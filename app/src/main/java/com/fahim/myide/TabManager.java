package com.fahim.myide;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TabManager {

    public interface Listener {
        void onTabSelected(EditorTab tab);
        void onTabClosed(EditorTab tab);
    }

    private final Context ctx;
    private final HorizontalScrollView strip;
    private final LinearLayout row;
    private final List<EditorTab> tabs = new ArrayList<EditorTab>();
    private int activeIndex = -1;
    private Listener listener;

    public TabManager(Context ctx, HorizontalScrollView strip) {
        this.ctx = ctx;
        this.strip = strip;
        this.row = new LinearLayout(ctx);
        this.row.setOrientation(LinearLayout.HORIZONTAL);
        this.strip.removeAllViews();
        this.strip.addView(row);
        this.strip.setHorizontalScrollBarEnabled(false);
    }

    public void setListener(Listener l) { this.listener = l; }
    public List<EditorTab> getTabs() { return tabs; }
    public int getActiveIndex() { return activeIndex; }

    public EditorTab active() {
        return (activeIndex < 0 || activeIndex >= tabs.size()) ? null : tabs.get(activeIndex);
    }

    public EditorTab openOrFocus(File file, String content) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).file.getAbsolutePath().equals(file.getAbsolutePath())) {
                select(i);
                return tabs.get(i);
            }
        }
        EditorTab t = new EditorTab(file, content);
        tabs.add(t);
        render();
        select(tabs.size() - 1);
        return t;
    }

    public void select(int i) {
        if (i < 0 || i >= tabs.size()) return;
        activeIndex = i;
        render();
        scrollTabIntoView(i);
        if (listener != null) listener.onTabSelected(tabs.get(i));
    }

    public void close(int i) {
        if (i < 0 || i >= tabs.size()) return;
        EditorTab gone = tabs.remove(i);
        if (activeIndex >= tabs.size()) activeIndex = tabs.size() - 1;
        if (activeIndex < 0 && !tabs.isEmpty()) activeIndex = 0;
        render();
        if (listener != null) listener.onTabClosed(gone);
        if (listener != null && activeIndex >= 0) listener.onTabSelected(tabs.get(activeIndex));
    }

    public void refreshTitles() {
        render();
    }

    private void scrollTabIntoView(final int idx) {
        strip.post(new Runnable() {
				@Override public void run() {
					if (idx < 0 || idx >= row.getChildCount()) return;
					View child = row.getChildAt(idx);
					if (child == null) return;
					strip.smoothScrollTo(Math.max(0, child.getLeft() - 40), 0);
				}
			});
    }

    private void render() {
        row.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            EditorTab t = tabs.get(i);

            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(10), dp(4), dp(4), dp(4));
            cell.setBackgroundColor(i == activeIndex ? 0xFF2D2D30 : 0xFF1E1E1E);

            TextView label = new TextView(ctx);
            label.setText(t.title());
            label.setTextColor(i == activeIndex ? Color.WHITE : 0xFFB0B0B0);
            label.setTextSize(13f);
            label.setSingleLine(true);
            label.setMaxWidth(dp(180));
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            label.setOnClickListener(new View.OnClickListener() {
					@Override public void onClick(View v) { select(idx); }
				});
            cell.addView(label);

            TextView close = new TextView(ctx);
            close.setText("  \u2715");
            close.setTextColor(0xFFB0B0B0);
            close.setTextSize(13f);
            close.setPadding(dp(6), 0, dp(6), 0);
            close.setOnClickListener(new View.OnClickListener() {
					@Override public void onClick(View v) { close(idx); }
				});
            cell.addView(close);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(1);
            row.addView(cell, lp);
        }
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
