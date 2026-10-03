package com.fahim.myide;

import android.content.Context;
import android.os.Environment;

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
    private final StringBuilder fullLog = new StringBuilder();

    public ApkBuilder(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        fullLog.append(s).append('\n');
        if (progress != null) progress.onProgress(s);
    }

    public Result build(File projectRoot, int minSdk, int targetSdk) {
        StringBuilder log = new StringBuilder();
        try {
            try {
                String aapt2Path = ctx.getApplicationInfo().nativeLibraryDir + "/libaapt2.so";
                ProcessBuilder pbV = new ProcessBuilder(aapt2Path, "version");
                pbV.redirectErrorStream(true);
                Process pV = pbV.start();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                InputStream isV = pV.getInputStream();
                byte[] bV = new byte[1024];
                int nV;
                while ((nV = isV.read(bV)) > 0) bo.write(bV, 0, nV);
                pV.waitFor();
                say("aapt2 version: " + bo.toString().trim());
            } catch (Throwable t) {
                say("aapt2 version check failed: " + t);
            }

            say("Preparing...");

            File workDir = new File(ctx.getFilesDir(), "build_area");
            deleteRecursive(workDir);
            workDir.mkdirs();

            List<File> sourceRoots = new ArrayList<File>();
            List<File> resRoots = new ArrayList<File>();
            List<File> jarDeps = new ArrayList<File>();
            List<File> aarDeps = new ArrayList<File>();

            File appManifest = findManifest(projectRoot);
            File appRes      = findRes(projectRoot);
            File appSrc      = findSrc(projectRoot);

            if (appManifest == null) return fail(log, "AndroidManifest.xml not found in project");
            if (appRes == null)      return fail(log, "res/ folder not found in project");
            if (appSrc == null)      return fail(log, "src/ folder not found in project");

            sourceRoots.add(appSrc);
            resRoots.add(appRes);

            File parent = appManifest.getParentFile();
            if (parent != null) {
                File sibSrc = new File(parent, "src");
                if (!sibSrc.exists()) sibSrc = new File(parent, "java");
                if (sibSrc.exists() && !sibSrc.equals(appSrc)) sourceRoots.add(sibSrc);

                File sibRes = new File(parent, "res");
                if (sibRes.exists() && !sibRes.equals(appRes)) resRoots.add(sibRes);
            }

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

                        File modRes = findRes(modDir);
                        File modSrc = findSrc(modDir);

                        if (modSrc != null) sourceRoots.add(modSrc);
                        if (modRes != null) resRoots.add(modRes);

                        collectDeps(new File(modDir, "libs"), jarDeps, aarDeps);
                    }
                }
            }

            File depsFile = new File(projectRoot, ".myide/deps.txt");
            if (depsFile.exists()) {
                File mavenDir = new File(workDir, "maven_libs");
                mavenDir.mkdirs();
                int n = 0;
                try { n = MavenResolver.copyResolvedToDir(ctx, depsFile, mavenDir); }
                catch (Exception e) { say("Maven copy failed: " + e.getMessage()); }
                if (n == 0) {
                    if (autoResolveDeps(depsFile)) {
                        try { n = MavenResolver.copyResolvedToDir(ctx, depsFile, mavenDir); }
                        catch (Exception e) { say("Maven copy failed: " + e.getMessage()); }
                    }
                }
                if (n > 0) collectDeps(mavenDir, jarDeps, aarDeps);
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

            say("Loading bundled AARs...");
            aarDeps.addAll(extractBundledAars());

            say("Extracting tools...");
            File androidJar  = extractAsset("android.jar");
            File lambdaStubs = extractAsset("core-lambda-stubs.jar");
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

            say("Compiling app resources...");
            File appResFlat = new File(workDir, "app_res_flat");
            appResFlat.mkdirs();
            for (File r : resRoots) {
                if (!r.exists()) continue;
                File flatOut = new File(appResFlat, r.getName() + "_" + Math.abs(r.hashCode()));
                flatOut.mkdirs();
                runAapt2("compile", "--dir", r.getAbsolutePath(), "-o", flatOut.getAbsolutePath());
            }

            say("Building AAR libraries...");
            File aarClassesDir = new File(workDir, "aar_classes");
            aarClassesDir.mkdirs();

            List<File> aarFlatFiles = new ArrayList<File>();

            int aarIdx = 0;
            for (File aar : aarDeps) {
                if (aar.getName().endsWith(".jar")) {
                    jarDeps.add(aar);
                    continue;
                }

                aarIdx++;
                say("Processing AAR " + aarIdx + ": " + aar.getName());

                File extractDir = new File(workDir, "aar_extract/" + aar.getName().replace(".", "_"));
                extractDir.mkdirs();
                unzipTo(aar, extractDir);

                File aarClasses = new File(extractDir, "classes.jar");
                if (aarClasses.exists()) {
                    File dest = new File(aarClassesDir, aar.getName().replace(".aar", "_classes.jar"));
                    copyFile(aarClasses, dest);
                    jarDeps.add(dest);
                }

                File aarLibs = new File(extractDir, "libs");
                if (aarLibs.exists()) {
                    File[] libJars = aarLibs.listFiles();
                    if (libJars != null) for (File lj : libJars) {
                        if (lj.isFile() && lj.getName().endsWith(".jar")) {
                            File dest = new File(aarClassesDir,
                                aar.getName().replace(".aar", "_") + lj.getName());
                            copyFile(lj, dest);
                            jarDeps.add(dest);
                        }
                    }
                }

                File aarRes = new File(extractDir, "res");
                if (!aarRes.exists()) continue;

                File aarResFlat = new File(workDir, "aar_res_flat/" + aarIdx);
                aarResFlat.mkdirs();
                runAapt2("compile", "--dir", aarRes.getAbsolutePath(),
                         "-o", aarResFlat.getAbsolutePath());

                File[] aarFlat = aarResFlat.listFiles();
                if (aarFlat != null) for (File f : aarFlat) {
                    if (f.getName().endsWith(".flat")) aarFlatFiles.add(f);
                }
            }

            File patchedManifest = new File(workDir, "AndroidManifest.xml");
            patchManifest(appManifest, patchedManifest, minSdk, targetSdk);

            say("Linking app resources (aapt2)...");
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
            linkArgs.add("--auto-add-overlay");
            linkArgs.add("--no-version-vectors");
            linkArgs.add("-o"); linkArgs.add(unsignedApk.getAbsolutePath());

            File[] flatDirs = appResFlat.listFiles();
            if (flatDirs != null) {
                for (File d : flatDirs) {
                    File[] inner = d.listFiles();
                    if (inner != null) for (File f : inner) {
                        if (f.getName().endsWith(".flat")) linkArgs.add(f.getAbsolutePath());
                    }
                }
            }

            for (File f : aarFlatFiles) {
                linkArgs.add("-R");
                linkArgs.add(f.getAbsolutePath());
            }

            runAapt2(linkArgs.toArray(new String[0]));

            File classesDir = new File(workDir, "classes");
            classesDir.mkdirs();

            boolean hasKotlin = false;
            for (File src : sourceRoots) if (hasKtFiles(src)) { hasKotlin = true; break; }

            if (hasKotlin) {
                String mode = ctx.getSharedPreferences("kotlin", Context.MODE_PRIVATE)
                                 .getString("mode", "auto");
                File kotlincJar = null, ktStdlib = null;
                try { kotlincJar = extractAsset("kotlin-compiler-embeddable-1.9.24.jar"); }
                catch (Exception ignored) {}
                try { ktStdlib = extractAsset("kotlin-stdlib-1.9.24.jar"); }
                catch (Exception ignored) {}

                boolean compiled = false;
                if ("remote".equals(mode) || ("auto".equals(mode) && kotlincJar == null)) {
                    say("Kotlin: remote...");
                    try {
                        RemoteKotlinCompiler rkc = new RemoteKotlinCompiler(ctx,
                            new RemoteKotlinCompiler.Progress() {
                                @Override public void onProgress(String m) { say(m); }
                            });
                        rkc.compile(sourceRoots, classesDir);
                        if (ktStdlib != null && ktStdlib.exists()) jarDeps.add(ktStdlib);
                        compiled = true;
                    } catch (Throwable t) { say("Remote Kotlin failed"); }
                }
                if (!compiled && kotlincJar != null) {
                    say("Kotlin: local...");
                    KotlinCompiler kc = new KotlinCompiler(ctx, new KotlinCompiler.Progress() {
                        @Override public void onProgress(String m) { say(m); }
                    });
                    kc.compile(kotlincJar, ktStdlib, androidJar, sourceRoots,
                               genDir, classesDir, jarDeps);
                    if (ktStdlib != null && ktStdlib.exists()) jarDeps.add(ktStdlib);
                }
            }

            jarDeps.add(lambdaStubs);
            say("Compiling Java (ECJ)...");
            compileJava(androidJar, ecjFull, ecjResDir, sourceRoots, genDir, classesDir, jarDeps);

            say("Dexing (R8 → D8 fallback)...");
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
            writeBuildLog();
            return new Result(true, signedApk, log.toString());

        } catch (Throwable t) {
            String err = "ERROR: " + causeChain(t);
            log.append(err).append('\n');
            say(err);
            writeBuildLog();
            return new Result(false, null, err);
        }
    }

    private void writeBuildLog() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "MyIDE");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "myide_build.log");
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(fullLog.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
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
        say(msg);
        writeBuildLog();
        return new Result(false, null, msg);
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

        StringBuilder cmdLine = new StringBuilder("aapt2 ");
        for (String a : args) cmdLine.append(a).append(' ');
        say("$ " + cmdLine.toString());

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
            String out = baos.toString();
            say("aapt2 failed (" + code + "):\n" + out);
            throw new RuntimeException("aapt2 failed (" + code + "):\n" + out);
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
            String msg = "ECJ error: " + causeChain(ite);
            say(msg);
            throw new RuntimeException(msg);
        }
        writer.flush();
        if (ok == null || !ok) {
            String msg = "Java compile failed:\n" + baos.toString();
            say(msg);
            throw new RuntimeException(msg);
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

    // ============ DEX with R8 → D8 fallback ============
    private void compileDex(File androidJar, File d8Zip, File classesDir,
                            File outputDir, List<File> extraJars, int minSdk) throws Exception {
        List<File> classFiles = new ArrayList<File>();
        findClassFiles(classesDir, classFiles);
        if (classFiles.isEmpty()) throw new RuntimeException("No .class files to dex");

        if (!outputDir.exists()) outputDir.mkdirs();

        boolean r8Ok = false;
        try {
            r8Ok = runR8(androidJar, d8Zip, classFiles, outputDir, extraJars, minSdk);
        } catch (Throwable t) {
            say("R8 threw: " + causeChain(t));
            r8Ok = false;
        }

        if (!r8Ok) {
            say("R8 failed → falling back to D8...");
            deleteRecursive(outputDir);
            outputDir.mkdirs();
            runD8(androidJar, d8Zip, classFiles, outputDir, extraJars, minSdk);
        } else {
            say("R8 succeeded");
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
        if (!anyDex) throw new RuntimeException("No .dex files produced");
    }

    private boolean runR8(File androidJar, File d8Zip, List<File> classFiles,
                          File outputDir, List<File> extraJars, int minSdk) throws Exception {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream r8Out = new ByteArrayOutputStream();
        ByteArrayOutputStream r8Err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(r8Out, true));
        System.setErr(new PrintStream(r8Err, true));

        try {
            DexClassLoader loader = new DexClassLoader(
                d8Zip.getAbsolutePath(),
                ctx.getCacheDir().getAbsolutePath(),
                null,
                ctx.getClassLoader());

            Class<?> r8Class = loader.loadClass("com.android.tools.r8.R8");
            Method main = r8Class.getMethod("main", String[].class);

            List<String> args = new ArrayList<String>();
            args.add("--release");
            args.add("--output");   args.add(outputDir.getAbsolutePath());
            args.add("--min-api");  args.add(String.valueOf(Math.max(minSdk, 24)));

            File keepRules = new File(ctx.getFilesDir(), "r8-keep.pro");
            writeKeepRules(keepRules);
            args.add("--pg-conf");  args.add(keepRules.getAbsolutePath());

            File mapFile = new File(outputDir, "mapping.txt");
            args.add("--pg-map-output"); args.add(mapFile.getAbsolutePath());

            for (File j : extraJars) {
                if (j != null && j.exists() && j.getName().endsWith(".jar")) {
                    args.add(j.getAbsolutePath());
                }
            }

            for (File f : classFiles) args.add(f.getAbsolutePath());

            try {
                main.invoke(null, (Object) args.toArray(new String[0]));
            } catch (InvocationTargetException ite) {
                say("R8 error: " + causeChain(ite));
                say("--- R8 stdout ---\n" + r8Out.toString());
                say("--- R8 stderr ---\n" + r8Err.toString());
                return false;
            }
            return true;
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private void runD8(File androidJar, File d8Zip, List<File> classFiles,
                       File outputDir, List<File> extraJars, int minSdk) throws Exception {
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
                String msg = "D8 error: " + causeChain(ite)
                    + "\n--- stdout ---\n" + d8Out.toString()
                    + "\n--- stderr ---\n" + d8Err.toString();
                say(msg);
                throw new RuntimeException(msg);
            }
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private void writeKeepRules(File f) throws Exception {
        String rules =
            "-keep public class * extends android.app.Activity\n" +
            "-keep public class * extends android.app.Application\n" +
            "-keep public class * extends android.app.Service\n" +
            "-keep public class * extends android.content.BroadcastReceiver\n" +
            "-keep public class * extends android.content.ContentProvider\n" +
            "-keep public class * extends android.view.View {\n" +
            "    public <init>(android.content.Context);\n" +
            "    public <init>(android.content.Context, android.util.AttributeSet);\n" +
            "    public <init>(android.content.Context, android.util.AttributeSet, int);\n" +
            "}\n" +
            "-keepclassmembers class * {\n" +
            "    @android.webkit.JavascriptInterface <methods>;\n" +
            "}\n" +
            "-keepattributes *Annotation*\n" +
            "-keepattributes SourceFile,LineNumberTable\n" +
            "-keep class com.google.gson.** { *; }\n" +
            "-keep class * extends java.lang.annotation.Annotation { *; }\n" +
            "-keepclassmembers class * {\n" +
            "    @androidx.annotation.Keep <fields>;\n" +
            "    @androidx.annotation.Keep <methods>;\n" +
            "}\n" +
            "-dontwarn **\n" +
            "-ignorewarnings\n";
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(rules.getBytes("UTF-8"));
        fos.close();
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
            String msg = "Sign error: " + causeChain(ite);
            say(msg);
            throw new RuntimeException(msg);
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
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.startsWith("classes") && name.endsWith(".dex")) {
                continue;
            }
            zout.putNextEntry(new ZipEntry(name));
            int n;
            while ((n = zin.read(buf)) > 0) zout.write(buf, 0, n);
            zout.closeEntry();
        }
        zin.close();

        for (File dex : dexFiles) {
            zout.putNextEntry(new ZipEntry(dex.getName()));
            FileInputStream fin = new FileInputStream(dex);
            int n;
            while ((n = fin.read(buf)) > 0) zout.write(buf, 0, n);
            fin.close();
            zout.closeEntry();
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