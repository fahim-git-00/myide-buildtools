package com.fahim.myide;

import java.io.File;

public class EditorTab {
    public final File file;
    public String text;
    public int selStart, selEnd;
    public int scrollY;
    public boolean dirty;
    public boolean loaded;

    public EditorTab(File f, String initial) {
        this.file = f;
        this.text = initial;
        this.selStart = 0;
        this.selEnd = 0;
        this.loaded = false;
    }

    public String title() {
        return file.getName() + (dirty ? " \u2022" : "");
    }
}