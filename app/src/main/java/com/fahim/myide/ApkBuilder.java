package com.fahim.myide;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import dalvik.system.DexClassLoader;

public class ApkBuilder {

    public interface Progress {
        void onProgress(String message);
    }

    public static class Result {
        public boolean success;
        public File apk;
        public String log;
        public Result(boolean s, File a, String l) { success = s; apk = a; log = l; }
    }

    private final Context ctx;
    private final Progress progress;

    public ApkBuilder(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    public Result build(File projectRoot, int minSdk, int targetSdk) {
        StringBuilder log = new StringBuilder();
        try {
            say("Preparing...");

            File workDir = new File(ctx.getFilesDir(), "build_area");
            deleteRecursive(workDir);
            workDir.mkdirs();

            List<File> sourceRoots = new ArrayList<File>();
            List<File> resRoots = new ArrayList<File>();
            List<File> jarDeps = new ArrayList<File>();
            List<File> aarDeps = new ArrayList<File>();

            File appManifest = new File(projectRoot, "AndroidManifest.xml");
            File appRes = new File(projectRoot, "res");
            File appSrc = new File(projectRoot, "src");
            if (!appSrc.exists()) appSrc = new File(projectRoot, "java");
            if (!appSrc.exists()) appSrc = new File(projectRoot, "src/main/java");

            if (!appManifest.exists()) return fail(log, "AndroidManifest.xml not found");
            if (!appRes.exists())      return fail(log, "res/ folder not found");
            if (!appSrc.exists())      return fail(log, "src/ folder not found");

            sourceRoots.add(appSrc);
            resRoots.add(appRes);

            File settingsGradle = new File(projectRoot, "settings.gradle");
            if (settingsGradle.exists()) {
                String settings = readFile(settingsGradle);
                Matcher m = Pattern.compile("include\\s+([^\\n]+)").matcher(settings);
                while (m.find()) {
                    String line = m.group(1);
                    Matcher nm = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(line);
                    while (nm.find()) {
                        String modName = nm.group(1).replace(':', '/');
                        File modDir = new File(projectRoot, modName);
                        if (!modDir.exists()) continue;

                        File modRes = new File(modDir, "res");
                        File modSrc = new File(modDir, "src");
                        if (!modSrc.exists()) modSrc = new File(modDir, "java");
                        if (!modSrc.exists()) modSrc = new File(modDir, "src/main/java");

                        if (modSrc.exists()) sourceRoots.add(modSrc);
                        if (modRes.exists()) resRoots.add(modRes);

                        collectDeps(new File(modDir, "libs"), jarDeps, aarDeps);
                    }
                }
            }

            // ---- Copy resolved Maven deps from cache ----
            File depsFile = new File(projectRoot, ".myide/deps.txt");
            if (depsFile.exists()) {
                say("Copying Maven deps...");
                File mavenDir = new File(workDir, "maven_libs");
                mavenDir.mkdirs();
                try {
                    int n = MavenResolver.copyResolvedToDir(ctx, depsFile, mavenDir);
                    if (n > 0) {
                        say("Copied " + n + " Maven files");
                        collectDeps(mavenDir, jarDeps, aarDeps);
                    }
                } catch (Exception e) {
                    say("Maven copy failed: " + e.getMessage());
                }
            }

            collectDeps(new File(projectRoot, "libs"), jarDeps, aarDeps);
            collectDeps(new File(projectRoot, "app/libs"), jarDeps, aarDeps);

            File[] rootFiles = projectRoot.listFiles();
            if (rootFiles != null) {
                for (File f : rootFiles) {
                    if (!f.isFile()) continue;
                    if (f.getName().endsWith(".jar")) jarDeps.add(f);
                    else if (f.getName().endsWith(".aar")) aarDeps.add(f);
                }
            }

            say("Extracting tools...");
            File androidJar  = extractAsset("android.jar");
            File ecjFull     = extractAsset("ecj_full.jar");
            File ecjResZip   = extractAsset("ecj_res.zip");
            File d8Zip       = extractAsset("d8.zip");
            File apksigner   = extractAsset("apksigner-full.jar");
            File keyPk8      = extractAsset("keys/mykey.pk8");
            File keyPem      = extractAsset("keys/mykey.x509.pem");

            File ecjResDir = new File(ctx.getFilesDir(), "ecj_res");
            deleteRecursive(ecjResDir);
            ecjResDir.mkdirs();
            unzipTo(ecjResZip, ecjResDir);

            say("Merging resources...");
            File mergedRes = new File(workDir, "res_merged");
            mergedRes.mkdirs();
            for (File r : resRoots) {
                if (r.exists()) copyDirContents(r, mergedRes);
            }

            File aarClassesDir = new File(workDir, "aar_classes");
            aarClassesDir.mkdirs();
            for (File aar : aarDeps) {
                say("Extracting AAR: " + aar.getName());
                File extractDir = new File(workDir, "aar_extract/" + aar.getName().replace(".", "_"));
                extractDir.mkdirs();
                unzipTo(aar, extractDir);

                File aarRes = new File(extractDir, "res");
                if (aarRes.exists()) copyDirContents(aarRes, mergedRes);

                File aarClasses = new File(extractDir, "classes.jar");
                if (aarClasses.exists()) {
                    File dest = new File(aarClassesDir, aar.getName().replace(".aar", "_classes.jar"));
                    copyFile(aarClasses, dest);
                    jarDeps.add(dest);
                }
            }

            File patchedManifest = new File(workDir, "AndroidManifest.xml");
            patchManifest(appManifest, patchedManifest, minSdk, targetSdk);

            say("Compiling resources (aapt2)...");
            File compiledRes = new File(workDir, "compiled_res");
            compiledRes.mkdirs();
            runAapt2("compile", "--dir", mergedRes.getAbsolutePath(),
                     "-o", compiledRes.getAbsolutePath());

            say("Linking resources (aapt2)...");
            File genDir = new File(workDir, "gen");
            genDir.mkdirs();
            File unsignedApk = new File(workDir, "app-unsigned.apk");

            List<String> linkArgs = new ArrayList<String>();
            linkArgs.add("link");
            linkArgs.add("-I"); linkArgs.add(androidJar.getAbsolutePath());
            linkArgs.add("--manifest"); linkArgs.add(patchedManifest.getAbsolutePath());
            linkArgs.add("--java"); linkArgs.add(genDir.getAbsolutePath());
            linkArgs.add("--min-sdk-version"); linkArgs.add(String.valueOf(minSdk));
            linkArgs.add("--target-sdk-version"); linkArgs.add(String.valueOf(targetSdk));
            linkArgs.add("-o"); linkArgs.add(unsignedApk.getAbsolutePath());

            File[] flat = compiledRes.listFiles();
            if (flat != null) for (File f : flat) {
                if (f.getName().endsWith(".flat")) linkArgs.add(f.getAbsolutePath());
            }
            runAapt2(linkArgs.toArray(new String[0]));

            say("Compiling Java (ECJ)...");
            File classesDir = new File(workDir, "classes");
            classesDir.mkdirs();
            compileJava(androidJar, ecjFull, ecjResDir, sourceRoots, genDir, classesDir, jarDeps);

            say("Dexing (R8)...");
            File dexDir = new File(workDir, "dex");
            dexDir.mkdirs();
            compileDex(androidJar, d8Zip, classesDir, dexDir, jarDeps, minSdk);

            say("Packaging APK...");
            File withDex = new File(workDir, "app-withdex.apk");
            addDexToApk(unsignedApk, dexDir, withDex);

            say("Signing APK...");
            File signedApk = new File(workDir, "app-signed.apk");
            signApk(apksigner, keyPk8, keyPem, withDex, signedApk);

            say("Done");
            return new Result(true, signedApk, log.toString());

        } catch (Throwable t) {
            log.append("ERROR: ").append(causeChain(t)).append('\n');
            return new Result(false, null, log.toString());
        }
    }

    // ---------- helpers ----------

    private Result fail(StringBuilder log, String msg) {
        log.append(msg).append('\n');
        return new Result(false, null, log.toString());
    }

    private void collectDeps(File libsDir, List<File> jarOut, List<File> aarOut) {
        if (libsDir == null || !libsDir.exists() || !libsDir.isDirectory()) return;
        File[] files = libsDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            if (f.getName().endsWith(".jar")) jarOut.add(f);
            else if (f.getName().endsWith(".aar")) aarOut.add(f);
        }
    }

