package com.fahim.myide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_MANAGE_STORAGE = 2001;

    private static final int C_BG        = 0xFF1E1E1E;
    private static final int C_SURFACE   = 0xFF252526;
    private static final int C_SURFACE2  = 0xFF2D2D30;
    private static final int C_DIVIDER   = 0xFF3E3E42;
    private static final int C_TEXT      = 0xFFD4D4D4;
    private static final int C_TEXT_DIM  = 0xFF858585;
    private static final int C_ACCENT    = 0xFF0E639C;
    private static final int C_ACCENT2   = 0xFF4FC3F7;
    private static final int C_FOLDER    = 0xFFDCDCAA;
    private static final int C_WARN      = 0xFFF44747;
    private static final int C_OK        = 0xFF4EC9B0;

    private EditText editor;
    private TextView lineNumbers;
    private boolean isHighlighting = false;

    private TabManager tabs;
    private UndoManager undoMgr;
    private EditorEnhancer enhancer;

    private View sidebar;
    private View dimLayer;
    private ListView fileList;
    private TextView txtProjectPath;
    private Button btnOpenFolder;
    private Button btnRefreshTree;
    private boolean sidebarOpen = false;

    private TextView txtTitle;
    private TextView txtStatusLeft;
    private TextView txtStatusPos;
    private TextView txtStatusLang;
    private TextView btnMenu;
    private TextView btnUndo;
    private TextView btnRedo;
    private TextView btnSave;
    private TextView btnBuild;
    private TextView btnMore;

    private File projectRoot = null;
    private final List<FileNode> fileNodes = new ArrayList<FileNode>();
    private FileAdapter fileAdapter;

    private File currentFile = null;
    private EditorTab lastLoaded = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        editor = (EditText) findViewById(R.id.editor);
        lineNumbers = (TextView) findViewById(R.id.lineNumbers);

        sidebar = findViewById(R.id.sidebar);
        dimLayer = findViewById(R.id.dimLayer);
        fileList = (ListView) findViewById(R.id.fileList);
        txtProjectPath = (TextView) findViewById(R.id.txtProjectPath);
        btnOpenFolder = (Button) findViewById(R.id.btnOpenFolder);
        btnRefreshTree = (Button) findViewById(R.id.btnRefreshTree);

        txtTitle = (TextView) findViewById(R.id.txtTitle);
        txtStatusLeft = (TextView) findViewById(R.id.txtStatusLeft);
        txtStatusPos = (TextView) findViewById(R.id.txtStatusPos);
        txtStatusLang = (TextView) findViewById(R.id.txtStatusLang);
        btnMenu = (TextView) findViewById(R.id.btnMenu);
        btnUndo = (TextView) findViewById(R.id.btnUndo);
        btnRedo = (TextView) findViewById(R.id.btnRedo);
        btnSave = (TextView) findViewById(R.id.btnSave);
        btnBuild = (TextView) findViewById(R.id.btnBuild);
        btnMore = (TextView) findViewById(R.id.btnMore);

        fileAdapter = new FileAdapter(this, fileNodes);
        fileList.setAdapter(fileAdapter);

        setupTabs();
        setupUndo();
        enhancer = new EditorEnhancer(editor);

        wireToolbar();
        wireSidebar();

        editor.setText(
            "package com.example.myapp;\n\n" +
            "import android.app.Activity;\n" +
            "import android.os.Bundle;\n\n" +
            "public class MainActivity extends Activity {\n" +
            "    @Override protected void onCreate(Bundle b) {\n" +
            "        super.onCreate(b);\n" +
            "        setContentView(R.layout.main);\n" +
            "    }\n" +
            "}\n"
        );

        SyntaxHighlighter.highlight(editor.getText(), currentLang());
        updateLineNumbers(editor.getText().toString());
        updateStatusBar();

        editor.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (isHighlighting) return;
                    isHighlighting = true;
                    SyntaxHighlighter.highlight(s, currentLang());
                    updateLineNumbers(s.toString());
                    updateStatusBar();
                    isHighlighting = false;
                }
            });

        editor.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (event.isCtrlPressed()) {
                        if (keyCode == KeyEvent.KEYCODE_S) { saveFile(); return true; }
                        if (keyCode == KeyEvent.KEYCODE_F) {
                            new FindReplaceDialog(MainActivity.this, editor).show();
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_Z) { flushActiveTab(); undoMgr.undo(); return true; }
                        if (keyCode == KeyEvent.KEYCODE_Y) { flushActiveTab(); undoMgr.redo(); return true; }
                        if (keyCode == KeyEvent.KEYCODE_B) { runBuild(); return true; }
                    }
                    return false;
                }
            });

        requestStoragePermissionIfNeeded();
        updateTitle();
    }

    private void wireToolbar() {
        btnMenu.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (sidebarOpen) closeSidebar();
                    else if (projectRoot == null) pickProjectFolder();
                    else openSidebar();
                }
            });
        btnUndo.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { flushActiveTab(); undoMgr.undo(); }
            });
        btnRedo.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { flushActiveTab(); undoMgr.redo(); }
            });
        btnSave.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { saveFile(); }
            });
        btnBuild.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { runBuild(); }
            });
        btnMore.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openOptionsMenu(); }
            });
    }

    private void wireSidebar() {
        btnOpenFolder.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickProjectFolder(); }
            });
        btnRefreshTree.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { reloadFileTree(); }
            });
        dimLayer.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { closeSidebar(); }
            });
        fileList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                    FileNode n = fileNodes.get(pos);
                    if (n.isDirectory || n.file == null) return;
                    openFileInTab(n.file);
                    closeSidebar();
                }
            });
        fileList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                    FileNode n = fileNodes.get(pos);
                    if (n.file == null) return false;
                    showFileOps(n.file);
                    return true;
                }
            });
    }

    private void applyTheme() {
        SharedPreferences prefs = getSharedPreferences("theme", MODE_PRIVATE);
        int mode = prefs.getInt("mode", 2);
        AppCompatDelegateHelper.setMode(this, mode);
    }

    private void toggleTheme() {
        SharedPreferences prefs = getSharedPreferences("theme", MODE_PRIVATE);
        int current = prefs.getInt("mode", 2);
        int next;
        String msg;
        if (current == 2)      { next = 0; msg = "Light theme"; }
        else if (current == 0) { next = 1; msg = "Dark theme"; }
        else                   { next = 2; msg = "Follow system"; }
        prefs.edit().putInt("mode", next).apply();
        toast(msg);
        recreate();
    }

    private void setupTabs() {
        HorizontalScrollView strip = (HorizontalScrollView) findViewById(R.id.tabStrip);
        tabs = new TabManager(this, strip);
        tabs.setListener(new TabManager.Listener() {
                @Override public void onTabSelected(EditorTab t) {
                    if (lastLoaded != null && lastLoaded != t && lastLoaded.loaded) {
                        flushTab(lastLoaded);
                    }
                    applyTabToEditor(t);
                    t.loaded = true;
                    lastLoaded = t;
                }
                @Override public void onTabClosed(EditorTab t) {
                    if (lastLoaded == t) lastLoaded = null;
                    if (tabs.getTabs().isEmpty()) {
                        currentFile = null;
                        isHighlighting = true;
                        editor.setText("");
                        isHighlighting = false;
                        SyntaxHighlighter.highlight(editor.getText(), currentLang());
                        updateLineNumbers("");
                        updateStatusBar();
                        updateTitle();
                    }
                }
            });
    }

    private void setupUndo() {
        undoMgr = new UndoManager(editor);
    }

    private void applyTabToEditor(final EditorTab t) {
        currentFile = t.file;
        isHighlighting = true;
        editor.setText(t.text);
        SyntaxHighlighter.highlight(editor.getText(), currentLang());
        updateLineNumbers(t.text);
        int s = Math.max(0, Math.min(t.selStart, t.text.length()));
        int e = Math.max(0, Math.min(t.selEnd,   t.text.length()));
        editor.setSelection(s, e);
        editor.post(new Runnable() {
                @Override public void run() { editor.scrollTo(0, t.scrollY); }
            });
        isHighlighting = false;
        updateTitle();
        updateStatusBar();
    }

    private void flushTab(EditorTab t) {
        if (t == null || t != lastLoaded) return;
        String now = editor.getText().toString();
        if (!now.equals(t.text)) { t.text = now; t.dirty = true; }
        t.selStart = editor.getSelectionStart();
        t.selEnd   = editor.getSelectionEnd();
        t.scrollY  = editor.getScrollY();
    }

    private void flushActiveTab() {
        if (lastLoaded != null) flushTab(lastLoaded);
    }

    private void openFileInTab(File f) {
        try {
            String text = readTextFile(f);
            EditorTab t = tabs.openOrFocus(f, text);
            currentFile = t.file;
            updateTitle();
            updateStatusBar();
        } catch (Exception e) {
            toast("Open failed: " + e.getMessage());
        }
    }

    private void closeTabsFor(File f) {
        String abs = f.getAbsolutePath();
        for (int i = tabs.getTabs().size() - 1; i >= 0; i--) {
            File tf = tabs.getTabs().get(i).file;
            String tfa = tf.getAbsolutePath();
            if (tfa.equals(abs) || tfa.startsWith(abs + "/")) tabs.close(i);
        }
    }

    private void showFileOps(final File f) {
        final String[] items = f.isDirectory()
            ? new String[]{"New file", "New folder", "Rename", "Duplicate", "Delete"}
            : new String[]{"Rename", "Duplicate", "Delete"};

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(f.getName())
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    String choice = items[which];
                    if ("New file".equals(choice)) {
                        FileTreeOps.newFile(MainActivity.this, f, new FileTreeOps.After() {
                                @Override public void done(boolean changed) {
                                    if (changed) reloadFileTree();
                                }
                            });
                    } else if ("New folder".equals(choice)) {
                        FileTreeOps.newFolder(MainActivity.this, f, new FileTreeOps.After() {
                                @Override public void done(boolean changed) {
                                    if (changed) reloadFileTree();
                                }
                            });
                    } else if ("Rename".equals(choice)) {
                        FileTreeOps.rename(MainActivity.this, f, new FileTreeOps.After() {
                                @Override public void done(boolean changed) {
                                    if (changed) { reloadFileTree(); closeTabsFor(f); }
                                }
                            });
                    } else if ("Duplicate".equals(choice)) {
                        FileTreeOps.duplicate(f, new FileTreeOps.After() {
                                @Override public void done(boolean changed) {
                                    if (changed) reloadFileTree();
                                }
                            });
                    } else if ("Delete".equals(choice)) {
                        FileTreeOps.delete(MainActivity.this, f, new FileTreeOps.After() {
                                @Override public void done(boolean changed) {
                                    if (changed) { closeTabsFor(f); reloadFileTree(); }
                                }
                            });
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showLibraries() {
        if (projectRoot == null) { toast("Pick or create a project first"); return; }
        new LibrariesDialog(this, projectRoot, new Runnable() {
                @Override public void run() { toast("Dependencies updated"); }
            }).show();
    }

    private void showLogcat() {
        final LogcatView logcat = new LogcatView(this);
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(logcat);
        dlg.getWindow().setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface d) { logcat.stop(); }
            });
        logcat.start();
        dlg.show();
    }

    private void showKotlinMode() {
        final SharedPreferences prefs = getSharedPreferences("kotlin", MODE_PRIVATE);
        final String current = prefs.getString("mode", "auto");
        final String[] labels = {
            "Auto (local if available, else remote)",
            "Local only (on-device kotlinc)",
            "Remote only (GitHub Actions)"
        };
        final String[] values = { "auto", "local", "remote" };
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) checked = i;

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Kotlin Compile Mode")
            .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    prefs.edit().putString("mode", values[which]).apply();
                    toast("Kotlin mode: " + values[which]);
                    d.dismiss();
                }
            })
            .setNeutralButton("GitHub Token", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { showGithubTokenDialog(); }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showGithubTokenDialog() {
        final RemoteKotlinCompiler rkc = new RemoteKotlinCompiler(this, null);
        final EditText et = new EditText(this);
        et.setHint("ghp_xxxxxxxxxxxx");
        et.setSingleLine(true);
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_TEXT_DIM);
        String existing = getSharedPreferences("github", MODE_PRIVATE).getString("token", "");
        if (existing != null && existing.length() > 0) et.setText(existing);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("GitHub Token")
            .setMessage("Needs 'repo' + 'workflow' scope. Used only for remote Kotlin builds.")
            .setView(et)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    rkc.setToken(et.getText().toString());
                    toast("Token saved");
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_new_project)        { showNewProjectDialog(); return true; }
        else if (id == R.id.action_toggle_drawer) {
            if (sidebarOpen) closeSidebar();
            else if (projectRoot == null) pickProjectFolder();
            else openSidebar();
            return true;
        }
        else if (id == R.id.action_build)         { runBuild(); return true; }
        else if (id == R.id.action_open)          { pickProjectFolder(); return true; }
        else if (id == R.id.action_save)          { saveFile(); return true; }
        else if (id == R.id.action_save_as)       { saveFileAs(); return true; }
        else if (id == R.id.action_refresh)       { reloadFileTree(); return true; }
        else if (id == R.id.action_undo)          { flushActiveTab(); undoMgr.undo(); return true; }
        else if (id == R.id.action_redo)          { flushActiveTab(); undoMgr.redo(); return true; }
        else if (id == R.id.action_find)          { new FindReplaceDialog(this, editor).show(); return true; }
        else if (id == R.id.action_libraries)     { showLibraries(); return true; }
        else if (id == R.id.action_logcat)        { showLogcat(); return true; }
        else if (id == R.id.action_theme)         { toggleTheme(); return true; }
        else if (id == R.id.action_kotlin_mode)   { showKotlinMode(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void openSidebar() {
        if (sidebarOpen && sidebar.getVisibility() == View.VISIBLE) return;
        sidebarOpen = true;
        sidebar.clearAnimation();
        dimLayer.clearAnimation();
        sidebar.setVisibility(View.VISIBLE);
        dimLayer.setVisibility(View.VISIBLE);
        TranslateAnimation anim = new TranslateAnimation(
            Animation.RELATIVE_TO_SELF, -1f, Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 0f,  Animation.RELATIVE_TO_SELF, 0f);
        anim.setDuration(200);
        sidebar.startAnimation(anim);
    }

    private void closeSidebar() {
        if (!sidebarOpen) return;
        sidebarOpen = false;
        sidebar.clearAnimation();
        dimLayer.clearAnimation();
        dimLayer.setVisibility(View.GONE);
        TranslateAnimation anim = new TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0f,  Animation.RELATIVE_TO_SELF, -1f,
            Animation.RELATIVE_TO_SELF, 0f,  Animation.RELATIVE_TO_SELF, 0f);
        anim.setDuration(200);
        anim.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationStart(Animation a) {}
                @Override public void onAnimationRepeat(Animation a) {}
                @Override public void onAnimationEnd(Animation a) {
                    if (!sidebarOpen) {
                        sidebar.setVisibility(View.GONE);
                        sidebar.clearAnimation();
                    }
                }
            });
        sidebar.startAnimation(anim);
    }

    private void reloadFileTree() {
        if (projectRoot == null) { toast("No project folder selected"); return; }
        try {
            fileNodes.clear();
            buildTree(projectRoot, 0);
            fileAdapter.notifyDataSetChanged();
            txtProjectPath.setText(projectRoot.getAbsolutePath());
            toast("Loaded " + fileNodes.size() + " items");
        } catch (Exception e) {
            toast("Load failed: " + e.getMessage());
        }
    }

    private void buildTree(File dir, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
        for (File f : kids) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            fileNodes.add(new FileNode(n, f.isDirectory(), null, depth, f));
            if (f.isDirectory() && depth < 5) buildTree(f, depth + 1);
        }
    }

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQ_MANAGE_STORAGE);
                } catch (Throwable t) {
                    try {
                        startActivityForResult(
                            new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            REQ_MANAGE_STORAGE);
                    } catch (Throwable ignored) {}
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                                       android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                       android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                   }, REQ_MANAGE_STORAGE);
            }
        }
    }

    private interface FolderCallback { void onChosen(File folder); }

    private void pickProjectFolder() {
        File startTmp = Environment.getExternalStorageDirectory();
        if (!startTmp.exists() || !startTmp.canRead()) startTmp = getFilesDir();
        showFolderPicker(startTmp, new FolderCallback() {
                @Override public void onChosen(File folder) {
                    File root = findProjectRoot(folder);
                    if (root == null) { toast("No AndroidManifest.xml found"); return; }
                    projectRoot = root;
                    txtProjectPath.setText(root.getAbsolutePath());
                    reloadFileTree();
                    openSidebar();
                    toast("Project: " + root.getName());
                }
            });
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }

    private void showFolderPicker(final File startDir, final FolderCallback cb) {
        final File[] currentDir = new File[]{ startDir };

        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_SURFACE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(C_BG);
        header.setPadding(dp(16), dp(14), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("Open Folder");
        title.setTextColor(C_TEXT);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        final TextView breadcrumb = new TextView(this);
        breadcrumb.setTextColor(C_ACCENT2);
        breadcrumb.setTextSize(11f);
        breadcrumb.setPadding(0, dp(4), 0, 0);
        breadcrumb.setSingleLine(true);
        header.addView(breadcrumb);

        View sep = new View(this);
        sep.setBackgroundColor(C_DIVIDER);
        header.addView(sep, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        root.addView(header);

        final ListView list = new ListView(this);
        list.setBackgroundColor(C_SURFACE);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(0, dp(4), 0, dp(4));
        list.setClipToPadding(false);

        final List<File> entries = new ArrayList<File>();
        final List<String> entryLabels = new ArrayList<String>();
        final List<Boolean> entryDirs = new ArrayList<Boolean>();

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int i) { return entries.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View reuse, ViewGroup parent) {
                LinearLayout row;
                if (reuse instanceof LinearLayout) {
                    row = (LinearLayout) reuse;
                    row.removeAllViews();
                } else {
                    row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(16), dp(12), dp(16), dp(12));
                }

                TextView icon = new TextView(MainActivity.this);
                icon.setText(entryDirs.get(pos) ? "📁" : "📄");
                icon.setTextSize(16f);
                icon.setPadding(0, 0, dp(12), 0);
                row.addView(icon);

                TextView name = new TextView(MainActivity.this);
                name.setText(entryLabels.get(pos));
                name.setTextColor(entryDirs.get(pos) ? C_FOLDER : C_TEXT);
                name.setTextSize(14f);
                name.setSingleLine(true);
                name.setTypeface(Typeface.MONOSPACE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(name, lp);

                if (entryDirs.get(pos)) {
                    TextView arrow = new TextView(MainActivity.this);
                    arrow.setText("›");
                    arrow.setTextColor(C_TEXT_DIM);
                    arrow.setTextSize(18f);
                    row.addView(arrow);
                }

                return row;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                    File picked = entries.get(pos);
                    if (picked.isDirectory()) {
                        currentDir[0] = picked;
                        refreshFolderList(currentDir[0], entries, entryLabels, entryDirs, adapter, breadcrumb);
                    }
                }
            });

        root.addView(list, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(C_SURFACE2);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));

        bar.addView(makeBarButton("↑ Up", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        File parent = currentDir[0].getParentFile();
                        if (parent != null) {
                            currentDir[0] = parent;
                            refreshFolderList(currentDir[0], entries, entryLabels, entryDirs, adapter, breadcrumb);
                        }
                    }
                }));

        bar.addView(makeBarButton("+ New", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        promptNewFolder(currentDir[0], new Runnable() {
                                @Override public void run() {
                                    refreshFolderList(currentDir[0], entries, entryLabels, entryDirs, adapter, breadcrumb);
                                }
                            });
                    }
                }));

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(makeBarButton("Cancel", new View.OnClickListener() {
                    @Override public void onClick(View v) { dlg.dismiss(); }
                }));

        bar.addView(makeAccentButton("Use this folder", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        File chosen = currentDir[0];
                        dlg.dismiss();
                        cb.onChosen(chosen);
                    }
                }));

        root.addView(bar);

        dlg.setContentView(root);
        dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dlg.getWindow().setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);

        refreshFolderList(currentDir[0], entries, entryLabels, entryDirs, adapter, breadcrumb);
        dlg.show();
    }

    private void refreshFolderList(File dir,
                                   List<File> entries,
                                   List<String> labels,
                                   List<Boolean> dirs,
                                   BaseAdapter adapter,
                                   TextView breadcrumb) {
        entries.clear();
        labels.clear();
        dirs.clear();

        breadcrumb.setText(dir.getAbsolutePath());

        File[] kids = dir.listFiles();
        if (kids == null) kids = new File[0];

        Arrays.sort(kids, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

        for (File f : kids) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (!f.isDirectory()) continue;
            entries.add(f);
            labels.add(n);
            dirs.add(f.isDirectory());
        }

        adapter.notifyDataSetChanged();
    }

    private TextView makeBarButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_TEXT);
        tv.setTextSize(13f);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView makeAccentButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(C_ACCENT);
        tv.setOnClickListener(l);
        return tv;
    }

    private void promptNewFolder(final File parent, final Runnable after) {
        final EditText et = new EditText(this);
        et.setHint("folder name");
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_TEXT_DIM);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(16), dp(8), dp(16), dp(8));
        wrap.setBackgroundColor(C_SURFACE);
        wrap.addView(et, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("New folder in " + parent.getName())
            .setView(wrap)
            .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String n = et.getText().toString().trim();
                    if (n.length() > 0) new File(parent, n).mkdirs();
                    if (after != null) after.run();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Detects the project root.
     * Priority:
     *   1. Picked dir has AndroidManifest.xml AND res/ AND src/ (Gradle-ish flat)
     *   2. Picked/app/src/main  (Gradle module with main source set)
     *   3. Picked/src/main
     *   4. Picked/src
     *   5. Picked/app (fallback)
     *   6. Any immediate subdir matching the above
     */
    private File findProjectRoot(File picked) {
        File direct = matchRoot(picked);
        if (direct != null) return direct;

        File gradleMain = new File(picked, "app/src/main");
        if (matchRoot(gradleMain) != null) return gradleMain;

        File srcMain = new File(picked, "src/main");
        if (matchRoot(srcMain) != null) return srcMain;

        File srcFlat = new File(picked, "src");
        if (matchRoot(srcFlat) != null) return srcFlat;

        File appDir = new File(picked, "app");
        if (matchRoot(appDir) != null) return appDir;

        File[] kids = picked.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (!k.isDirectory()) continue;

                File r = matchRoot(k);
                if (r != null) return r;

                File k1 = new File(k, "app/src/main");
                if (matchRoot(k1) != null) return k1;

                File k2 = new File(k, "src/main");
                if (matchRoot(k2) != null) return k2;

                File k3 = new File(k, "src");
                if (matchRoot(k3) != null) return k3;

                File k4 = new File(k, "app");
                if (matchRoot(k4) != null) return k4;
            }
        }
        return null;
    }

    /**
     * Returns dir if it looks like a real Android project root:
     *   has AndroidManifest.xml
     *   has res/
     *   has src/ OR java/
     */
    private File matchRoot(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File manifest = new File(dir, "AndroidManifest.xml");
        File res = new File(dir, "res");
        File src = new File(dir, "src");
        File javaDir = new File(dir, "java");
        if (manifest.isFile() && res.isDirectory()
            && (src.isDirectory() || javaDir.isDirectory())) {
            return dir;
        }
        return null;
    }

    private void showNewProjectDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_SURFACE);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(label("App Name"));
        final EditText nameEt = new EditText(this);
        nameEt.setHint("MyApp");
        nameEt.setTextColor(C_TEXT);
        nameEt.setHintTextColor(C_TEXT_DIM);
        root.addView(nameEt);

        root.addView(label("Package"));
        final EditText pkgEt = new EditText(this);
        pkgEt.setText("com.example.myapp");
        pkgEt.setTextColor(C_TEXT);
        root.addView(pkgEt);

        root.addView(label("Location"));
        final TextView locTv = new TextView(this);
        locTv.setText("(not selected)");
        locTv.setTextColor(C_WARN);
        locTv.setPadding(0, 4, 0, 4);
        root.addView(locTv);

        final File[] chosen = new File[]{null};
        Button pick = new Button(this);
        pick.setText("Choose folder");
        pick.setAllCaps(false);
        pick.setTextColor(C_TEXT);
        pick.setBackgroundColor(C_ACCENT);
        pick.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    File startTmp = Environment.getExternalStorageDirectory();
                    if (!startTmp.exists() || !startTmp.canRead()) startTmp = getFilesDir();
                    showFolderPicker(startTmp, new FolderCallback() {
                            @Override public void onChosen(File folder) {
                                chosen[0] = folder;
                                locTv.setText(folder.getAbsolutePath());
                                locTv.setTextColor(C_OK);
                            }
                        });
                }
            });
        root.addView(pick);

        final AlertDialog dlg = new AlertDialog.Builder(this,
                                                        android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Create New Project")
            .setView(root)
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create();

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override public void onShow(DialogInterface d) {
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                String name = nameEt.getText().toString().trim();
                                String pkg = pkgEt.getText().toString().trim();
                                if (name.length() == 0) { toast("App name required"); return; }
                                if (!pkg.contains(".") || pkg.contains(" ")) {
                                    toast("Invalid package name"); return;
                                }
                                if (chosen[0] == null) { toast("Pick a location"); return; }
                                File proj = new File(chosen[0], name);
                                if (proj.exists()) { toast("Already exists: " + name); return; }
                                File created = createProject(proj, name, pkg);
                                if (created == null) return;
                                dlg.dismiss();
                                projectRoot = created;
                                txtProjectPath.setText(created.getAbsolutePath());
                                reloadFileTree();
                                openSidebar();
                                String pkgPath = pkg.replace('.', '/');
                                File main = new File(created, "src/" + pkgPath + "/MainActivity.java");
                                if (main.exists()) openFileInTab(main);
                                toast("Project created: " + name);
                            }
                        });
                }
            });
        dlg.show();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_FOLDER);
        int mt = dp(10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = mt;
        tv.setLayoutParams(lp);
        return tv;
    }

    private File createProject(File projDir, String appName, String pkg) {
        try {
            String pkgPath = pkg.replace('.', '/');
            File srcDir      = new File(projDir, "src/" + pkgPath);
            File resLayout   = new File(projDir, "res/layout");
            File resValues   = new File(projDir, "res/values");
            File resDrawable = new File(projDir, "res/drawable");
            srcDir.mkdirs();
            resLayout.mkdirs();
            resValues.mkdirs();
            resDrawable.mkdirs();

            writeText(new File(projDir, "AndroidManifest.xml"),
                      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                      "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                      "    package=\"" + pkg + "\">\n\n" +
                      "    <uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"34\" />\n\n" +
                      "    <application\n" +
                      "        android:allowBackup=\"true\"\n" +
                      "        android:label=\"@string/app_name\"\n" +
                      "        android:icon=\"@drawable/ic_launcher\"\n" +
                      "        android:theme=\"@android:style/Theme.Material.Light\">\n" +
                      "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n" +
                      "            <intent-filter>\n" +
                      "                <action android:name=\"android.intent.action.MAIN\" />\n" +
                      "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
                      "            </intent-filter>\n" +
                      "        </activity>\n" +
                      "    </application>\n" +
                      "</manifest>\n");

            writeText(new File(srcDir, "MainActivity.java"),
                      "package " + pkg + ";\n\n" +
                      "import android.app.Activity;\n" +
                      "import android.os.Bundle;\n\n" +
                      "public class MainActivity extends Activity {\n" +
                      "    @Override\n" +
                      "    protected void onCreate(Bundle savedInstanceState) {\n" +
                      "        super.onCreate(savedInstanceState);\n" +
                      "        setContentView(R.layout.main);\n" +
                      "    }\n" +
                      "}\n");

            writeText(new File(resValues, "strings.xml"),
                      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                      "<resources>\n" +
                      "    <string name=\"app_name\">" + escapeXml(appName) + "</string>\n" +
                      "    <string name=\"hello\">Hello from " + escapeXml(appName) + "!</string>\n" +
                      "</resources>\n");

            writeText(new File(resLayout, "main.xml"),
                      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                      "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                      "    android:layout_width=\"match_parent\"\n" +
                      "    android:layout_height=\"match_parent\"\n" +
                      "    android:orientation=\"vertical\"\n" +
                      "    android:gravity=\"center\">\n\n" +
                      "    <TextView\n" +
                      "        android:layout_width=\"wrap_content\"\n" +
                      "        android:layout_height=\"wrap_content\"\n" +
                      "        android:text=\"@string/hello\"\n" +
                      "        android:textSize=\"20sp\" />\n\n" +
                      "</LinearLayout>\n");

            writeText(new File(resDrawable, "ic_launcher.xml"),
                      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                      "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                      "    android:width=\"108dp\" android:height=\"108dp\"\n" +
                      "    android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n" +
                      "    <path android:fillColor=\"#3F51B5\" android:pathData=\"M0,0h108v108h-108z\" />\n" +
                      "    <path android:fillColor=\"#FFFFFF\" android:pathData=\"M54,24 L84,84 L24,84 Z\" />\n" +
                      "</vector>\n");

            return projDir;
        } catch (Exception e) {
            toast("Create failed: " + e.getMessage());
            return null;
        }
    }

    private void writeText(File f, String content) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void runBuild() {
        if (projectRoot == null) { toast("Pick or create a project first"); return; }
        flushActiveTab();
        startBuild();
    }

    private void startBuild() {
        final EditText minEt = new EditText(this);
        minEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minEt.setText("21");
        minEt.setHint("min SDK (e.g. 21)");
        minEt.setTextColor(C_TEXT);
        minEt.setHintTextColor(C_TEXT_DIM);

        final EditText targetEt = new EditText(this);
        targetEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        targetEt.setText("34");
        targetEt.setHint("target SDK (e.g. 34)");
        targetEt.setTextColor(C_TEXT);
        targetEt.setHintTextColor(C_TEXT_DIM);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(C_SURFACE);
        int pad = dp(16);
        layout.setPadding(pad, pad, pad, pad);

        TextView minLbl = new TextView(this);
        minLbl.setText("Minimum SDK:");
        minLbl.setTextColor(C_FOLDER);
        layout.addView(minLbl);
        layout.addView(minEt);

        TextView targetLbl = new TextView(this);
        targetLbl.setText("Target SDK:");
        targetLbl.setTextColor(C_FOLDER);
        layout.addView(targetLbl);
        layout.addView(targetEt);

        TextView hint = new TextView(this);
        hint.setText("Common: min 21, target 34 or 35");
        hint.setTextColor(C_TEXT_DIM);
        hint.setTextSize(11f);
        layout.addView(hint);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Build APK")
            .setMessage("Project: " + projectRoot.getName())
            .setView(layout)
            .setPositiveButton("Build", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    int min, target;
                    try { min = Integer.parseInt(minEt.getText().toString().trim()); }
                    catch (Exception e) { min = 21; }
                    try { target = Integer.parseInt(targetEt.getText().toString().trim()); }
                    catch (Exception e) { target = 34; }
                    if (min < 1) min = 21;
                    if (target < min) target = min;
                    doBuild(min, target);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void doBuild(final int minSdk, final int targetSdk) {
        final AlertDialog dlg = new AlertDialog.Builder(this,
                                                        android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Building...")
            .setMessage("Starting")
            .setCancelable(false)
            .create();
        dlg.show();

        new Thread(new Runnable() {
                @Override public void run() {
                    ApkBuilder builder = new ApkBuilder(MainActivity.this,
                        new ApkBuilder.Progress() {
                            @Override public void onProgress(final String msg) {
                                runOnUiThread(new Runnable() {
                                        @Override public void run() { dlg.setMessage(msg); }
                                    });
                            }
                        });
                    final ApkBuilder.Result r = builder.build(projectRoot, minSdk, targetSdk);
                    runOnUiThread(new Runnable() {
                            @Override public void run() {
                                dlg.dismiss();
                                if (r.success) {
                                    askWhereToSave(r.apk);
                                } else {
                                    new AlertDialog.Builder(MainActivity.this,
                                                            android.R.style.Theme_Material_Dialog_Alert)
                                        .setTitle("\u274C BUILD FAILED")
                                        .setMessage(r.log)
                                        .setPositiveButton("OK", null)
                                        .show();
                                }
                            }
                        });
                }
            }).start();
    }

    private void askWhereToSave(final File apk) {
        File startTmp = Environment.getExternalStorageDirectory();
        if (!startTmp.exists() || !startTmp.canRead()) startTmp = getFilesDir();
        showFolderPicker(startTmp, new FolderCallback() {
                @Override public void onChosen(File folder) {
                    File saved = copyApkToFolder(apk, folder);
                    if (saved != null) askInstallOrClose(saved);
                }
            });
    }

    private void askInstallOrClose(final File saved) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("\u2705 Build Successful")
            .setMessage("APK saved to:\n" + saved.getAbsolutePath())
            .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { installApk(saved); }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private File copyApkToFolder(File apk, File folder) {
        try {
            String baseName = "app.apk";
            if (projectRoot != null) {
                File mf = new File(projectRoot, "AndroidManifest.xml");
                if (mf.exists()) {
                    String xml = readTextFile(mf);
                    int i = xml.indexOf("package=\"");
                    if (i >= 0) {
                        int s = i + 9;
                        int e = xml.indexOf('"', s);
                        if (e > s) baseName = xml.substring(s, e) + ".apk";
                    }
                }
            }
            File dest = new File(folder, baseName);
            int n = 1;
            while (dest.exists()) {
                dest = new File(folder, baseName.replace(".apk", "_" + n + ".apk"));
                n++;
            }
            FileInputStream in = new FileInputStream(apk);
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            in.close();
            out.close();
            return dest;
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
            return null;
        }
    }

    private void installApk(File apk) {
        try {
            File publicApk = new File(Environment.getExternalStorageDirectory(),
                                      "Download/MyIDE-install.apk");
            File parent = publicApk.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileInputStream in = new FileInputStream(apk);
            FileOutputStream out = new FileOutputStream(publicApk);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            in.close();
            out.close();
            toast("APK copied to Download. Open it to install.");
        } catch (Exception e) {
            toast("Copy failed: " + e.getMessage());
        }
    }

    private String readTextFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private void saveFile() {
        if (currentFile == null) { saveFileAs(); return; }
        try {
            flushActiveTab();
            FileOutputStream fos = new FileOutputStream(currentFile);
            fos.write(editor.getText().toString().getBytes("UTF-8"));
            fos.close();
            EditorTab t = tabs.active();
            if (t != null && t.file.getAbsolutePath().equals(currentFile.getAbsolutePath())) {
                t.text = editor.getText().toString();
                t.dirty = false;
                tabs.refreshTitles();
            }
            toast("Saved: " + currentFile.getName());
            updateStatusBar();
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    private void saveFileAs() {
        if (projectRoot == null) { toast("Pick or create a project first"); return; }
        final EditText et = new EditText(this);
        et.setHint("MainActivity.java");
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_TEXT_DIM);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Save as (in project src/)")
            .setView(et)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String name = et.getText().toString().trim();
                    if (name.length() == 0) return;
                    if (!name.endsWith(".java") && !name.endsWith(".kt")) name += ".java";
                    File dir = new File(projectRoot, "src");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, name);
                    currentFile = f;
                    saveFile();
                    reloadFileTree();
                    openFileInTab(f);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateLineNumbers(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append(i);
            if (i < lines) sb.append('\n');
        }
        lineNumbers.setText(sb.toString());
    }

    private void updateStatusBar() {
        if (txtStatusPos == null) return;
        int sel = editor.getSelectionStart();
        if (sel < 0) sel = 0;
        String text = editor.getText().toString();
        if (sel > text.length()) sel = text.length();
        int line = 1, col = 1;
        for (int i = 0; i < sel; i++) {
            if (text.charAt(i) == '\n') { line++; col = 1; }
            else col++;
        }
        txtStatusPos.setText("Ln " + line + ", Col " + col);
        txtStatusLang.setText(langName(currentLang()));
        if (currentFile != null) txtStatusLeft.setText(currentFile.getName());
        else txtStatusLeft.setText("Ready");
    }

    private String langName(int lang) {
        switch (lang) {
            case SyntaxHighlighter.LANG_XML: return "XML";
            case SyntaxHighlighter.LANG_JSON: return "JSON";
            case SyntaxHighlighter.LANG_GRADLE: return "Gradle";
            case SyntaxHighlighter.LANG_MARKDOWN: return "Markdown";
            case SyntaxHighlighter.LANG_KOTLIN: return "Kotlin";
            default: return "Java";
        }
    }

    private int currentLang() {
        String path = null;
        if (currentFile != null) path = currentFile.getAbsolutePath();
        else if (lastLoaded != null && lastLoaded.file != null)
            path = lastLoaded.file.getAbsolutePath();
        return SyntaxHighlighter.detectLang(path);
    }

    private void updateTitle() {
        if (txtTitle != null) {
            txtTitle.setText(currentFile != null
                ? "MyIDE — " + currentFile.getName()
                : "MyIDE");
        }
        setTitle("MyIDE" + (currentFile != null ? " \u2014 " + currentFile.getName() : ""));
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
    }

    @Override
    protected void onPause() {
        super.onPause();
        flushActiveTab();
    }
}