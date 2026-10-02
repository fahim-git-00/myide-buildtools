package com.fahim.myide;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class LibrariesDialog {

    private final Context ctx;
    private final File projectRoot;
    private final Runnable onChanged;

    public LibrariesDialog(Context ctx, File projectRoot, Runnable onChanged) {
        this.ctx = ctx;
        this.projectRoot = projectRoot;
        this.onChanged = onChanged;
    }

    private File depsFile() {
        File dir = new File(projectRoot, ".myide");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "deps.txt");
    }

    private int dp(int v) {
        return (int)(v * ctx.getResources().getDisplayMetrics().density);
    }

    public void show() {
        final File df = depsFile();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        // header
        TextView header = new TextView(ctx);
        header.setText("Maven Dependencies");
        header.setTextColor(0xFFDCDCAA);
        header.setTextSize(14f);
        root.addView(header);

        TextView sub = new TextView(ctx);
        sub.setText("Format: group:artifact:version\nExample: com.squareup.okhttp3:okhttp:4.12.0");
        sub.setTextColor(0xFF858585);
        sub.setTextSize(11f);
        sub.setPadding(0, dp(4), 0, dp(8));
        root.addView(sub);

        // deps list (scrollable)
        final ScrollView scroll = new ScrollView(ctx);
        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

        // add row
        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);

        final EditText input = new EditText(ctx);
        input.setHint("group:artifact:version");
        input.setHintTextColor(0xFF666666);
        input.setTextColor(0xFFD4D4D4);
        input.setSingleLine(true);
        addRow.addView(input, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button addBtn = new Button(ctx);
        addBtn.setText("Add");
        addBtn.setAllCaps(false);
        addBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String s = input.getText().toString().trim();
                    if (!isValidCoord(s)) {
                        toast("Invalid: use group:artifact:version");
                        return;
                    }
                    appendLine(df, s);
                    input.setText("");
                    reload(list);
                }
            });
        addRow.addView(addBtn);
        root.addView(addRow);

        // resolve button
        Button resolveBtn = new Button(ctx);
        resolveBtn.setText("Resolve All");
        resolveBtn.setAllCaps(false);
        resolveBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    resolveNow(df);
                }
            });
        root.addView(resolveBtn);

        // initial load
        reload(list);

        new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setView(root)
            .setPositiveButton("Close", null)
            .show();
    }

    private void reload(LinearLayout list) {
        list.removeAllViews();
        final File df = depsFile();
        if (!df.exists()) {
            TextView empty = new TextView(ctx);
            empty.setText("(no dependencies)");
            empty.setTextColor(0xFF666666);
            empty.setPadding(0, dp(4), 0, dp(4));
            list.addView(empty);
            return;
        }

        List<MavenResolver.Coord> coords = MavenResolver.parseDepsFile(df);
        if (coords.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("(no dependencies)");
            empty.setTextColor(0xFF666666);
            empty.setPadding(0, dp(4), 0, dp(4));
            list.addView(empty);
            return;
        }

        for (final MavenResolver.Coord c : coords) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(2), 0, dp(2));

            TextView tv = new TextView(ctx);
            tv.setText(c.toString());
            tv.setTextColor(0xFFD4D4D4);
            tv.setTextSize(12f);
            row.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button rm = new Button(ctx);
            rm.setText("\u2716");
            rm.setAllCaps(false);
            rm.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        removeLine(df, c.toString());
                        reload((LinearLayout) v.getParent().getParent());
                    }
                });
            row.addView(rm);

            list.addView(row);
        }
    }

    private void resolveNow(File df) {
        final AlertDialog prog = new AlertDialog.Builder(ctx,
            android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Resolving...")
            .setMessage("Starting")
            .setCancelable(false)
            .create();
        prog.show();

        new Thread(new Runnable() {
                @Override public void run() {
                    MavenResolver r = new MavenResolver(ctx, new MavenResolver.Progress() {
                            @Override public void onProgress(final String msg) {
                                ((android.app.Activity) ctx).runOnUiThread(new Runnable() {
                                        @Override public void run() { prog.setMessage(msg); }
                                    });
                            }
                            @Override public void onDone(final boolean ok, final String msg) {
                                ((android.app.Activity) ctx).runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            prog.dismiss();
                                            toast(msg);
                                            if (onChanged != null) onChanged.run();
                                        }
                                    });
                            }
                        });
                    r.resolve(df);
                }
            }).start();
    }

    // =========================================================
    //  Small helpers
    // =========================================================

    private boolean isValidCoord(String s) {
        if (s == null) return false;
        String[] p = s.split(":");
        return p.length == 3 && p[0].length() > 0 && p[1].length() > 0 && p[2].length() > 0;
    }

    private void appendLine(File f, String line) {
        try {
            String existing = f.exists() ? readFile(f) : "";
            if (existing.contains(line + "\n") || existing.equals(line)) return;
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((line + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            toast("Add failed: " + e.getMessage());
        }
    }

    private void removeLine(File f, String line) {
        try {
            String existing = f.exists() ? readFile(f) : "";
            StringBuilder sb = new StringBuilder();
            for (String l : existing.split("\n")) {
                if (!l.trim().equals(line.trim())) sb.append(l).append('\n');
            }
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            toast("Remove failed: " + e.getMessage());
        }
    }

    private String readFile(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private void toast(String s) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }
}