    private File extractAsset(String name) throws IOException {
        File out = new File(ctx.getFilesDir(), name);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (out.exists() && out.length() > 0) return out;

        InputStream in = ctx.getAssets().open(name);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        return out;
    }

    private void runAapt2(String... args) throws Exception {
        String path = ctx.getApplicationInfo().nativeLibraryDir + "/libaapt2.so";
        List<String> cmd = new ArrayList<String>();
        cmd.add(path);
        for (String a : args) cmd.add(a);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        InputStream is = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        int code = p.waitFor();

        if (code != 0) {
            throw new RuntimeException("aapt2 failed (" + code + "):\n" + baos.toString());
        }
    }

    private void compileJava(File androidJar, File ecjFull, File ecjResDir,
                             List<File> sourceRoots, File genDir, File classesDir,
                             List<File> extraJars) throws Exception {
        ResourceAwareLoader loader = new ResourceAwareLoader(
            ecjFull.getAbsolutePath(),
            ctx.getCacheDir(),
            ctx.getClassLoader(),
            ecjResDir);

        Class<?> mainClass = loader.loadClass("org.eclipse.jdt.internal.compiler.batch.Main");

        StringBuilder cp = new StringBuilder();
        cp.append(androidJar.getAbsolutePath());
        for (File j : extraJars) {
            if (j != null && j.exists()) cp.append(File.pathSeparator).append(j.getAbsolutePath());
        }

        List<String> args = new ArrayList<String>();
        args.add("-1.8");
        args.add("-proc:none");
        args.add("-nowarn");
        args.add("-classpath");
        args.add(cp.toString());
        args.add("-d");
        args.add(classesDir.getAbsolutePath());

        List<File> javaFiles = new ArrayList<File>();
        for (File src : sourceRoots) findJavaFiles(src, javaFiles);
        findJavaFiles(genDir, javaFiles);
        if (javaFiles.isEmpty()) throw new RuntimeException("No .java files found");
        for (File f : javaFiles) args.add(f.getAbsolutePath());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(baos);
        Object instance = mainClass.getConstructor(PrintWriter.class, PrintWriter.class, boolean.class)
            .newInstance(writer, writer, false);
        Method compile = mainClass.getMethod("compile", String[].class);

        Boolean ok;
        try {
            ok = (Boolean) compile.invoke(instance, (Object) args.toArray(new String[0]));
        } catch (InvocationTargetException ite) {
            throw new RuntimeException("ECJ error: " + causeChain(ite));
        }
        writer.flush();
        if (ok == null || !ok) {
            throw new RuntimeException("Java compile failed:\n" + baos.toString());
        }
    }

