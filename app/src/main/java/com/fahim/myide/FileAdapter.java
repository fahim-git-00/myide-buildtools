package com.fahim.myide;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

public class FileAdapter extends BaseAdapter {

    private final Context context;
    private final List<FileNode> nodes;

    public FileAdapter(Context context, List<FileNode> nodes) {
        this.context = context;
        this.nodes = nodes;
    }

    @Override public int getCount() { return nodes.size(); }
    @Override public Object getItem(int i) { return nodes.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView tv;
        if (convertView instanceof TextView) {
            tv = (TextView) convertView;
        } else {
            tv = new TextView(context);
            tv.setTextSize(13f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setSingleLine(true);
        }

        FileNode n = nodes.get(position);
        float d = context.getResources().getDisplayMetrics().density;
        int pad = (int)(6 * d);
        int indent = (int)((12 + n.depth * 16) * d);
        tv.setPadding(indent, pad, pad, pad);

        String icon;
        if (n.isDirectory) icon = "📁 ";
        else icon = iconFor(n.name);
        tv.setText(icon + n.name);

        if (n.isDirectory) {
            tv.setTextColor(0xFFDCDCAA);
        } else {
            tv.setTextColor(0xFFD4D4D4);
        }

        tv.setBackgroundColor(0x00000000);
        return tv;
    }

    private String iconFor(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(".java")) return "☕ ";
        if (l.endsWith(".kt") || l.endsWith(".kts")) return "🅺 ";
        if (l.endsWith(".xml")) return "📐 ";
        if (l.endsWith(".gradle")) return "🐘 ";
        if (l.endsWith(".json")) return "{} ";
        if (l.endsWith(".md")) return "📝 ";
        if (l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".webp")) return "🖼 ";
        if (l.endsWith(".jar") || l.endsWith(".aar")) return "📦 ";
        if (l.endsWith(".apk")) return "📱 ";
        return "📄 ";
    }
}