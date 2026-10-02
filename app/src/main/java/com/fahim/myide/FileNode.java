package com.fahim.myide;

import java.io.File;

public class FileNode {
    public final String name;
    public final boolean isDirectory;
    public final Object uri;    // kept for compatibility (unused)
    public final int depth;
    public final File file;     // real file on disk

    public FileNode(String name, boolean isDirectory, Object uri, int depth, File file) {
        this.name = name;
        this.isDirectory = isDirectory;
        this.uri = uri;
        this.depth = depth;
        this.file = file;
    }
}