    private static class ResourceAwareLoader extends DexClassLoader {
        private final File resDir;
        ResourceAwareLoader(String dexPath, File optDir, ClassLoader parent, File resDir) {
            super(dexPath, optDir.getAbsolutePath(), null, parent);
            this.resDir = resDir;
        }
        @Override public InputStream getResourceAsStream(String name) {
            File f = new File(resDir, name);
            if (f.exists() && f.isFile()) {
                try { return new FileInputStream(f); } catch (Exception ignored) {}
            }
            return super.getResourceAsStream(name);
        }
        @Override public URL getResource(String name) {
            File f = new File(resDir, name);
            if (f.exists() && f.isFile()) {
                try { return f.toURI().toURL(); } catch (Exception ignored) {}
            }
            return super.getResource(name);
        }
    }

    private void compileDex(File androidJar, File d8Zip, File classesDir,
                            File outputDir, List<File> extraJars, int minSdk) throws Exception {
        List<File> classFiles = new ArrayList<File>();
        findClassFiles(classesDir, classFiles);
        if (classFiles.isEmpty()) throw new RuntimeException("No .class files to dex");

        if (!outputDir.exists()) outputDir.mkdirs();

        DexClassLoader loader = new DexClassLoader(
            d8Zip.getAbsolutePath(),
            ctx.getCacheDir().getAbsolutePath(),
            null,
            ctx.getClassLoader());

        Class<?> r8 = loader.loadClass("com.android.tools.r8.R8");
        Method main = r8.getMethod("main", String[].class);

        List<String> args = new ArrayList<String>();
        args.add("--output"); args.add(outputDir.getAbsolutePath());
        args.add("--min-api"); args.add(String.valueOf(minSdk));
        args.add("--lib"); args.add(androidJar.getAbsolutePath());
        args.add("--release");
        for (File f : classFiles) args.add(f.getAbsolutePath());
        for (File j : extraJars) {
            if (j != null && j.exists() && j.getName().endsWith(".jar")) {
                args.add("--lib");
                args.add(j.getAbsolutePath());
            }
        }

        try {
            main.invoke(null, (Object) args.toArray(new String[0]));
        } catch (InvocationTargetException ite) {
            throw new RuntimeException("R8 error: " + causeChain(ite));
        }
    }

