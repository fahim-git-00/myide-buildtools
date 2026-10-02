package com.fahim.myide;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.Deque;

public class LogcatView extends LinearLayout {

    private static final int MAX_LINES = 4000;

    private final TextView out;
    private final ScrollView scroller;
    private final EditText filter;
    private final Deque<String> buffer = new ArrayDeque<String>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Process proc;
    private Thread reader;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile String filterText = "";
    private boolean autoScroll = true;

    // level filters
    private boolean showV = true, showD = true, showI = true, showW = true, showE = true;

    public LogcatView(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF1E1E1E);

        int pad = dp(8);

        // ---- Top row: filter text + pause + clear ----
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, pad);

        filter = new EditText(ctx);
        filter.setHint("filter (substring)");
        filter.setHintTextColor(0xFF666666);
        filter.setTextColor(0xFFD4D4D4);
        filter.setSingleLine(true);
        filter.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    filterText = s.toString().trim();
                    rerender();
                }
            });
        LayoutParams fp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(filter, fp);

        bar.addView(btn("Pause", new OnClickListener() {
                @Override public void onClick(View v) {
                    paused = !paused;
                    ((Button) v).setText(paused ? "Resume" : "Pause");
                }
            }));
        bar.addView(btn("Clear", new OnClickListener() {
                @Override public void onClick(View v) {
                    buffer.clear();
                    out.setText("");
                }
            }));
        addView(bar);

        // ---- Second row: level checkboxes + copy + save + autoscroll ----
        LinearLayout bar2 = new LinearLayout(ctx);
        bar2.setOrientation(HORIZONTAL);
        bar2.setGravity(Gravity.CENTER_VERTICAL);
        bar2.setPadding(pad, 0, pad, pad);

        bar2.addView(chk("V", showV, new LevelToggle() {
                @Override public void set(boolean v) { showV = v; rerender(); }
            }));
        bar2.addView(chk("D", showD, new LevelToggle() {
                @Override public void set(boolean v) { showD = v; rerender(); }
            }));
        bar2.addView(chk("I", showI, new LevelToggle() {
                @Override public void set(boolean v) { showI = v; rerender(); }
            }));
        bar2.addView(chk("W", showW, new LevelToggle() {
                @Override public void set(boolean v) { showW = v; rerender(); }
            }));
        bar2.addView(chk("E", showE, new LevelToggle() {
                @Override public void set(boolean v) { showE = v; rerender(); }
            }));

        bar2.addView(btn("Copy", new OnClickListener() {
                @Override public void onClick(View v) { copyAll(); }
            }));
        bar2.addView(btn("Save", new OnClickListener() {
                @Override public void onClick(View v) { saveToFile(); }
            }));
        bar2.addView(btn("Auto\u25BC", new OnClickListener() {
                @Override public void onClick(View v) {
                    autoScroll = !autoScroll;
                    ((Button) v).setText(autoScroll ? "Auto\u25BC" : "Auto\u25B2");
                }
            }));
        addView(bar2);

        // ---- Output ----
        scroller = new ScrollView(ctx);
        out = new TextView(ctx);
        out.setTextSize(11f);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setTextColor(0xFFD4D4D4);
        out.setMovementMethod(new ScrollingMovementMethod());
        out.setTextIsSelectable(true);
        out.setPadding(pad, pad, pad, pad);
        scroller.addView(out);
        addView(scroller, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private interface LevelToggle { void set(boolean v); }

    private CheckBox chk(String label, boolean checked, final LevelToggle cb) {
        CheckBox c = new CheckBox(getContext());
        c.setText(label);
        c.setTextColor(0xFFD4D4D4);
        c.setChecked(checked);
        c.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    cb.set(((CheckBox) v).isChecked());
                }
            });
        return c;
    }

    private Button btn(String t, OnClickListener l) {
        Button b = new Button(getContext());
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();
            proc = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "time", "*:V"});
            final BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));
            reader = new Thread(new Runnable() {
                    @Override public void run() {
                        String line;
                        try {
                            while (running && (line = r.readLine()) != null) {
                                final String l = line;
                                ui.post(new Runnable() {
                                        @Override public void run() { append(l); }
                                    });
                            }
                        } catch (Exception ignored) {}
                    }
                }, "logcat-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            append("logcat failed: " + e.getMessage());
            running = false;
        }
    }

    public void stop() {
        running = false;
        try { if (proc != null) proc.destroy(); } catch (Exception ignored) {}
        proc = null;
        reader = null;
    }

    public void onResume() { if (!running) start(); }
    public void onPause()  { }

    private void append(String line) {
        if (paused) return;
        buffer.addLast(line);
        while (buffer.size() > MAX_LINES) buffer.removeFirst();

        if (!matches(line)) return;

        appendColored(line);
        if (autoScroll) {
            scroller.post(new Runnable() {
                    @Override public void run() { scroller.fullScroll(ScrollView.FOCUS_DOWN); }
                });
        }
    }

    private void appendColored(String line) {
        int color = colorFor(line);
        SpannableString ss = new SpannableString(line + "\n");
        ss.setSpan(new ForegroundColorSpan(color), 0, ss.length(),
                   Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append(ss);
    }

    private int colorFor(String line) {
        char lvl = levelOf(line);
        switch (lvl) {
            case 'E': return 0xFFFF6666;
            case 'W': return 0xFFFFCC66;
            case 'I': return 0xFF66FF66;
            case 'D': return 0xFF66CCFF;
            case 'V': return 0xFFAAAAAA;
            default:  return 0xFFD4D4D4;
        }
    }

    /** logcat -v time format: "MM-DD HH:MM:SS.mmm L/TAG(PID): msg" */
    private char levelOf(String line) {
        if (line == null || line.length() < 19) return ' ';
        // find first space after time, then first char
        int i = line.indexOf(' ');
        if (i < 0) return ' ';
        int j = line.indexOf(' ', i + 1);
        if (j < 0 || j + 1 >= line.length()) return ' ';
        return line.charAt(j + 1);
    }

    private boolean matches(String line) {
        if (line == null) return false;
        // level filter
        char lvl = levelOf(line);
        switch (lvl) {
            case 'V': if (!showV) return false; break;
            case 'D': if (!showD) return false; break;
            case 'I': if (!showI) return false; break;
            case 'W': if (!showW) return false; break;
            case 'E': if (!showE) return false; break;
        }
        if (filterText.isEmpty()) return true;
        return line.toLowerCase().contains(filterText.toLowerCase());
    }

    private void rerender() {
        out.setText("");
        for (String l : buffer) {
            if (matches(l)) appendColored(l);
        }
        if (autoScroll) {
            scroller.post(new Runnable() {
                    @Override public void run() { scroller.fullScroll(ScrollView.FOCUS_DOWN); }
                });
        }
    }

    // ---- Copy ----
    private void copyAll() {
        StringBuilder sb = new StringBuilder();
        for (String l : buffer) if (matches(l)) sb.append(l).append('\n');
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
            getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("logcat", sb.toString()));
        Toast.makeText(getContext(), "Copied " + buffer.size() + " lines",
                       Toast.LENGTH_SHORT).show();
    }

    // ---- Save ----
    private void saveToFile() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "logcat_" + System.currentTimeMillis() + ".txt");
            FileOutputStream fos = new FileOutputStream(f);
            for (String l : buffer) {
                if (matches(l)) fos.write((l + "\n").getBytes("UTF-8"));
            }
            fos.close();
            Toast.makeText(getContext(), "Saved: " + f.getAbsolutePath(),
                           Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Save failed: " + e.getMessage(),
                           Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}