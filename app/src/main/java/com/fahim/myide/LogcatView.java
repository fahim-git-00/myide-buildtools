package com.fahim.myide;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
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

    public LogcatView(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF1E1E1E);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(8);
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
            // Clear the kernel log buffer first so we don't replay history.
            Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();

            proc = Runtime.getRuntime().exec(new String[]{
												 "logcat", "-v", "time", "*:V"
											 });
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
    public void onPause()  { /* keep running; call stop() to save battery */ }

    private void append(String line) {
        if (paused) return;
        buffer.addLast(line);
        while (buffer.size() > MAX_LINES) buffer.removeFirst();

        if (!matches(line)) return;

        out.append(line);
        out.append("\n");
        scroller.post(new Runnable() {
				@Override public void run() { scroller.fullScroll(ScrollView.FOCUS_DOWN); }
			});
    }

    private boolean matches(String line) {
        if (filterText.isEmpty()) return true;
        return line.toLowerCase().contains(filterText.toLowerCase());
    }

    private void rerender() {
        StringBuilder sb = new StringBuilder();
        for (String l : buffer) {
            if (matches(l)) { sb.append(l).append('\n'); }
        }
        out.setText(sb.toString());
        scroller.post(new Runnable() {
				@Override public void run() { scroller.fullScroll(ScrollView.FOCUS_DOWN); }
			});
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
