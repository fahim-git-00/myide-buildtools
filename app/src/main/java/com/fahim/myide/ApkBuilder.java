package com.fahim.myide;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import dalvik.system.DexClassLoader;

/**
 * On-device APK builder: aapt2 -> (kotlinc local/remote) -> ECJ -> D8 -> APK assembly -> apksigner.
 *
 * Speed design:
 *  - Build tools (d8, ecj, apksigner, android.jar ...) are extracted ONCE per app update, marked
 *    read-only (required for dynamic code loading on Android 14+), and their class loaders are
 *    cached for the life of the process so ART verifies / JIT-compiles them only once.
 *  - Every library jar is dexed once (D8 --intermediate) and cached by SHA-1; builds only re-dex
 *    your own classes and merge.
 *  - AAR files are unzipped and their resources compiled once, cached by SHA-1.
 */
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

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final int MIN_BUILD_API = 26;   // D8 / apksig / ECJ use java.nio.file (API 26+)

    // Class loaders for the build tools, cached per process.
    private static final Object LOADER_LOCK = new Object();
    private static final Map<String, ClassLoader> LOADERS = new HashMap<String, ClassLoader>();
    private static boolean sAaptChecked = false;

    private final Context ctx;
    private final Progress progress;
    private final StringBuilder fullLog = new StringBuilder();
    private final long startMs = System.currentTimeMillis();
    private long stampCache = Long.MIN_VALUE;
    private boolean lastPredexHit = false;

    public ApkBuilder(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        long dt = System.currentTimeMillis() - startMs;
        fullLog.append(String.format(Locale.US, "[%6dms] ", dt)).append(s).append('\n');
        if (progress != null) progress.onProgress(s);
    }

    // ------------------------------------------------------------------------------------
    // Main build
    // ------------------------------------------------------------------------------------

    public Result build(File projectRoot, int minSdk, int targetSdk) {
        StringBuilder log = new StringBuilder();
        try {
            if (Build.VERSION.SDK_INT < MIN_BUILD_API) {
                return fail(log, "On-device build tools need Android 8.0 (API 26) or newer. "
                    + "This device is API " + Build.VERSION.SDK_INT + ".");
            }
            if (minSdk < 21) {
                say("minSdk raised from " + minSdk + " to 21 (legacy multidex is not supported)");
                minSdk = 21;
            }
            if (targetSdk < minSdk) targetSdk = minSdk;

            String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "?";
            say("Device API " + Build.VERSION.SDK_INT + ", ABI " + abi);
            checkAapt2Once();

            say("Preparing...");
            File workDir = new File(ctx.getFilesDir(), "build_area");
            deleteRecursive(workDir);
            workDir.mkdirs();

            List<File> sourceRoots = new ArrayList<File>();
            List<File> resRoots = new ArrayList<File>();
            List<File> assetDirs = new ArrayList<File>();
            List<File> jniRoots = new ArrayList<File>();
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
            addSiblingDirs(appRes, assetDirs, jniRoots);

            File parent = appManifest.getParentFile();
            if (parent != null) {
                File sibSrc = new File(parent, "src");
                if (!sibSrc.exists()) sibSrc = new File(parent, "java");
                if (sibSrc.exists() && !sibSrc.equals(appSrc)) sourceRoots.add(sibSrc);

                File sibRes = new File(parent, "res");
                if (sibRes.exists() && !sibRes.equals(appRes)) resRoots.add(sibRes);

                addIfDir(assetDirs, new File(parent, "assets"));
                addIfDir(jniRoots, new File(parent, "jniLibs"));
            }
            addIfDir(assetDirs, new File(projectRoot, "assets"));
            addIfDir(jniRoots, new File(projectRoot, "jniLibs"));

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

                        if (modSrc != null && !sourceRoots.contains(modSrc)) sourceRoots.add(modSrc);
                        if (modRes != null && !resRoots.contains(modRes)) {
                            resRoots.add(modRes);
                            addSiblingDirs(modRes, assetDirs, jniRoots);
                        }

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
            File d8Zip       = extractAsset("d8.zip");
            File apksigner   = extractAsset("apksigner-full.jar");
            File keyPk8      = extractAsset("keys/mykey.pk8");
            File keyPem      = extractAsset("keys/mykey.x509.pem");
            File ecjResDir   = prepareEcjRes();

            say("Compiling app resources...");
            File appResFlat = new File(workDir, "app_res_flat");
            appResFlat.mkdirs();
            for (File r : resRoots) {
                if (!r.exists()) continue;
                File flatOut = new File(appResFlat, r.getName() + "_" + Math.abs(r.getAbsolutePath().hashCode()));
                flatOut.mkdirs();
                runAapt2("compile", "--dir", r.getAbsolutePath(), "-o", flatOut.getAbsolutePath());
            }

            say("Preparing AAR libraries...");
            List<AarInfo> aarInfos = new ArrayList<AarInfo>();
            Set<String> seenAar = new HashSet<String>();
            for (File aar : aarDeps) {
                if (aar.getName().endsWith(".jar")) {
                    jarDeps.add(aar);
                    continue;
                }
                String digest = sha1(aar);
                if (!seenAar.add(digest)) continue;       // same AAR twice
                AarInfo info = prepareAar(aar, digest);
                aarInfos.add(info);
                jarDeps.addAll(info.jars);
            }

            File patchedManifest = new File(workDir, "AndroidManifest.xml");
            patchManifest(appManifest, patchedManifest, minSdk, targetSdk, aarInfos);

            say("Linking app resources (aapt2)...");
            File genDir = new File(workDir, "gen");
            genDir.mkdirs();
            File unsignedApk = new File(workDir, "app-unsigned.apk");

            String appPkg = readPackage(patchedManifest);
            List<String> extraPkgs = new ArrayList<String>();
            for (AarInfo a : aarInfos) {
                if (a.pkg != null && a.pkg.length() > 0 && !a.pkg.equals(appPkg)
                    && !extraPkgs.contains(a.pkg)) extraPkgs.add(a.pkg);
            }

            List<String> linkArgs = new ArrayList<String>();
            linkArgs.add("link");
            linkArgs.add("-I"); linkArgs.add(androidJar.getAbsolutePath());
            linkArgs.add("--manifest"); linkArgs.add(patchedManifest.getAbsolutePath());
            linkArgs.add("--java"); linkArgs.add(genDir.getAbsolutePath());
            linkArgs.add("--min-sdk-version"); linkArgs.add(String.valueOf(minSdk));
            linkArgs.add("--target-sdk-version"); linkArgs.add(String.valueOf(targetSdk));
            linkArgs.add("--auto-add-overlay");
            linkArgs.add("--no-version-vectors");
            if (!extraPkgs.isEmpty()) {
                linkArgs.add("--extra-packages");
                linkArgs.add(join(extraPkgs, ":"));
            }
            for (File a : assetDirs) { linkArgs.add("-A"); linkArgs.add(a.getAbsolutePath()); }
            for (AarInfo a : aarInfos) {
                if (a.assets != null) { linkArgs.add("-A"); linkArgs.add(a.assets.getAbsolutePath()); }
            }
            linkArgs.add("-o"); linkArgs.add(unsignedApk.getAbsolutePath());

            // With --auto-add-overlay the LAST file wins on conflicts: libraries first, app last.
            for (AarInfo a : aarInfos) {
                for (File f : a.flats) linkArgs.add(f.getAbsolutePath());
            }
            File[] flatDirs = appResFlat.listFiles();
            if (flatDirs != null) {
                Arrays.sort(flatDirs);
                for (File d : flatDirs) {
                    File[] inner = d.listFiles();
                    if (inner != null) {
                        Arrays.sort(inner);
                        for (File f : inner) {
                            if (f.getName().endsWith(".flat")) linkArgs.add(f.getAbsolutePath());
                        }
                    }
                }
            }
            runAapt2(linkArgs.toArray(new String[0]));

            File classesDir = new File(workDir, "classes");
            classesDir.mkdirs();

            boolean hasKotlin = false;
            for (File src : sourceRoots) if (hasKtFiles(src)) { hasKotlin = true; break; }

            if (hasKotlin) {
                say("Compiling Kotlin...");
                File ktStdlib = null;
                try { ktStdlib = extractAsset("kotlin-stdlib-1.9.24.jar"); }
                catch (Exception ignored) {}
                compileKotlin(sourceRoots, genDir, classesDir, androidJar, ktStdlib, jarDeps);
                if (ktStdlib != null && !hasKotlinStdlib(jarDeps)) jarDeps.add(ktStdlib);
            }

            // de-duplicate identical jars (same content) so D8 never sees a type twice
            jarDeps = dedupeJars(jarDeps);

            say("Compiling Java (ECJ)...");
            compileJava(androidJar, lambdaStubs, ecjFull, ecjResDir, sourceRoots, genDir,
                        classesDir, jarDeps, hasKotlin);

            say("Dexing (D8)...");
            File dexDir = new File(workDir, "dex");
            dexDir.mkdirs();
            List<File> dexFiles = compileDex(androidJar, d8Zip, classesDir, workDir, dexDir,
                                             jarDeps, minSdk);

            say("Packaging APK...");
            File withDex = new File(workDir, "app-withdex.apk");
            assembleApk(unsignedApk, dexFiles, jniRoots, aarInfos, withDex);

            say("Signing APK...");
            File signedApk = new File(workDir, "app-signed.apk");
            signApk(apksigner, keyPk8, keyPem, withDex, signedApk, minSdk);

            trimCaches();
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

    // ------------------------------------------------------------------------------------
    // Tool extraction + class loader caching
    // ------------------------------------------------------------------------------------

    private long appStamp() {
        if (stampCache != Long.MIN_VALUE) return stampCache;
        long s;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            s = pi.lastUpdateTime;
        } catch (Exception e) {
            s = System.currentTimeMillis();   // unknown -> always re-extract
        }
        stampCache = s;
        return s;
    }

    /** Extracts an asset once per app update. File is read-only (needed for DCL on Android 14+). */
    private File extractAsset(String name) throws IOException {
        File out = new File(new File(ctx.getFilesDir(), "tools"), name);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        File marker = new File(out.getPath() + ".stamp");
        String want = String.valueOf(appStamp());
        if (out.isFile() && out.length() > 0 && marker.isFile() && want.equals(readSmall(marker))) {
            if (out.canWrite()) out.setReadOnly();
            return out;
        }

        if (out.exists()) out.delete();
        File tmp = new File(out.getPath() + ".tmp");
        if (tmp.exists()) tmp.delete();

        InputStream in = ctx.getAssets().open(name);
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            copyStream(in, fos);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            fos.close();
        }
        if (!tmp.renameTo(out)) throw new IOException("Cannot move " + tmp + " to " + out);
        out.setReadOnly();
        writeSmall(marker, want);
        say("Extracted " + name + " (" + out.length() + " bytes)");
        return out;
    }

    private File prepareEcjRes() throws Exception {
        File zip = extractAsset("ecj_res.zip");
        File dir = new File(ctx.getFilesDir(), "tools/ecj_res");
        File marker = new File(dir, ".stamp");
        String want = String.valueOf(appStamp());
        if (dir.isDirectory() && marker.isFile() && want.equals(readSmall(marker))) return dir;
        deleteRecursive(dir);
        dir.mkdirs();
        unzipTo(zip, dir);
        writeSmall(marker, want);
        return dir;
    }

    private DexClassLoader dexLoader(File dexJar) {
        String key = "dex:" + dexJar.getAbsolutePath() + ":" + dexJar.lastModified()
            + ":" + dexJar.length();
        synchronized (LOADER_LOCK) {
            ClassLoader l = LOADERS.get(key);
            if (l == null) {
                File opt = new File(ctx.getCacheDir(), "odex");
                opt.mkdirs();
                l = new DexClassLoader(dexJar.getAbsolutePath(), opt.getAbsolutePath(),
                                       null, ctx.getClassLoader());
                LOADERS.put(key, l);
            }
            return (DexClassLoader) l;
        }
    }

    private ClassLoader ecjLoader(File ecjFull, File resDir) {
        String key = "ecj:" + ecjFull.getAbsolutePath() + ":" + ecjFull.lastModified()
            + ":" + ecjFull.length();
        synchronized (LOADER_LOCK) {
            ClassLoader l = LOADERS.get(key);
            if (l == null) {
                File opt = new File(ctx.getCacheDir(), "odex");
                opt.mkdirs();
                l = new ResourceAwareLoader(ecjFull.getAbsolutePath(), opt,
                                            ctx.getClassLoader(), resDir);
                LOADERS.put(key, l);
            }
            return l;
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

    // ------------------------------------------------------------------------------------
    // aapt2
    // ------------------------------------------------------------------------------------

    private String aapt2Path() {
        return ctx.getApplicationInfo().nativeLibraryDir + "/libaapt2.so";
    }

    private void checkAapt2Once() {
        if (sAaptChecked) return;
        sAaptChecked = true;
        try {
            File f = new File(aapt2Path());
            if (!f.exists()) {
                say("WARNING: " + f + " not found. Put an aapt2 binary for this device's ABI in "
                    + "jniLibs/<abi>/libaapt2.so");
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(f.getAbsolutePath(), "version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            copyStream(p.getInputStream(), bo);
            p.waitFor();
            say("aapt2 version: " + bo.toString().trim());
        } catch (Throwable t) {
            say("aapt2 version check failed: " + t);
        }
    }

    private void runAapt2(String... args) throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(aapt2Path());
        for (String a : args) cmd.add(a);

        StringBuilder cmdLine = new StringBuilder("aapt2 ");
        for (int i = 0; i < args.length && i < 3; i++) cmdLine.append(args[i]).append(' ');
        if (args.length > 3) cmdLine.append("... (").append(args.length).append(" args)");
        say("$ " + cmdLine.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        copyStream(p.getInputStream(), baos);
        int code = p.waitFor();

        if (code != 0) {
            String out = baos.toString();
            say("aapt2 failed (" + code + "):\n" + out);
            throw new RuntimeException("aapt2 failed (" + code + "):\n" + out);
        }
    }

    // ------------------------------------------------------------------------------------
    // AARs (cached by content hash)
    // ------------------------------------------------------------------------------------

    private static class AarInfo {
        String pkg;
        File manifest;
        File assets;
        File jni;
        List<File> jars = new ArrayList<File>();
        List<File> flats = new ArrayList<File>();
    }

    private AarInfo prepareAar(File aar, String digest) throws Exception {
        File base = new File(ctx.getFilesDir(), "aar_cache/" + digest);
        File extract = new File(base, "x");
        File flatDir = new File(base, "flat");
        File done = new File(base, ".done");

        if (!done.exists()) {
            say("Processing AAR: " + aar.getName());
            deleteRecursive(base);
            extract.mkdirs();
            flatDir.mkdirs();
            unzipTo(aar, extract);
            File res = new File(extract, "res");
            if (res.isDirectory()) {
                runAapt2("compile", "--dir", res.getAbsolutePath(), "-o", flatDir.getAbsolutePath());
            }
            writeSmall(done, "1");
        } else {
            say("AAR cached: " + aar.getName());
            base.setLastModified(System.currentTimeMillis());
        }

        AarInfo info = new AarInfo();
        File classes = new File(extract, "classes.jar");
        if (classes.isFile()) info.jars.add(classes);
        File libs = new File(extract, "libs");
        File[] libJars = libs.listFiles();
        if (libJars != null) {
            Arrays.sort(libJars);
            for (File lj : libJars) {
                if (lj.isFile() && lj.getName().endsWith(".jar")) info.jars.add(lj);
            }
        }
        File[] flats = flatDir.listFiles();
        if (flats != null) {
            Arrays.sort(flats);
            for (File f : flats) if (f.getName().endsWith(".flat")) info.flats.add(f);
        }
        File assets = new File(extract, "assets");
        if (assets.isDirectory()) info.assets = assets;
        File jni = new File(extract, "jni");
        if (jni.isDirectory()) info.jni = jni;
        File mf = new File(extract, "AndroidManifest.xml");
        if (mf.isFile()) {
            info.manifest = mf;
            info.pkg = readPackage(mf);
        }
        return info;
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
                    try { copyStream(in, fos); }
                    finally { in.close(); fos.close(); }
                }
                out.add(dest);
            }
        } catch (Exception e) {
            say("Bundled AAR load failed: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------
    // Kotlin
    // ------------------------------------------------------------------------------------

    private void compileKotlin(List<File> sourceRoots, File genDir, File classesDir,
                               File androidJar, File ktStdlib, List<File> jarDeps) throws Exception {
        String mode = ctx.getSharedPreferences("kotlin", Context.MODE_PRIVATE)
                         .getString("mode", "auto");
        String localErr = "";

        if (!"remote".equals(mode)) {
            try {
                File kotlincJar = extractAsset("kotlin-compiler-embeddable-1.9.24.jar");
                if (!containsEntry(kotlincJar, "classes.dex")) {
                    throw new IOException("kotlin compiler asset is a plain JVM jar (no classes.dex). "
                        + "Convert it with d8 first, or use remote mode.");
                }
                say("Kotlin: local...");
                KotlinCompiler kc = new KotlinCompiler(ctx, new KotlinCompiler.Progress() {
                    @Override public void onProgress(String m) { say(m); }
                });
                kc.compile(kotlincJar, ktStdlib, androidJar, sourceRoots, genDir, classesDir, jarDeps);
                return;
            } catch (Throwable t) {
                localErr = causeChain(t);
                say("Kotlin local failed: " + localErr);
                if ("local".equals(mode)) {
                    throw new RuntimeException("Kotlin local compile failed:\n" + localErr);
                }
            }
        }

        try {
            say("Kotlin: remote...");
            RemoteKotlinCompiler rkc = new RemoteKotlinCompiler(ctx,
                new RemoteKotlinCompiler.Progress() {
                    @Override public void onProgress(String m) { say(m); }
                });
            rkc.compile(sourceRoots, genDir, classesDir);
        } catch (Throwable t) {
            throw new RuntimeException("Kotlin compile failed.\n"
                + (localErr.length() > 0 ? "local: " + localErr + "\n" : "")
                + "remote: " + causeChain(t));
        }
    }

    private static boolean hasKotlinStdlib(List<File> jars) {
        Pattern p = Pattern.compile("kotlin-stdlib-\\d.*\\.jar");
        for (File f : jars) if (p.matcher(f.getName()).matches()) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------
    // ECJ
    // ------------------------------------------------------------------------------------

    private void compileJava(File androidJar, File lambdaStubs, File ecjFull, File ecjResDir,
                             List<File> sourceRoots, File genDir, File classesDir,
                             List<File> libJars, boolean hasKotlin) throws Exception {
        List<File> javaFiles = new ArrayList<File>();
        for (File src : sourceRoots) findJavaFiles(src, javaFiles);
        findJavaFiles(genDir, javaFiles);

        if (javaFiles.isEmpty()) {
            say("No .java files to compile");
            return;
        }

        ClassLoader loader = ecjLoader(ecjFull, ecjResDir);
        Class<?> mainClass = loader.loadClass("org.eclipse.jdt.internal.compiler.batch.Main");

        StringBuilder cp = new StringBuilder();
        cp.append(androidJar.getAbsolutePath());
        // lambda stubs are for compilation only - they must never be dexed into the app
        if (lambdaStubs != null && lambdaStubs.exists()) {
            cp.append(File.pathSeparator).append(lambdaStubs.getAbsolutePath());
        }
        for (File j : libJars) {
            if (j != null && j.exists()) cp.append(File.pathSeparator).append(j.getAbsolutePath());
        }
        if (hasKotlin) cp.append(File.pathSeparator).append(classesDir.getAbsolutePath());

        List<String> args = new ArrayList<String>();
        args.add("-1.8");
        args.add("-proc:none");
        args.add("-nowarn");
        args.add("-encoding"); args.add("UTF-8");
        args.add("-classpath"); args.add(cp.toString());
        args.add("-d"); args.add(classesDir.getAbsolutePath());
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

    // ------------------------------------------------------------------------------------
    // D8: libraries pre-dexed once, app classes dexed with desugaring, then merged
    // ------------------------------------------------------------------------------------

    private List<File> compileDex(File androidJar, File d8Zip, File classesDir, File workDir,
                                  File outputDir, List<File> libJars, int minSdk) throws Exception {
        List<File> classFiles = new ArrayList<File>();
        findClassFiles(classesDir, classFiles);
        if (classFiles.isEmpty()) throw new RuntimeException("No .class files to dex");

        // 1) pre-dex every library jar once (cached by content hash)
        List<File> libDex = new ArrayList<File>();
        int cached = 0, fresh = 0;
        for (File jar : libJars) {
            if (jar == null || !jar.isFile() || !jar.getName().endsWith(".jar")) continue;
            List<File> dex = predex(jar, androidJar, d8Zip, minSdk);
            libDex.addAll(dex);
            if (lastPredexHit) cached++; else fresh++;
        }
        say("Library dex: " + cached + " cached, " + fresh + " new");

        // 2) app classes -> jar (stored, no compression: D8 reads it right back)
        File appJar = new File(workDir, "app-classes.jar");
        writeClassesJar(classesDir, classFiles, appJar);

        // 3) dex the app classes. Libraries are --classpath so lambdas / default methods desugar
        File appDex = new File(workDir, "app_dex");
        appDex.mkdirs();
        List<String> a = new ArrayList<String>();
        a.add("--intermediate");
        a.add("--output"); a.add(appDex.getAbsolutePath());
        a.add("--min-api"); a.add(String.valueOf(minSdk));
        a.add("--lib"); a.add(androidJar.getAbsolutePath());
        for (File j : libJars) {
            if (j != null && j.isFile() && j.getName().endsWith(".jar")) {
                a.add("--classpath"); a.add(j.getAbsolutePath());
            }
        }
        a.add(appJar.getAbsolutePath());
        runD8(d8Zip, a);

        // 4) merge everything into the final classes*.dex
        List<String> m = new ArrayList<String>();
        m.add("--output"); m.add(outputDir.getAbsolutePath());
        m.add("--min-api"); m.add(String.valueOf(minSdk));
        for (File d : libDex) m.add(d.getAbsolutePath());
        File[] appDexFiles = appDex.listFiles();
        if (appDexFiles != null) {
            Arrays.sort(appDexFiles);
            for (File d : appDexFiles) {
                if (d.getName().endsWith(".dex")) m.add(d.getAbsolutePath());
            }
        }
        runD8(d8Zip, m);

        List<File> out = new ArrayList<File>();
        File[] kids = outputDir.listFiles();
        if (kids != null) {
            Arrays.sort(kids);
            for (File f : kids) {
                if (f.getName().endsWith(".dex") && f.length() > 0) out.add(f);
            }
        }
        if (out.isEmpty()) throw new RuntimeException("D8 produced no .dex files");
        return out;
    }

    private String predexKey(File jar, File d8Zip, int minSdk) throws Exception {
        return sha1(jar) + "_" + minSdk + "_" + d8Zip.length();
    }

    private List<File> predex(File jar, File androidJar, File d8Zip, int minSdk) throws Exception {
        File dir = new File(ctx.getFilesDir(), "predex/" + predexKey(jar, d8Zip, minSdk));
        File ok = new File(dir, ".ok");
        lastPredexHit = ok.exists();
        if (!lastPredexHit) {
            say("Pre-dexing " + jar.getName());
            deleteRecursive(dir);
            dir.mkdirs();
            List<String> a = new ArrayList<String>();
            a.add("--intermediate");
            a.add("--output"); a.add(dir.getAbsolutePath());
            a.add("--min-api"); a.add(String.valueOf(minSdk));
            a.add("--lib"); a.add(androidJar.getAbsolutePath());
            a.add(jar.getAbsolutePath());
            runD8(d8Zip, a);
            writeSmall(ok, "1");
        } else {
            dir.setLastModified(System.currentTimeMillis());
        }
        List<File> out = new ArrayList<File>();
        File[] kids = dir.listFiles();
        if (kids != null) {
            Arrays.sort(kids);
            for (File f : kids) if (f.getName().endsWith(".dex")) out.add(f);
        }
        return out;
    }

    /** Runs D8 through its Java API (a failure throws instead of calling System.exit). */
    private void runD8(File d8Zip, List<String> args) throws Exception {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        System.setOut(new PrintStream(so, true));
        System.setErr(new PrintStream(se, true));
        try {
            DexClassLoader loader = dexLoader(d8Zip);
            String[] a = args.toArray(new String[0]);
            try {
                Class<?> cmdCls = loader.loadClass("com.android.tools.r8.D8Command");
                Class<?> originCls = loader.loadClass("com.android.tools.r8.origin.Origin");
                Class<?> d8Cls = loader.loadClass("com.android.tools.r8.D8");
                Object origin = originCls.getMethod("unknown").invoke(null);
                Method parse = cmdCls.getMethod("parse", String[].class, originCls);
                Object builder = parse.invoke(null, a, origin);
                Object cmd = parse.getReturnType().getMethod("build").invoke(builder);
                d8Cls.getMethod("run", cmdCls).invoke(null, cmd);
            } catch (NoSuchMethodException nsme) {
                say("D8 API differs in this d8.zip, falling back to D8.main()");
                Method main = loader.loadClass("com.android.tools.r8.D8")
                                    .getMethod("main", String[].class);
                main.invoke(null, (Object) a);
            }
        } catch (InvocationTargetException ite) {
            String msg = "D8 error: " + causeChain(ite)
                + "\n--- stdout ---\n" + so.toString()
                + "\n--- stderr ---\n" + se.toString();
            say(msg);
            throw new RuntimeException(msg);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        String warn = se.toString().trim();
        if (warn.length() > 0) {
            say("D8: " + (warn.length() > 1500 ? warn.substring(0, 1500) + "..." : warn));
        }
    }

    private void writeClassesJar(File classesDir, List<File> classFiles, File outJar) throws Exception {
        ZipOutputStream jarOut = new ZipOutputStream(
            new BufferedOutputStream(new FileOutputStream(outJar), 65536));
        jarOut.setLevel(Deflater.NO_COMPRESSION);
        byte[] copyBuf = new byte[65536];
        String root = classesDir.getAbsolutePath();
        try {
            for (File cf : classFiles) {
                String rel = cf.getAbsolutePath().substring(root.length() + 1).replace('\\', '/');
                jarOut.putNextEntry(new ZipEntry(rel));
                FileInputStream fin = new FileInputStream(cf);
                try {
                    int n;
                    while ((n = fin.read(copyBuf)) > 0) jarOut.write(copyBuf, 0, n);
                } finally {
                    fin.close();
                }
                jarOut.closeEntry();
            }
        } finally {
            jarOut.close();
        }
    }

    // ------------------------------------------------------------------------------------
    // APK assembly (keeps resources.arsc STORED + 4-byte aligned, adds dex and native libs)
    // ------------------------------------------------------------------------------------

    private static class CountingOutputStream extends FilterOutputStream {
        long count = 0;
        CountingOutputStream(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    private void assembleApk(File inApk, List<File> dexFiles, List<File> jniRoots,
                             List<AarInfo> aars, File outApk) throws Exception {
        if (dexFiles.isEmpty()) throw new RuntimeException("No dex output");
        if (outApk.exists()) outApk.delete();

        CountingOutputStream cos = new CountingOutputStream(
            new BufferedOutputStream(new FileOutputStream(outApk), 65536));
        ZipOutputStream zos = new ZipOutputStream(cos);
        zos.setLevel(Deflater.BEST_SPEED);
        Set<String> names = new HashSet<String>();

        try {
            ZipFile zf = new ZipFile(inApk);
            try {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String name = e.getName();
                    if (e.isDirectory()) continue;
                    if (name.startsWith("classes") && name.endsWith(".dex")) continue;
                    InputStream in = zf.getInputStream(e);
                    try {
                        if (e.getMethod() == ZipEntry.STORED) {
                            addStored(zos, cos, names, name, in, e.getSize(), e.getCrc(), 4);
                        } else {
                            addDeflated(zos, names, name, in);
                        }
                    } finally {
                        in.close();
                    }
                }
            } finally {
                zf.close();
            }

            for (File dex : dexFiles) {
                FileInputStream fin = new FileInputStream(dex);
                try { addDeflated(zos, names, dex.getName(), fin); }
                finally { fin.close(); }
            }

            List<File> roots = new ArrayList<File>(jniRoots);
            for (AarInfo a : aars) if (a.jni != null) roots.add(a.jni);
            for (File root : roots) {
                File[] abis = root.listFiles();
                if (abis == null) continue;
                Arrays.sort(abis);
                for (File abi : abis) {
                    if (!abi.isDirectory()) continue;
                    File[] sos = abi.listFiles();
                    if (sos == null) continue;
                    Arrays.sort(sos);
                    for (File so : sos) {
                        if (!so.isFile() || !so.getName().endsWith(".so")) continue;
                        String name = "lib/" + abi.getName() + "/" + so.getName();
                        if (names.contains(name)) continue;
                        long crc = crcOf(so);
                        FileInputStream fin = new FileInputStream(so);
                        try {
                            // 16 KB alignment is valid for 4 KB and 16 KB page devices
                            addStored(zos, cos, names, name, fin, so.length(), crc, 16384);
                        } finally {
                            fin.close();
                        }
                    }
                }
            }
        } finally {
            zos.close();
        }
    }

    private void addDeflated(ZipOutputStream zos, Set<String> names, String name,
                             InputStream in) throws IOException {
        if (!names.add(name)) return;
        zos.putNextEntry(new ZipEntry(name));
        copyStream(in, zos);
        zos.closeEntry();
    }

    private void addStored(ZipOutputStream zos, CountingOutputStream cos, Set<String> names,
                           String name, InputStream in, long size, long crc,
                           int align) throws IOException {
        if (!names.add(name)) return;
        ZipEntry ze = new ZipEntry(name);
        ze.setMethod(ZipEntry.STORED);
        ze.setSize(size);
        ze.setCompressedSize(size);
        ze.setCrc(crc);
        // local header = 30 bytes + name + extra; pad the extra field so data starts aligned
        int nameLen = name.getBytes("UTF-8").length;
        long dataStart = cos.count + 30 + nameLen;
        int pad = (int) ((align - (dataStart % align)) % align);
        if (pad > 0) ze.setExtra(new byte[pad]);
        zos.putNextEntry(ze);
        copyStream(in, zos);
        zos.closeEntry();
    }

    private static long crcOf(File f) throws IOException {
        CRC32 crc = new CRC32();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        } finally {
            in.close();
        }
        return crc.getValue();
    }

    // ------------------------------------------------------------------------------------
    // Signing (apksig API; a failure throws instead of calling System.exit)
    // ------------------------------------------------------------------------------------

    private void signApk(File apksigner, File pk8, File pem, File inApk, File outApk,
                         int minSdk) throws Exception {
        DexClassLoader loader = dexLoader(apksigner);
        try {
            PrivateKey key = loadPrivateKey(readBytes(pk8));
            X509Certificate cert;
            FileInputStream cin = new FileInputStream(pem);
            try {
                cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(cin);
            } finally {
                cin.close();
            }
            List<X509Certificate> certs = new ArrayList<X509Certificate>();
            certs.add(cert);

            Class<?> scb = loader.loadClass("com.android.apksig.ApkSigner$SignerConfig$Builder");
            Object cfgBuilder = scb.getConstructor(String.class, PrivateKey.class, List.class)
                .newInstance("CERT", key, certs);
            Object signerConfig = scb.getMethod("build").invoke(cfgBuilder);
            List<Object> signers = new ArrayList<Object>();
            signers.add(signerConfig);

            Class<?> bc = loader.loadClass("com.android.apksig.ApkSigner$Builder");
            Object b = bc.getConstructor(List.class).newInstance(signers);
            bc.getMethod("setInputApk", File.class).invoke(b, inApk);
            bc.getMethod("setOutputApk", File.class).invoke(b, outApk);
            bc.getMethod("setMinSdkVersion", int.class).invoke(b, minSdk);
            bc.getMethod("setV1SigningEnabled", boolean.class).invoke(b, minSdk < 24);
            bc.getMethod("setV2SigningEnabled", boolean.class).invoke(b, true);
            bc.getMethod("setV3SigningEnabled", boolean.class).invoke(b, true);
            Object signer = bc.getMethod("build").invoke(b);
            signer.getClass().getMethod("sign").invoke(signer);
        } catch (InvocationTargetException ite) {
            String msg = "Sign error: " + causeChain(ite);
            say(msg);
            throw new RuntimeException(msg);
        } catch (NoSuchMethodException nsme) {
            say("apksig API differs, falling back to ApkSignerTool");
            signWithTool(loader, pk8, pem, inApk, outApk);
        } catch (ClassNotFoundException cnfe) {
            say("apksig classes not found, falling back to ApkSignerTool");
            signWithTool(loader, pk8, pem, inApk, outApk);
        }
        if (!outApk.exists() || outApk.length() == 0) {
            throw new RuntimeException("Signing produced no output");
        }
    }

    private void signWithTool(DexClassLoader loader, File pk8, File pem, File inApk,
                              File outApk) throws Exception {
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
    }

    private static PrivateKey loadPrivateKey(byte[] pkcs8) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
        String[] algs = {"RSA", "EC", "DSA"};
        Exception last = null;
        for (String alg : algs) {
            try { return KeyFactory.getInstance(alg).generatePrivate(spec); }
            catch (Exception e) { last = e; }
        }
        throw new RuntimeException("Unsupported private key format: " + last);
    }

    // ------------------------------------------------------------------------------------
    // Manifest patch + simple AAR manifest merge (DOM based)
    // ------------------------------------------------------------------------------------

    private static final String[] MERGE_ROOT_TAGS = {
        "uses-permission", "uses-permission-sdk-23", "uses-feature", "permission"
    };
    private static final String[] MERGE_APP_COMPONENT_TAGS = {
        "activity", "activity-alias", "service", "receiver", "provider"
    };
    private static final String[] MERGE_APP_PLAIN_TAGS = {
        "meta-data", "uses-library", "uses-native-library"
    };

    private void patchManifest(File in, File out, int minSdk, int targetSdk,
                               List<AarInfo> aars) throws Exception {
        Document doc = parseXml(in);
        Element root = doc.getDocumentElement();
        String pkg = root.getAttribute("package");

        // drop any existing <uses-sdk> and android:applicationId, then insert ours first
        List<Node> toRemove = new ArrayList<Node>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && ((Element) n).getTagName().equals("uses-sdk")) toRemove.add(n);
        }
        for (Node n : toRemove) root.removeChild(n);
        root.removeAttributeNS(ANDROID_NS, "applicationId");

        Element sdk = doc.createElement("uses-sdk");
        sdk.setAttributeNS(ANDROID_NS, "android:minSdkVersion", String.valueOf(minSdk));
        sdk.setAttributeNS(ANDROID_NS, "android:targetSdkVersion", String.valueOf(targetSdk));
        root.insertBefore(sdk, root.getFirstChild());

        for (AarInfo a : aars) {
            if (a.manifest == null) continue;
            try {
                mergeAarManifest(doc, root, pkg, a.manifest);
            } catch (Exception e) {
                say("Manifest merge skipped for " + a.pkg + ": " + e.getMessage());
            }
        }

        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        FileOutputStream fos = new FileOutputStream(out);
        try {
            t.transform(new DOMSource(doc), new StreamResult(fos));
        } finally {
            fos.close();
        }
    }

    private void mergeAarManifest(Document doc, Element appRoot, String appPkg,
                                  File aarManifest) throws Exception {
        Document ad = parseXml(aarManifest);
        Element ar = ad.getDocumentElement();
        String aarPkg = ar.getAttribute("package");

        for (Node n = ar.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element c = (Element) n;
            String tag = c.getTagName();

            if (contains(MERGE_ROOT_TAGS, tag)) {
                String nm = replaceVars(attr(c, "name"), appPkg);
                if (nm.length() == 0 || hasChildNamed(appRoot, tag, nm)) continue;
                Element imp = (Element) doc.importNode(c, true);
                replaceVarsDeep(imp, appPkg);
                appRoot.appendChild(imp);
            } else if (tag.equals("application")) {
                Element appEl = getOrCreateApplication(doc, appRoot);
                for (Node d = c.getFirstChild(); d != null; d = d.getNextSibling()) {
                    if (!(d instanceof Element)) continue;
                    Element de = (Element) d;
                    String dTag = de.getTagName();
                    boolean component = contains(MERGE_APP_COMPONENT_TAGS, dTag);
                    boolean plain = contains(MERGE_APP_PLAIN_TAGS, dTag);
                    if (!component && !plain) continue;

                    String raw = attr(de, "name");
                    String full = component ? expandName(raw, aarPkg) : raw;
                    if (full.length() > 0 && hasChildNamed(appEl, dTag, full)) continue;

                    Element imp = (Element) doc.importNode(de, true);
                    if (component) {
                        if (full.length() > 0) imp.setAttributeNS(ANDROID_NS, "android:name", full);
                        fixRef(imp, "targetActivity", aarPkg);
                        fixRef(imp, "parentActivityName", aarPkg);
                    }
                    replaceVarsDeep(imp, appPkg);
                    appEl.appendChild(imp);
                }
            }
        }
    }

    private static void fixRef(Element e, String attrName, String pkg) {
        String v = attr(e, attrName);
        if (v.length() > 0) e.setAttributeNS(ANDROID_NS, "android:" + attrName, expandName(v, pkg));
    }

    private static Element getOrCreateApplication(Document doc, Element root) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && ((Element) n).getTagName().equals("application")) {
                return (Element) n;
            }
        }
        Element app = doc.createElement("application");
        root.appendChild(app);
        return app;
    }

    private static boolean hasChildNamed(Element parent, String tag, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element c = (Element) n;
            if (c.getTagName().equals(tag) && name.equals(attr(c, "name"))) return true;
        }
        return false;
    }

    private static String attr(Element e, String name) {
        String v = e.getAttributeNS(ANDROID_NS, name);
        return v == null ? "" : v;
    }

    private static String expandName(String name, String pkg) {
        if (name == null || name.length() == 0) return "";
        if (name.startsWith(".")) return pkg + name;
        if (name.indexOf('.') < 0) return pkg + "." + name;
        return name;
    }

    private static String replaceVars(String v, String appPkg) {
        if (v == null) return "";
        return v.replace("${applicationId}", appPkg).replace("${packageName}", appPkg);
    }

    private static void replaceVarsDeep(Element e, String appPkg) {
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String v = a.getNodeValue();
            if (v != null && v.indexOf("${") >= 0) a.setNodeValue(replaceVars(v, appPkg));
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) replaceVarsDeep((Element) n, appPkg);
        }
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    private static Document parseXml(File f) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        FileInputStream in = new FileInputStream(f);
        try {
            return dbf.newDocumentBuilder().parse(in);
        } finally {
            in.close();
        }
    }

    private String readPackage(File manifest) {
        try {
            Matcher pm = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(readFile(manifest));
            if (pm.find()) return pm.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    // ------------------------------------------------------------------------------------
    // Project discovery helpers
    // ------------------------------------------------------------------------------------

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

    private void addSiblingDirs(File resDir, List<File> assetDirs, List<File> jniRoots) {
        File base = resDir.getParentFile();
        if (base == null) return;
        addIfDir(assetDirs, new File(base, "assets"));
        addIfDir(jniRoots, new File(base, "jniLibs"));
    }

    private static void addIfDir(List<File> list, File dir) {
        if (dir != null && dir.isDirectory() && !list.contains(dir)) list.add(dir);
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

    private void collectDeps(File libsDir, List<File> jarOut, List<File> aarOut) {
        if (libsDir == null || !libsDir.exists() || !libsDir.isDirectory()) return;
        File[] files = libsDir.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            if (!f.isFile()) continue;
            if (f.getName().endsWith(".jar")) jarOut.add(f);
            else if (f.getName().endsWith(".aar")) aarOut.add(f);
        }
    }

    private List<File> dedupeJars(List<File> jars) throws Exception {
        List<File> out = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        for (File j : jars) {
            if (j == null || !j.isFile()) continue;
            if (seen.add(sha1(j))) out.add(j);
        }
        return out;
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

    // ------------------------------------------------------------------------------------
    // Cache housekeeping
    // ------------------------------------------------------------------------------------

    private void trimCaches() {
        try {
            trimDir(new File(ctx.getFilesDir(), "predex"), 150);
            trimDir(new File(ctx.getFilesDir(), "aar_cache"), 60);
        } catch (Throwable ignored) {}
    }

    private static void trimDir(File dir, int keep) {
        File[] kids = dir.listFiles();
        if (kids == null || kids.length <= keep) return;
        Arrays.sort(kids, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                long x = a.lastModified(), y = b.lastModified();
                return x < y ? 1 : (x > y ? -1 : 0);     // newest first
            }
        });
        for (int i = keep; i < kids.length; i++) deleteRecursive(kids[i]);
    }

    // ------------------------------------------------------------------------------------
    // Generic helpers
    // ------------------------------------------------------------------------------------

    private void writeBuildLog() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "MyIDE");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "myide_build.log");
            FileOutputStream fos = new FileOutputStream(out);
            try { fos.write(fullLog.toString().getBytes("UTF-8")); }
            finally { fos.close(); }
        } catch (Throwable ignored) {}
    }

    private Result fail(StringBuilder log, String msg) {
        log.append(msg).append('\n');
        say(msg);
        writeBuildLog();
        return new Result(false, null, msg);
    }

    private void unzipTo(File zip, File destDir) throws Exception {
        String destCanon = destDir.getCanonicalPath() + File.separator;
        ZipInputStream zin = new ZipInputStream(new java.io.BufferedInputStream(
            new FileInputStream(zip), 65536));
        try {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                String canon = out.getCanonicalPath();
                if (!(canon + File.separator).startsWith(destCanon)) {
                    throw new SecurityException("Zip entry escapes target: " + e.getName());
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File p = out.getParentFile();
                    if (p != null) p.mkdirs();
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        int n;
                        while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                    } finally {
                        fos.close();
                    }
                }
                zin.closeEntry();
            }
        } finally {
            zin.close();
        }
    }

    private static boolean containsEntry(File zip, String entry) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            return zf.getEntry(entry) != null;
        } catch (Exception e) {
            return false;
        } finally {
            if (zf != null) try { zf.close(); } catch (IOException ignored) {}
        }
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

    private static String sha1(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static byte[] readBytes(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copyStream(in, out);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private String readFile(File f) throws Exception {
        return new String(readBytes(f), "UTF-8");
    }

    private static String readSmall(File f) {
        try { return new String(readBytes(f), "UTF-8").trim(); }
        catch (Exception e) { return ""; }
    }

    private static void writeSmall(File f, String s) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        try { fos.write(s.getBytes("UTF-8")); }
        finally { fos.close(); }
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
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
        // never follow symlinks out of the tree
        try {
            if (!f.getCanonicalPath().equals(f.getAbsoluteFile().getParentFile() == null
                    ? f.getCanonicalPath()
                    : new File(f.getAbsoluteFile().getParentFile().getCanonicalFile(),
                               f.getName()).getPath())) {
                f.delete();
                return;
            }
        } catch (IOException ignored) {}
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}