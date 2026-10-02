package com.fahim.myide;

import java.io.File;

public class FileNode {
    public final String name;
    public final boolean isDirectory;
    public final Object uri;
    public final int depth;
    public final File file;

    public FileNode(String name, boolean isDirectory, Object uri, int depth, File file) {
        this.name = name;
        this.isDirectory = isDirectory;
        this.uri = uri;
        this.depth = depth;
        this.file = file;
    }
}