    private void signApk(File apksigner, File pk8, File pem,
                         File inApk, File outApk) throws Exception {
        DexClassLoader loader = new DexClassLoader(
            apksigner.getAbsolutePath(),
            ctx.getCacheDir().getAbsolutePath(),
            null,
            ctx.getClassLoader());

        Class<?> tool = loader.loadClass("com.android.apksigner.ApkSignerTool");
        Method main = tool.getMethod("main", String[].class);

        List<String> args = new ArrayList<String>();
        args.add("sign");
        args.add("--key"); args.add(pk8.getAbsolutePath());
        args.add("--cert"); args.add(pem.getAbsolutePath());
        args.add("--out"); args.add(outApk.getAbsolutePath());
        args.add(inApk.getAbsolutePath());

        try {
            main.invoke(null, (Object) args.toArray(new String[0]));
        } catch (InvocationTargetException ite) {
            throw new RuntimeException("Sign error: " + causeChain(ite));
        }
        if (!outApk.exists() || outApk.length() == 0) {
            throw new RuntimeException("Signing produced no output");
        }
    }

    private void patchManifest(File in, File out, int minSdk, int targetSdk) throws Exception {
        String xml = readFile(in);
        xml = xml.replaceAll("<uses-sdk[^/]*/>", "");
        xml = xml.replaceAll("<uses-sdk.*?</uses-sdk>", "");

        String usesSdk = "<uses-sdk android:minSdkVersion=\"" + minSdk
            + "\" android:targetSdkVersion=\"" + targetSdk + "\" />\n    ";
        int mStart = xml.indexOf("<manifest");
        int mEnd = xml.indexOf('>', mStart);
        if (mEnd > 0) {
            xml = xml.substring(0, mEnd + 1) + "\n    " + usesSdk + xml.substring(mEnd + 1);
        }

        FileOutputStream fos = new FileOutputStream(out);
        fos.write(xml.getBytes("UTF-8"));
        fos.close();
    }

    private void addDexToApk(File inApk, File dexDir, File outApk) throws Exception {
        if (outApk.exists()) outApk.delete();

        List<File> dexFiles = new ArrayList<File>();
        File[] kids = dexDir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (f.getName().endsWith(".dex")) dexFiles.add(f);
            }
        }
        if (dexFiles.isEmpty()) throw new RuntimeException("No dex output");

        ZipInputStream zin = new ZipInputStream(new FileInputStream(inApk));
        ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outApk));
        byte[] buf = new byte[8192];
        ZipEntry e;
        boolean hasDex = false;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.startsWith("classes") && name.endsWith(".dex")) {
                hasDex = true;
                continue;
            }
            zout.putNextEntry(new ZipEntry(name));
            int n;
            while ((n = zin.read(buf)) > 0) zout.write(buf, 0, n);
            zout.closeEntry();
        }
        zin.close();

        if (!hasDex) {
            for (File dex : dexFiles) {
                zout.putNextEntry(new ZipEntry(dex.getName()));
                FileInputStream fin = new FileInputStream(dex);
                int n;
                while ((n = fin.read(buf)) > 0) zout.write(buf, 0, n);
                fin.close();
                zout.closeEntry();
            }
        }
        zout.close();
    }

    private void unzipTo(File zip, File destDir) throws Exception {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            File out = new File(destDir, e.getName());
            if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                throw new SecurityException("Zip entry escapes target");
            }
            if (e.isDirectory()) out.mkdirs();
            else {
                File p = out.getParentFile();
                if (p != null) p.mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                int n;
                while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
            }
            zin.closeEntry();
        }
        zin.close();
    }

    private void copyDirContents(File srcDir, File dstDir) throws Exception {
        if (srcDir == null || !srcDir.exists()) return;
        File[] kids = srcDir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File target = new File(dstDir, k.getName());
            if (k.isDirectory()) {
                target.mkdirs();
                copyDirContents(k, target);
            } else {
                if (target.exists()) continue;
                File p = target.getParentFile();
                if (p != null) p.mkdirs();
                copyFile(k, target);
            }
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private void findJavaFiles(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) findJavaFiles(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    private void findClassFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) findClassFiles(f, out);
            else if (f.getName().endsWith(".class")) out.add(f);
        }
    }

    private String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int d = 0;
        while (t != null && d < 10) {
            sb.append(t.getClass().getSimpleName()).append(": ")
              .append(t.getMessage()).append('\n');
            Throwable next = (t instanceof InvocationTargetException)
                ? ((InvocationTargetException) t).getTargetException()
                : t.getCause();
            if (next == t) break;
            t = next;
            d++;
        }
        return sb.toString();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}