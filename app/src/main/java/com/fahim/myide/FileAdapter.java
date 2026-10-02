package com.fahim.myide;

import android.content.Context;
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
            tv.setTextSize(14f);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        }

        FileNode n = nodes.get(position);
        tv.setPadding(16 + n.depth * 24, 24, 16, 24);
        tv.setText((n.isDirectory ? "📁 " : "📄 ") + n.name);
        tv.setTextColor(n.isDirectory ? 0xFFDCDCAA : 0xFFD4D4D4);
        return tv;
    }
}
