package com.fahim.myide;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

public class GradleBuilder {

    public interface Progress {
        void onProgress(String message);
    }

    private final Context ctx;
    private final Progress progress;

    public GradleBuilder(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    public static class Result {
        public boolean success;
        public File apk;
        public String log;
        public Result(boolean s, File a, String l) { success = s; apk = a; log = l; }
    }

    public Result build(File projectRoot, int minSdk, int targetSdk) {
        StringBuilder log = new StringBuilder();
        try {
            say("Gradle: preparing...");

            // 1) Ensure app/build.gradle exists with correct min/target
            File appDir = new File(projectRoot, "app");
            File appBuildGradle = new File(appDir, "build.gradle");
            if (!appBuildGradle.exists()) {
                return fail(log, "app/build.gradle not found — Gradle mode needs a real Gradle project");
            }

            // 2) Write local.properties (points to Termux SDK)
            File home = new File(System.getenv("HOME") == null
                ? "/data/data/com.termux/files/home"
                : System.getenv("HOME"));
            File sdk = new File(home, "android-sdk");

            File localProps = new File(projectRoot, "local.properties");
            writeFile(localProps,
                "sdk.dir=" + sdk.getAbsolutePath() + "\n");

            // 3) Copy android.jar into the project so Gradle's compileSdk works offline
            //    Not strictly required if SDK is complete, but harmless.

            // 4) Prepare log paths
            File outDir = new File(Environment.getExternalStorageDirectory(), "MyIDE");
            if (!outDir.exists()) outDir.mkdirs();
            File logFile = new File(outDir, "gradle-build.log");
            File outApk = new File(outDir, "app-debug.apk");

            // 5) Assemble command
            String gradleCmd = findGradle();
            if (gradleCmd == null) {
                return fail(log,
                    "Gradle not found in PATH.\n" +
                    "Open Termux and run:\n" +
                    "  pkg install gradle\n" +
                    "Then re-run Build in MyIDE.");
            }

            say("Gradle: running assembleDebug (this may take a few minutes)...");

            ProcessBuilder pb = new ProcessBuilder(
                gradleCmd, "assembleDebug", "--no-daemon", "--console=plain"
            );
            pb.directory(projectRoot);
            pb.redirectErrorStream(true);

            // Add SDK tools to PATH
            pb.environment().put("ANDROID_HOME", sdk.getAbsolutePath());
            pb.environment().put("ANDROID_SDK_ROOT", sdk.getAbsolutePath());
            String path = pb.environment().get("PATH");
            if (path == null) path = "";
            path = sdk.getAbsolutePath() + "/cmdline-tools/latest/bin:"
                 + sdk.getAbsolutePath() + "/platform-tools:"
                 + path;
            pb.environment().put("PATH", path);

            Process p = pb.start();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream is = p.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = r.readLine()) != null) {
                buf.write((line + "\n").getBytes("UTF-8"));
                say("Gradle: " + line);
            }
            int code = p.waitFor();

            // Save full log
            writeFile(logFile, buf.toString("UTF-8"));

            if (code != 0) {
                log.append("Gradle exited with ").append(code).append("\n");
                log.append(buf.toString("UTF-8"));
                return new Result(false, null, log.toString());
            }

            // 6) Locate produced APK
            File apkDir = new File(projectRoot, "app/build/outputs/apk/debug");
            File[] apks = apkDir.listFiles();
            File produced = null;
            if (apks != null) {
                for (File f : apks) {
                    if (f.getName().endsWith(".apk")) { produced = f; break; }
                }
            }
            if (produced == null) {
                return fail(log, "Gradle finished but no APK found in " + apkDir);
            }

            // 7) Copy to /sdcard/MyIDE/
            copyFile(produced, outApk);

            say("Gradle: done");
            return new Result(true, outApk, log.toString());

        } catch (Throwable t) {
            log.append("ERROR: ").append(t.getClass().getSimpleName())
               .append(": ").append(t.getMessage()).append('\n');
            return new Result(false, null, log.toString());
        }
    }

    private String findGradle() {
        String[] candidates = {
            "/data/data/com.termux/files/usr/bin/gradle",
            "/data/data/com.termux/files/usr/opt/openjdk/bin/gradle",
            "/system/bin/gradle"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists() && f.canExecute()) return c;
        }
        // Try PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                File f = new File(dir, "gradle");
                if (f.exists() && f.canExecute()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    private Result fail(StringBuilder log, String msg) {
        log.append(msg).append('\n');
        return new Result(false, null, log.toString());
    }

    private void writeFile(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(s.getBytes("UTF-8"));
        fos.close();
    }

    private void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }
}