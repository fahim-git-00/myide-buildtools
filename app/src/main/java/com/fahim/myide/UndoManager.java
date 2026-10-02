package com.fahim.myide;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import java.util.ArrayDeque;
import java.util.Deque;

public class UndoManager {

    private static final int MAX_DEPTH = 100;
    private static final long DEBOUNCE_MS = 400;

    private final EditText edit;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Deque<Snapshot> undo = new ArrayDeque<Snapshot>();
    private final Deque<Snapshot> redo = new ArrayDeque<Snapshot>();

    private String lastCommitted;
    private boolean selfEdit = false;
    private Runnable pending;
    private int lastCaret;

    private static class Snapshot {
        final String text; final int caret;
        Snapshot(String t, int c) { text = t; caret = c; }
    }

    public UndoManager(EditText edit) {
        this.edit = edit;
        this.lastCommitted = edit.getText().toString();
        this.lastCaret = edit.getSelectionStart();
        edit.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    if (selfEdit) return;
                    scheduleCommit(s.toString());
                }
            });
    }

    public void reset() {
        if (pending != null) { h.removeCallbacks(pending); pending = null; }
        undo.clear();
        redo.clear();
        lastCommitted = edit.getText().toString();
        lastCaret = edit.getSelectionStart();
    }

    private void scheduleCommit(String current) {
        if (pending != null) h.removeCallbacks(pending);
        pending = new Runnable() {
            @Override public void run() { commit(edit.getText().toString()); }
        };
        h.postDelayed(pending, DEBOUNCE_MS);
    }

    private void commit(String current) {
        if (current.equals(lastCommitted)) return;
        undo.push(new Snapshot(lastCommitted, lastCaret));
        while (undo.size() > MAX_DEPTH) undo.removeLast();
        redo.clear();
        lastCommitted = current;
        lastCaret = edit.getSelectionStart();
    }

    public void forceCommit() {
        if (pending != null) { h.removeCallbacks(pending); pending = null; }
        commit(edit.getText().toString());
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void undo() {
        forceCommit();
        if (undo.isEmpty()) return;
        redo.push(new Snapshot(lastCommitted, edit.getSelectionStart()));
        Snapshot s = undo.pop();
        apply(s);
    }

    public void redo() {
        forceCommit();
        if (redo.isEmpty()) return;
        undo.push(new Snapshot(lastCommitted, edit.getSelectionStart()));
        Snapshot s = redo.pop();
        apply(s);
    }

    private void apply(Snapshot s) {
        selfEdit = true;
        edit.setText(s.text);
        int caret = Math.max(0, Math.min(s.caret, s.text.length()));
        edit.setSelection(caret);
        selfEdit = false;
        lastCommitted = s.text;
        lastCaret = caret;
    }
}