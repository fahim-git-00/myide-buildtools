package com.fahim.myide;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MavenResolver {

    private static final String MAVEN_BASE = "https://repo1.maven.org/maven2/";

    public interface Progress {
        void onProgress(String message);
        void onDone(boolean success, String message);
    }

    private final Context ctx;
    private final Progress progress;

    public MavenResolver(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    private void done(boolean ok, String s) {
        if (progress != null) progress.onDone(ok, s);
    }

    // =========================================================
    //  Public API
    // =========================================================

    public void resolve(File depsFile) {
        try {
            if (!depsFile.exists()) {
                done(true, "No dependencies file — nothing to do");
                return;
            }

            List<Coord> roots = parseDepsFile(depsFile);
            if (roots.isEmpty()) {
                done(true, "No dependencies to resolve");
                return;
            }

            say("Resolving " + roots.size() + " dependencies...");

            Set<String> visited = new HashSet<String>();
            Deque<Coord> queue = new ArrayDeque<Coord>(roots);
            List<Coord> resolved = new ArrayList<Coord>();

            while (!queue.isEmpty()) {
                Coord c = queue.poll();
                String key = c.group + ":" + c.artifact + ":" + c.version;
                if (visited.contains(key)) continue;
                visited.add(key);

                say("Fetching " + key);
                try {
                    resolveOne(c, resolved, queue);
                } catch (Exception e) {
                    say("Skip " + key + " (" + e.getMessage() + ")");
                }
            }

            // ---- Deduplicate by group:artifact, keep highest version ----
            Map<String, Coord> winners = new HashMap<String, Coord>();
            for (Coord c : resolved) {
                String key = c.group + ":" + c.artifact;
                Coord cur = winners.get(key);
                if (cur == null || compareVersions(c.version, cur.version) > 0) {
                    winners.put(key, c);
                }
            }

            List<Coord> finalList = new ArrayList<Coord>(winners.values());
            Collections.sort(finalList, new Comparator<Coord>() {
                    @Override public int compare(Coord a, Coord b) {
                        return a.toString().compareTo(b.toString());
                    }
                });

            StringBuilder sb = new StringBuilder();
            for (Coord c : finalList) {
                sb.append(c.group).append(':').append(c.artifact).append(':').append(c.version).append('\n');
            }
            writeFile(depsFile, sb.toString());

            done(true, "Resolved " + finalList.size() + " artifacts");
        } catch (Exception e) {
            done(false, "Resolve failed: " + e.getMessage());
        }
    }

    // =========================================================
    //  Core per-dep logic
    // =========================================================

    private void resolveOne(Coord c, List<Coord> out, Deque<Coord> queue) throws Exception {
        File pom = ensureInCache(c, "pom", c.artifact + "-" + c.version + ".pom");
        String pomText = readFile(pom);

        String packaging = extractTag(pomText, "packaging");
        if (packaging == null || packaging.isEmpty()) packaging = "jar";

        boolean hasBinary = !"pom".equals(packaging);

        if (hasBinary) {
            String ext = "aar".equals(packaging) ? "aar" : "jar";
            ensureInCache(c, ext, c.artifact + "-" + c.version + "." + ext);
            out.add(c);
        }

        List<Coord> children = parseDependencies(pomText);
        for (Coord child : children) {
            if (child.version != null && !child.version.isEmpty()
                && !child.version.contains("${")) {
                queue.add(child);
            }
        }
    }

    // =========================================================
    //  Version comparison
    // =========================================================

    private static int compareVersions(String a, String b) {
        if (a == null) return -1;
        if (b == null) return 1;
        String[] ap = a.split("[.\\-]");
        String[] bp = b.split("[.\\-]");
        int n = Math.max(ap.length, bp.length);
        for (int i = 0; i < n; i++) {
            String x = i < ap.length ? ap[i] : "0";
            String y = i < bp.length ? bp[i] : "0";
            int xi, yi;
            try { xi = Integer.parseInt(x); } catch (Exception e) { xi = 0; }
            try { yi = Integer.parseInt(y); } catch (Exception e) { yi = 0; }
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    // =========================================================
    //  Cache / download
    // =========================================================

    private File ensureInCache(Coord c, String ext, String fileName) throws Exception {
        File dir = new File(cacheRoot(),
            c.group.replace('.', '/') + "/" + c.artifact + "/" + c.version);
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, fileName);
        if (out.exists() && out.length() > 0) return out;

        String url = MAVEN_BASE + c.group.replace('.', '/') + "/"
            + c.artifact + "/" + c.version + "/" + fileName;

        say("Downloading " + fileName);
        download(url, out);
        return out;
    }

    private File cacheRoot() {
        File f = new File(ctx.getCacheDir(), "myide-m2");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    private void download(String urlStr, File out) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code + " for " + urlStr);
        }

        InputStream in = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        conn.disconnect();
    }

    // =========================================================
    //  POM parsing
    // =========================================================

    private static final Pattern P_DEPENDENCIES =
        Pattern.compile("<dependencies>(.*?)</dependencies>", Pattern.DOTALL);
    private static final Pattern P_DEP =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

    private List<Coord> parseDependencies(String pom) {
        List<Coord> result = new ArrayList<Coord>();

        String cleaned = pom.replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "");
        cleaned = cleaned.replaceAll("(?s)<build>.*?</build>", "");
        cleaned = cleaned.replaceAll("(?s)<profiles>.*?</profiles>", "");

        Matcher m = P_DEPENDENCIES.matcher(cleaned);
        while (m.find()) {
            String block = m.group(1);
            Matcher dm = P_DEP.matcher(block);
            while (dm.find()) {
                String dep = dm.group(1);
                Coord c = new Coord();
                c.group    = extractTag(dep, "groupId");
                c.artifact = extractTag(dep, "artifactId");
                c.version  = extractTag(dep, "version");

                String scope = extractTag(dep, "scope");
                String optional = extractTag(dep, "optional");
                if ("test".equals(scope) || "provided".equals(scope)) continue;
                if ("true".equals(optional)) continue;

                if (c.group != null && c.artifact != null) result.add(c);
            }
        }
        return result;
    }

    private String extractTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(xml);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    // =========================================================
    //  Deps file I/O
    // =========================================================

    public static List<Coord> parseDepsFile(File f) {
        List<Coord> out = new ArrayList<Coord>();
        try {
            String text = readFileStatic(f);
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(":");
                if (parts.length < 3) continue;
                Coord c = new Coord();
                c.group = parts[0].trim();
                c.artifact = parts[1].trim();
                c.version = parts[2].trim();
                out.add(c);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String readFileStatic(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private String readFile(File f) throws Exception {
        return readFileStatic(f);
    }

    private void writeFile(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(s.getBytes("UTF-8"));
        fos.close();
    }

    // =========================================================
    //  Coord
    // =========================================================

    public static class Coord {
        public String group;
        public String artifact;
        public String version;

        @Override public String toString() {
            return group + ":" + artifact + ":" + version;
        }
    }

    // =========================================================
    //  Copy cache files into a project build dir
    // =========================================================

    public static int copyResolvedToDir(Context ctx, File depsFile, File destDir) throws Exception {
        List<Coord> coords = parseDepsFile(depsFile);
        if (!destDir.exists()) destDir.mkdirs();

        File cacheRoot = new File(ctx.getCacheDir(), "myide-m2");
        int copied = 0;

        for (Coord c : coords) {
            File dir = new File(cacheRoot,
                c.group.replace('.', '/') + "/" + c.artifact + "/" + c.version);
            if (!dir.exists()) continue;
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (File f : kids) {
                if (!f.isFile()) continue;
                String n = f.getName();
                if (!(n.endsWith(".jar") || n.endsWith(".aar"))) continue;
                File dest = new File(destDir, f.getName());
                copyFileStatic(f, dest);
                copied++;
            }
        }
        return copied;
    }

    private static void copyFileStatic(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }
}