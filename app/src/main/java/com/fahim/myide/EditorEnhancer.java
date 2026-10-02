package com.fahim.myide;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

import java.util.ArrayDeque;
import java.util.Deque;

public class EditorEnhancer {

    public interface FoldProvider {
        void requestFoldToggle(int line);
    }

    private final EditText editor;
    private FoldProvider foldProvider;

    private boolean internalEdit = false;
    private String lastCharBefore;

    private static final String OPENERS  = "([{\"'`";
    private static final String CLOSERS  = ")]}\"'`";

    private final Deque<Integer> undoIndent = new ArrayDeque<Integer>();

    public EditorEnhancer(EditText editor) {
        this.editor = editor;
        install();
    }

    public void setFoldProvider(FoldProvider p) {
        this.foldProvider = p;
    }

    private void install() {
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                if (internalEdit) return;
                if (c > 0 && a == 0 && st >= 0 && st < s.length()) {
                    lastCharBefore = String.valueOf(s.charAt(st));
                } else {
                    lastCharBefore = null;
                }
            }

            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (internalEdit) return;
                if (c == 1 && b == 0 && st >= 0 && st <= s.length()) {
                    char typed = s.charAt(st);
                    handleAutoClose(typed, st);
                }
            }

            @Override public void afterTextChanged(Editable s) { }
        });

        editor.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                if (keyCode == KeyEvent.KEYCODE_TAB) {
                    insertSpaces(4);
                    return true;
                }

                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    return handleBackspaceDelete();
                }

                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    insertNewlineWithIndent();
                    return true;
                }

                // hardware shortcuts
                if (event.isCtrlPressed()) {
                    if (keyCode == KeyEvent.KEYCODE_S) {
                        editor.getContext();
                        // Save handled by activity via menu; just return false to let normal work
                        return false;
                    }
                }

                return false;
            }
        });
    }

    // ---- auto close ----

    private void handleAutoClose(char typed, int pos) {
        int idx = OPENERS.indexOf(typed);
        if (idx < 0) return;

        // Skip auto-close if next char is alphanumeric (word continuation)
        Editable e = editor.getText();
        if (pos + 1 < e.length()) {
            char next = e.charAt(pos + 1);
            if (Character.isLetterOrDigit(next) || next == '_') return;
        }

        // Skip for quotes if we are likely just closing an existing quote
        if (typed == '"' || typed == '\'' || typed == '`') {
            if (pos > 0) {
                char before = e.charAt(pos - 1);
                if (before == '\\') return;
            }
        }

        char closer = CLOSERS.charAt(idx);

        internalEdit = true;
        try {
            e.insert(pos + 1, String.valueOf(closer));
            editor.setSelection(pos + 1);
        } finally {
            internalEdit = false;
        }
    }

    // ---- backspace ----

    private boolean handleBackspaceDelete() {
        int s = editor.getSelectionStart();
        int e = editor.getSelectionEnd();
        if (s != e) return false; // native selection delete
        if (s <= 0) return false;

        Editable text = editor.getText();
        char before = text.charAt(s - 1);
        if (s < text.length()) {
            char after = text.charAt(s);
            int oi = OPENERS.indexOf(before);
            if (oi >= 0 && CLOSERS.charAt(oi) == after) {
                internalEdit = true;
                try {
                    text.delete(s - 1, s + 1);
                } finally {
                    internalEdit = false;
                }
                return true;
            }
        }
        return false;
    }

    // ---- newline indent ----

    private void insertNewlineWithIndent() {
        int selStart = editor.getSelectionStart();
        int selEnd = editor.getSelectionEnd();
        if (selStart != selEnd) {
            // replace selection
            internalEdit = true;
            try {
                editor.getText().replace(selStart, selEnd, "\n");
            } finally {
                internalEdit = false;
            }
            return;
        }

        Editable text = editor.getText();
        int lineStart = selStart;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;

        StringBuilder indent = new StringBuilder();
        int i = lineStart;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') { indent.append(c); i++; }
            else break;
        }

        // if previous non-space char on this line is '{', add extra indent
        int j = selStart - 1;
        while (j >= lineStart && Character.isWhitespace(text.charAt(j))) j--;
        boolean extra = j >= lineStart && text.charAt(j) == '{';

        String insert = "\n" + indent.toString() + (extra ? "    " : "");
        internalEdit = true;
        try {
            text.insert(selStart, insert);
            editor.setSelection(selStart + insert.length());
        } finally {
            internalEdit = false;
        }
    }

    private void insertSpaces(int n) {
        int s = editor.getSelectionStart();
        int e = editor.getSelectionEnd();
        internalEdit = true;
        try {
            editor.getText().replace(Math.min(s, e), Math.max(s, e),
                    repeat(' ', n));
        } finally {
            internalEdit = false;
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}