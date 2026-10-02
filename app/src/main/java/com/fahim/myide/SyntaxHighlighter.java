package com.fahim.myide;

import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyntaxHighlighter {

    private static final int COLOR_KEYWORD   = 0xFF569CD6;
    private static final int COLOR_STRING    = 0xFFCE9178;
    private static final int COLOR_COMMENT   = 0xFF6A9955;
    private static final int COLOR_NUMBER    = 0xFFB5CEA8;
    private static final int COLOR_ANNOTATION= 0xFFDCDCAA;

    private static final String[] KEYWORDS = {
        "abstract","assert","boolean","break","byte","case","catch","char",
        "class","const","continue","default","do","double","else","enum",
        "extends","final","finally","float","for","goto","if","implements",
        "import","instanceof","int","interface","long","native","new",
        "package","private","protected","public","return","short","static",
        "strictfp","super","switch","synchronized","this","throw","throws",
        "transient","try","void","volatile","while",
        "true","false","null"
    };

    private static final Pattern P_COMMENT =
	Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern P_STRING =
	Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern P_NUMBER =
	Pattern.compile("\\b\\d+(\\.\\d+)?[fFdDlL]?\\b");
    private static final Pattern P_ANNOTATION =
	Pattern.compile("@\\w+");

    private static final Pattern P_KEYWORD;
    static {
        StringBuilder sb = new StringBuilder("\\b(?:");
        for (int i = 0; i < KEYWORDS.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(KEYWORDS[i]);
        }
        sb.append(")\\b");
        P_KEYWORD = Pattern.compile(sb.toString());
    }

    public static void highlight(Spannable text) {
        ForegroundColorSpan[] old = text.getSpans(
            0, text.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan s : old) {
            text.removeSpan(s);
        }

        String s = text.toString();

        applyPattern(text, s, P_COMMENT, COLOR_COMMENT);
        applyPattern(text, s, P_STRING, COLOR_STRING);
        applyPattern(text, s, P_ANNOTATION, COLOR_ANNOTATION);
        applyPattern(text, s, P_NUMBER, COLOR_NUMBER);
        applyPattern(text, s, P_KEYWORD, COLOR_KEYWORD);
        applyPattern(text, s, P_COMMENT, COLOR_COMMENT);
        applyPattern(text, s, P_STRING, COLOR_STRING);
    }

    private static void applyPattern(
		Spannable text, String source, Pattern p, int color) {
        Matcher m = p.matcher(source);
        while (m.find()) {
            text.setSpan(
                new ForegroundColorSpan(color),
                m.start(), m.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
    }
}
