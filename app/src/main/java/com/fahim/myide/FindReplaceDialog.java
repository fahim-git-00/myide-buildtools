package com.fahim.myide;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FindReplaceDialog {

    private final Context ctx;
    private final EditText target;
    private final Dialog dlg;

    private EditText findField, replaceField;
    private CheckBox caseBox, regexBox;
    private TextView counter;

    private final List<int[]> matches = new ArrayList<int[]>();
    private int currentMatch = -1;

    public FindReplaceDialog(Context ctx, EditText target) {
        this.ctx = ctx;
        this.target = target;
        this.dlg = new Dialog(ctx);
        build();
    }

    private void build() {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF252526);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        findField = new EditText(ctx);
        findField.setHint("Find");
        findField.setTextColor(0xFFD4D4D4);
        findField.setHintTextColor(0xFF666666);
        root.addView(findField);

        replaceField = new EditText(ctx);
        replaceField.setHint("Replace with");
        replaceField.setTextColor(0xFFD4D4D4);
        replaceField.setHintTextColor(0xFF666666);
        root.addView(replaceField);

        LinearLayout opts = new LinearLayout(ctx);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        opts.setGravity(Gravity.CENTER_VERTICAL);
        caseBox = new CheckBox(ctx);
        caseBox.setText("Aa");
        caseBox.setTextColor(0xFFD4D4D4);
        regexBox = new CheckBox(ctx);
        regexBox.setText(".*");
        regexBox.setTextColor(0xFFD4D4D4);
        counter = new TextView(ctx);
        counter.setTextColor(0xFF858585);
        counter.setPadding(dp(12), 0, 0, 0);
        opts.addView(caseBox);
        opts.addView(regexBox);
        opts.addView(counter);
        root.addView(opts);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(btn("Prev", new View.OnClickListener() {
							 @Override public void onClick(View v) { step(-1); }
						 }));
        btns.addView(btn("Next", new View.OnClickListener() {
							 @Override public void onClick(View v) { step(1); }
						 }));
        btns.addView(btn("Replace", new View.OnClickListener() {
							 @Override public void onClick(View v) { replaceOne(); }
						 }));
        btns.addView(btn("All", new View.OnClickListener() {
							 @Override public void onClick(View v) { replaceAll(); }
						 }));
        root.addView(btns);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { rescan(); }
        };
        findField.addTextChangedListener(watcher);

        CompoundButton.OnCheckedChangeListener toggler =
            new CompoundButton.OnCheckedChangeListener() {
			@Override public void onCheckedChanged(CompoundButton v, boolean b) { rescan(); }
		};
        caseBox.setOnCheckedChangeListener(toggler);
        regexBox.setOnCheckedChangeListener(toggler);

        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(root);
        dlg.getWindow().setLayout(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT);
        dlg.getWindow().setGravity(Gravity.BOTTOM);
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    public void show() {
        String sel = getSelectedText();
        if (sel != null && !sel.isEmpty() && !sel.contains("\n")) findField.setText(sel);
        dlg.show();
        rescan();
    }

    private String getSelectedText() {
        int s = target.getSelectionStart(), e = target.getSelectionEnd();
        if (s < 0 || e < 0 || s == e) return null;
        return target.getText().subSequence(Math.min(s, e), Math.max(s, e)).toString();
    }

    private void rescan() {
        matches.clear();
        currentMatch = -1;

        String needle = findField.getText().toString();
        if (needle.isEmpty()) { counter.setText(""); return; }

        String hay = target.getText().toString();
        Pattern p = compile(needle);
        if (p == null) { counter.setText("bad regex"); return; }

        Matcher m = p.matcher(hay);
        while (m.find()) matches.add(new int[]{m.start(), m.end()});

        Spannable span = new SpannableString(hay);
        for (int[] r : matches) {
            span.setSpan(new BackgroundColorSpan(0x5533AAFF), r[0], r[1],
						 Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int cs = target.getSelectionStart();
        target.setText(span);
        target.setSelection(Math.max(0, Math.min(cs, span.length())));

        counter.setText(matches.size() + " matches");
        if (!matches.isEmpty()) { currentMatch = 0; focus(0); }
    }

    private Pattern compile(String needle) {
        try {
            int flags = caseBox.isChecked() ? 0 : Pattern.CASE_INSENSITIVE;
            if (regexBox.isChecked()) return Pattern.compile(needle, flags);
            return Pattern.compile(Pattern.quote(needle), flags);
        } catch (Exception e) { return null; }
    }

    private void step(int dir) {
        if (matches.isEmpty()) return;
        currentMatch = (currentMatch + dir + matches.size()) % matches.size();
        focus(currentMatch);
    }

    private void focus(int i) {
        int[] r = matches.get(i);
        target.requestFocus();
        target.setSelection(r[0], r[1]);
        counter.setText((i + 1) + "/" + matches.size());
    }

    private void replaceOne() {
        if (matches.isEmpty() || currentMatch < 0) return;
        int[] r = matches.get(currentMatch);
        Editable e = target.getText();
        e.replace(r[0], r[1], replaceField.getText().toString());
        rescan();
    }

    private void replaceAll() {
        String needle = findField.getText().toString();
        if (needle.isEmpty()) return;
        Pattern p = compile(needle);
        if (p == null) return;
        String rep = replaceField.getText().toString();
        String hay = target.getText().toString();
        String out = p.matcher(hay).replaceAll(Matcher.quoteReplacement(rep));
        target.setText(out);
        rescan();
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
