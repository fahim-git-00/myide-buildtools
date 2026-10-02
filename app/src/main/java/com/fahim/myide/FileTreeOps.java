package com.fahim.myide;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class FileTreeOps {

    public interface After {
        void done(boolean changed);
    }

    public static void newFile(Context ctx, final File parentDir, final After after) {
        promptName(ctx, "New file", "name.java", new NameCb() {
                @Override public void onName(String name) {
                    File f = new File(parentDir, name);
                    try {
                        if (f.getParentFile() != null) f.getParentFile().mkdirs();
                        if (!f.createNewFile()) { /* already exists */ }
                        after.done(true);
                    } catch (IOException e) { after.done(false); }
                }
            });
    }

    public static void newFolder(Context ctx, final File parentDir, final After after) {
        promptName(ctx, "New folder", "folder", new NameCb() {
                @Override public void onName(String name) {
                    File f = new File(parentDir, name);
                    after.done(f.mkdirs());
                }
            });
    }

    public static void rename(Context ctx, final File target, final After after) {
        promptName(ctx, "Rename", target.getName(), new NameCb() {
                @Override public void onName(String name) {
                    File dest = new File(target.getParentFile(), name);
                    if (dest.exists()) { after.done(false); return; }
                    after.done(target.renameTo(dest));
                }
            });
    }

    public static void delete(Context ctx, final File target, final After after) {
        new android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Delete " + target.getName() + "?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    after.done(deleteRecursive(target));
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    public static void duplicate(File src, After after) {
        try {
            File dst = new File(src.getParentFile(),
                                baseName(src.getName()) + "_copy" + ext(src.getName()));
            copyRecursive(src, dst);
            after.done(true);
        } catch (IOException e) { after.done(false); }
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        return f.delete();
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("mkdir failed");
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRecursive(k, new File(dst, k.getName()));
        } else {
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (IOException ignored) {}
                try { out.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static String baseName(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(0, i);
    }

    private static String ext(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i);
    }

    private interface NameCb { void onName(String name); }

    private static void promptName(Context ctx, String title, String initial, final NameCb cb) {
        final EditText input = new EditText(ctx);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                           | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setText(initial);
        int dot = initial.lastIndexOf('.');
        input.setSelection(0, dot > 0 ? dot : initial.length());
        input.setTextColor(0xFFD4D4D4);
        input.setHintTextColor(0xFF666666);

        new android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String name = input.getText().toString().trim();
                    if (name.length() > 0) cb.onName(name);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}