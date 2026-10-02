package com.fahim.myide;

import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyntaxHighlighter {

    private static final int C_KEYWORD    = 0xFF569CD6;
    private static final int C_STRING     = 0xFFCE9178;
    private static final int C_COMMENT    = 0xFF6A9955;
    private static final int C_NUMBER     = 0xFFB5CEA8;
    private static final int C_ANNOTATION = 0xFFDCDCAA;
    private static final int C_TYPE       = 0xFF4EC9B0;
    private static final int C_XML_TAG    = 0xFF569CD6;
    private static final int C_XML_ATTR   = 0xFF9CDCFE;
    private static final int C_XML_STR    = 0xFFCE9178;

    public static final int LANG_JAVA = 0;
    public static final int LANG_XML = 1;
    public static final int LANG_JSON = 2;
    public static final int LANG_GRADLE = 3;
    public static final int LANG_MARKDOWN = 4;
    public static final int LANG_KOTLIN = 5;

    public static int detectLang(String path) {
        if (path == null) return LANG_JAVA;
        String p = path.toLowerCase();
        if (p.endsWith(".xml"))    return LANG_XML;
        if (p.endsWith(".json"))   return LANG_JSON;
        if (p.endsWith(".gradle")) return LANG_GRADLE;
        if (p.endsWith(".md"))     return LANG_MARKDOWN;
        if (p.endsWith(".kt") || p.endsWith(".kts")) return LANG_KOTLIN;
        return LANG_JAVA;
    }

    private static final String[] JAVA_KW = {
        "abstract","assert","boolean","break","byte","case","catch","char",
        "class","const","continue","default","do","double","else","enum",
        "extends","final","finally","float","for","goto","if","implements",
        "import","instanceof","int","interface","long","native","new",
        "package","private","protected","public","return","short","static",
        "strictfp","super","switch","synchronized","this","throw","throws",
        "transient","try","void","volatile","while",
        "true","false","null"
    };

    private static final String[] GRADLE_KW = {
        "apply","plugin","android","dependencies","repositories",
        "buildscript","allprojects","task","def","ext","implementation",
        "compile","compileSdk","minSdk","targetSdk","buildToolsVersion",
        "defaultConfig","buildTypes","release","debug","proguardFiles",
        "minifyEnabled","testImplementation","api","classpath","mavenCentral",
        "google","jcenter"
    };

    private static final String[] KOTLIN_KW = {
        // hard keywords
        "as","break","class","continue","do","else","false","for","fun","if",
        "in","interface","is","null","object","package","return","super","this",
        "throw","true","try","typealias","typeof","val","var","when","while",
        // soft keywords
        "by","catch","constructor","delegate","dynamic","field","file","finally",
        "get","import","init","param","property","receiver","set","setparam",
        "where","actual","abstract","annotation","companion","const","crossinline",
        "data","enum","expect","external","final","infix","inline","inner",
        "internal","lateinit","noinline","open","operator","out","override",
        "private","protected","public","reified","sealed","suspend","tailrec",
        "vararg","it"
    };

    private static final Pattern P_JAVA_COMMENT =
        Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern P_JAVA_STRING =
        Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private static final Pattern P_JAVA_NUMBER =
        Pattern.compile("\\b\\d+(\\.\\d+)?[fFdDlL]?\\b|\\b0[xX][0-9a-fA-F]+\\b");
    private static final Pattern P_JAVA_ANNOT =
        Pattern.compile("@\\w+");
    private static final Pattern P_JAVA_TYPE =
        Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");

    private static final Pattern P_XML_COMMENT =
        Pattern.compile("<!--[\\s\\S]*?-->");
    private static final Pattern P_XML_TAG =
        Pattern.compile("</?[A-Za-z_][A-Za-z0-9_.:-]*");
    private static final Pattern P_XML_CLOSE =
        Pattern.compile("/?>");
    private static final Pattern P_XML_ATTR =
        Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_.:-]*(?=\\s*=)");
    private static final Pattern P_XML_STRING =
        Pattern.compile("\"[^\"]*\"");
    private static final Pattern P_XML_DECL =
        Pattern.compile("<\\?[\\s\\S]*?\\?>");

    private static final Pattern P_JSON_STRING =
        Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern P_JSON_NUMBER =
        Pattern.compile("\\b-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?\\b");
    private static final Pattern P_JSON_KW =
        Pattern.compile("\\b(?:true|false|null)\\b");

    private static final Pattern P_MD_HEADER =
        Pattern.compile("^#{1,6} .*$", Pattern.MULTILINE);
    private static final Pattern P_MD_CODE =
        Pattern.compile("`[^`]+`");
    private static final Pattern P_MD_BOLD =
        Pattern.compile("\\*\\*[^*]+\\*\\*");
    private static final Pattern P_MD_LINK =
        Pattern.compile("\\[[^\\]]+\\]\\([^)]+\\)");

    private static final Pattern P_JAVA_KW_RE;
    private static final Pattern P_GRADLE_KW_RE;
    private static final Pattern P_KOTLIN_KW_RE;

    static {
        P_JAVA_KW_RE = buildKwPattern(JAVA_KW);
        P_GRADLE_KW_RE = buildKwPattern(GRADLE_KW);
        P_KOTLIN_KW_RE = buildKwPattern(KOTLIN_KW);
    }

    private static Pattern buildKwPattern(String[] kws) {
        StringBuilder sb = new StringBuilder("\\b(?:");
        for (int i = 0; i < kws.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(kws[i]);
        }
        sb.append(")\\b");
        return Pattern.compile(sb.toString());
    }

    public static void highlight(Spannable text) {
        highlight(text, LANG_JAVA);
    }

    public static void highlight(Spannable text, int lang) {
        clearSpans(text);
        String s = text.toString();

        switch (lang) {
            case LANG_XML:      highlightXml(text, s); break;
            case LANG_JSON:     highlightJson(text, s); break;
            case LANG_GRADLE:   highlightGradle(text, s); break;
            case LANG_MARKDOWN: highlightMarkdown(text, s); break;
            case LANG_KOTLIN:   highlightKotlin(text, s); break;
            default:            highlightJava(text, s); break;
        }
    }

    private static void clearSpans(Spannable text) {
        ForegroundColorSpan[] old = text.getSpans(
            0, text.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan sp : old) text.removeSpan(sp);
    }

    private static void highlightJava(Spannable t, String s) {
        apply(t, s, P_JAVA_COMMENT, C_COMMENT);
        apply(t, s, P_JAVA_STRING, C_STRING);
        apply(t, s, P_JAVA_ANNOT, C_ANNOTATION);
        apply(t, s, P_JAVA_NUMBER, C_NUMBER);
        apply(t, s, P_JAVA_TYPE, C_TYPE);
        apply(t, s, P_JAVA_KW_RE, C_KEYWORD);
        apply(t, s, P_JAVA_COMMENT, C_COMMENT);
        apply(t, s, P_JAVA_STRING, C_STRING);
    }

    private static void highlightKotlin(Spannable t, String s) {
        apply(t, s, P_JAVA_COMMENT, C_COMMENT);
        apply(t, s, P_JAVA_STRING, C_STRING);
        apply(t, s, P_JAVA_ANNOT, C_ANNOTATION);
        apply(t, s, P_JAVA_NUMBER, C_NUMBER);
        apply(t, s, P_JAVA_TYPE, C_TYPE);
        apply(t, s, P_KOTLIN_KW_RE, C_KEYWORD);
        apply(t, s, P_JAVA_COMMENT, C_COMMENT);
        apply(t, s, P_JAVA_STRING, C_STRING);
    }

    private static void highlightXml(Spannable t, String s) {
        apply(t, s, P_XML_COMMENT, C_COMMENT);
        apply(t, s, P_XML_DECL, C_KEYWORD);
        apply(t, s, P_XML_TAG, C_XML_TAG);
        apply(t, s, P_XML_CLOSE, C_XML_TAG);
        apply(t, s, P_XML_ATTR, C_XML_ATTR);
        apply(t, s, P_XML_STRING, C_XML_STR);
        apply(t, s, P_XML_COMMENT, C_COMMENT);
    }

    private static void highlightJson(Spannable t, String s) {
        apply(t, s, P_JSON_STRING, C_STRING);
        apply(t, s, P_JSON_NUMBER, C_NUMBER);
        apply(t, s, P_JSON_KW, C_KEYWORD);
        apply(t, s, P_JSON_STRING, C_STRING);
    }

    private static void highlightGradle(Spannable t, String s) {
        apply(t, s, P_JAVA_COMMENT, C_COMMENT);
        apply(t, s, P_JAVA_STRING, C_STRING);
        apply(t, s, P_JAVA_NUMBER, C_NUMBER);
        apply(t, s, P_GRADLE_KW_RE, C_KEYWORD);
        apply(t, s, P_JAVA_COMMENT, C_COMMENT);
        apply(t, s, P_JAVA_STRING, C_STRING);
    }

    private static void highlightMarkdown(Spannable t, String s) {
        apply(t, s, P_MD_HEADER, C_KEYWORD);
        apply(t, s, P_MD_CODE, C_STRING);
        apply(t, s, P_MD_BOLD, C_ANNOTATION);
        apply(t, s, P_MD_LINK, C_TYPE);
    }

    private static void apply(Spannable text, String source, Pattern p, int color) {
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