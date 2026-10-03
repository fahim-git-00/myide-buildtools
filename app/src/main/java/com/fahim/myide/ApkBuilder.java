package com.fahim.myide;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
            // ===== DEBUG: capture aapt2 link --help =====
            String aapt2HelpText = "";
            try {
                String aapt2Path = ctx.getApplicationInfo().nativeLibraryDir + "/libaapt2.so";
                ProcessBuilder pbDbg = new ProcessBuilder(aapt2Path, "link", "--help");
                pbDbg.redirectErrorStream(true);
                Process pDbg = pbDbg.start();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                InputStream iDbg = pDbg.getInputStream();
                byte[] bb = new byte[4096];
                int nn;
                while ((nn = iDbg.read(bb)) > 0) bo.write(bb, 0, nn);
                pDbg.waitFor();
                aapt2HelpText = bo.toString();
            } catch (Throwable t) {
                aapt2HelpText = "ERROR: " + t;
            }
            // Short-circuit: return help text so it shows in the dialog
            return new Result(false, null, "AAPT2 HELP OUTPUT:\n\n" + aapt2HelpText);
            // ===== END DEBUG =====

        } catch (Throwable t) {
            log.append("ERROR: ").append(causeChain(t)).append('\n');
            return new Result(false, null, log.toString());
        }
    }

    private String extractPackage(File manifest) {
        try {
            String xml = readFile(manifest);
            Matcher m = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(xml);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    private List<File> extractBundledAars() {
        List<File> out = new ArrayList<File>();
        try {
            String[] names = ctx.getAssets().list("aar");
            if (names == null) return out;
            File aarCache = new File(ctx.getFilesDir(), "bundled_aar");
            if (!aarCache.exists()) aarCache.mkdirs();
            for (String n : names) {
                if (!n.endsWith(".aar") && !n.endsWith(".jar")) continue;
                File dest = new File(aarCache, n);
                if (!dest.exists() || dest.length() == 0) {
                    InputStream in = ctx.getAssets().open("aar/" + n);
                    FileOutputStream fos = new FileOutputStream(dest);
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
                    fos.close();
                    in.close();
                }
                out.add(dest);
            }
        } catch (Exception e) {
            say("Bundled AAR load failed: " + e.getMessage());
        }
        return out;
    }

    private File findManifest(File root) {
        if (root == null) return null;
        File m = new File(root, "AndroidManifest.xml");
        if (m.isFile()) return m;
        File m1 = new File(root, "app/src/main/AndroidManifest.xml");
        if (m1.isFile()) return m1;
        File m2 = new File(root, "src/main/AndroidManifest.xml");
        if (m2.isFile()) return m2;
        File m3 = new File(root, "src/AndroidManifest.xml");
        if (m3.isFile()) return m3;
        return null;
    }

    private File findRes(File root) {
        if (root == null) return null;
        File r = new File(root, "res");
        if (r.isDirectory()) return r;
        File r1 = new File(root, "app/src/main/res");
        if (r1.isDirectory()) return r1;
        File r2 = new File(root, "src/main/res");
        if (r2.isDirectory()) return r2;
        File r3 = new File(root, "src/res");
        if (r3.isDirectory()) return r3;
        return null;
    }

    private File findSrc(File root) {
        if (root == null) return null;
        File s = new File(root, "src");
        if (s.isDirectory()) return s;
        File s1 = new File(root, "java");
        if (s1.isDirectory()) return s1;
        File s2 = new File(root, "app/src/main/java");
        if (s2.isDirectory()) return s2;
        File s3 = new File(root, "src/main/java");
        if (s3.isDirectory()) return s3;
        return null;
    }

    private boolean hasKtFiles(File dir) {
        if (dir == null || !dir.exists()) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.isDirectory()) { if (hasKtFiles(f)) return true; }
            else if (f.getName().endsWith(".kt")) return true;
        }
        return false;
    }

    private boolean autoResolveDeps(File depsFile) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);

        MavenResolver resolver = new MavenResolver(ctx, new MavenResolver.Progress() {
            @Override public void onProgress(String message) { say(message); }
            @Override public void onDone(boolean success, String message) {
                ok.set(success);
                say("Resolve: " + message);
                latch.countDown();
            }
        });

        try {
            resolver.resolve(depsFile);
            boolean finished = latch.await(5, TimeUnit.MINUTES);
            if (!finished) return false;
            return ok.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            say("Resolve error: " + causeChain(t));
            return false;
        }
    }

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
        List<File> javaFiles = new ArrayList<File>();
        for (File src : sourceRoots) findJavaFiles(src, javaFiles);
        findJavaFiles(genDir, javaFiles);

        if (javaFiles.isEmpty()) {
            say("No .java files to compile");
            return;
        }

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

        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream d8Out = new ByteArrayOutputStream();
        ByteArrayOutputStream d8Err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(d8Out, true));
        System.setErr(new PrintStream(d8Err, true));

        try {
            DexClassLoader loader = new DexClassLoader(
                d8Zip.getAbsolutePath(),
                ctx.getCacheDir().getAbsolutePath(),
                null,
                ctx.getClassLoader());

            Class<?> d8Class = loader.loadClass("com.android.tools.r8.D8");
            Method main = d8Class.getMethod("main", String[].class);

            List<String> args = new ArrayList<String>();
            args.add("--output");   args.add(outputDir.getAbsolutePath());
            args.add("--min-api");  args.add(String.valueOf(minSdk));
            args.add("--lib");      args.add(androidJar.getAbsolutePath());

            for (File j : extraJars) {
                if (j != null && j.exists() && j.getName().endsWith(".jar")) {
                    args.add(j.getAbsolutePath());
                }
            }

            for (File f : classFiles) args.add(f.getAbsolutePath());

            try {
                main.invoke(null, (Object) args.toArray(new String[0]));
            } catch (InvocationTargetException ite) {
                throw new RuntimeException("D8 error: " + causeChain(ite)
                    + "\n--- stdout ---\n" + d8Out.toString()
                    + "\n--- stderr ---\n" + d8Err.toString());
            }

            File[] kids = outputDir.listFiles();
            boolean anyDex = false;
            if (kids != null) {
                for (File f : kids) {
                    if (f.getName().endsWith(".dex") && f.length() > 0) {
                        anyDex = true;
                        break;
                    }
                }
            }

            if (!anyDex) {
                throw new RuntimeException("D8 produced no .dex files.\n"
                    + "--- stdout ---\n" + d8Out.toString()
                    + "\n--- stderr ---\n" + d8Err.toString());
            }
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
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

        String pkg = "";
        Matcher pm = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(xml);
        if (pm.find()) pkg = pm.group(1);

        xml = xml.replaceAll("<uses-sdk[^>]*/>", "");
        xml = xml.replaceAll("<uses-sdk.*?</uses-sdk>", "");
        xml = xml.replaceAll("android:applicationId\\s*=\\s*\"[^\"]*\"", "");

        String usesSdk = "<uses-sdk android:minSdkVersion=\"" + minSdk
            + "\" android:targetSdkVersion=\"" + targetSdk + "\" />\n    ";

        int mStart = xml.indexOf("<manifest");
        int mEnd = xml.indexOf('>', mStart);
        if (mStart >= 0 && mEnd > 0) {
            String head = xml.substring(0, mEnd + 1);
            String tail = xml.substring(mEnd + 1);

            if (pkg != null && pkg.length() > 0 && !head.contains("package=")) {
                head = head.replaceFirst("<manifest", "<manifest package=\"" + pkg + "\"");
            }
            xml = head + "\n    " + usesSdk + tail;
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