package com.fahim.myide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.AdapterView;
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

    private EditText editor;
    private TextView lineNumbers;
    private boolean isHighlighting = false;

    private TabManager tabs;
    private UndoManager undoMgr;

    private View sidebar;
    private View dimLayer;
    private ListView fileList;
    private TextView txtProjectPath;
    private Button btnOpenFolder;
    private boolean sidebarOpen = false;

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

        fileAdapter = new FileAdapter(this, fileNodes);
        fileList.setAdapter(fileAdapter);

        setupTabs();
        setupUndo();

        btnOpenFolder.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickProjectFolder(); }
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

        editor.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (isHighlighting) return;
                    isHighlighting = true;
                    SyntaxHighlighter.highlight(s, currentLang());
                    updateLineNumbers(s.toString());
                    isHighlighting = false;
                }
            });

        requestStoragePermissionIfNeeded();
        updateTitle();
    }

    // =========================================================
    //  Theme
    // =========================================================

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

    // =========================================================
    //  tabs + undo setup
    // =========================================================

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
    }

    private void flushTab(EditorTab t) {
        if (t == null || t != lastLoaded) return;
        String now = editor.getText().toString();
        if (!now.equals(t.text)) {
            t.text = now;
            t.dirty = true;
        }
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
        } catch (Exception e) {
            toast("Open failed: " + e.getMessage());
        }
    }

    private void closeTabsFor(File f) {
        String abs = f.getAbsolutePath();
        for (int i = tabs.getTabs().size() - 1; i >= 0; i--) {
            File tf = tabs.getTabs().get(i).file;
            String tfa = tf.getAbsolutePath();
            if (tfa.equals(abs) || tfa.startsWith(abs + "/")) {
                tabs.close(i);
            }
        }
    }

    // =========================================================
    //  file operations
    // =========================================================

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

    // =========================================================
    //  Logcat
    // =========================================================

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

    // =========================================================
    //  Menu
    // =========================================================

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
        else if (id == R.id.action_logcat)        { showLogcat(); return true; }
        else if (id == R.id.action_theme)         { toggleTheme(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // =========================================================
    //  Sidebar
    // =========================================================

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

    // =========================================================
    //  Storage permission
    // =========================================================

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

    // =========================================================
    //  Folder picking
    // =========================================================

    private void pickProjectFolder() {
        File startTmp = Environment.getExternalStorageDirectory();
        if (!startTmp.exists() || !startTmp.canRead()) startTmp = getFilesDir();
        final File start = startTmp;
        showFolderPicker(start, new FolderCallback() {
                @Override public void onChosen(File folder) {
                    File root = findProjectRoot(folder);
                    if (root == null) {
                        toast("No AndroidManifest.xml found");
                        return;
                    }
                    projectRoot = root;
                    txtProjectPath.setText(root.getAbsolutePath());
                    reloadFileTree();
                    openSidebar();
                    toast("Project: " + root.getName());
                }
            });
    }

    private interface FolderCallback { void onChosen(File folder); }

    private void showFolderPicker(final File dir, final FolderCallback cb) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this,
                                                                    android.R.style.Theme_Material_Dialog_Alert);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(0xFF1E1E1E);
        int pad = (int)(12 * getResources().getDisplayMetrics().density);
        header.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("\uD83D\uDCC1  " + (dir.getName().length() == 0 ? "Storage" : dir.getName()));
        title.setTextColor(0xFFDCDCAA);
        title.setTextSize(16f);
        header.addView(title);

        TextView path = new TextView(this);
        path.setText(dir.getAbsolutePath());
        path.setTextColor(0xFF858585);
        path.setTextSize(11f);
        path.setPadding(0, 4, 0, 0);
        header.addView(path);

        final File[] children = dir.listFiles();
        final List<File> folders = new ArrayList<File>();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory() && !f.getName().startsWith(".")) folders.add(f);
            }
        }
        final File[] sorted = folders.toArray(new File[0]);
        Arrays.sort(sorted, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

        final String[] names = new String[sorted.length + 3];
        names[0] = "\u2705  Use this folder";
        names[1] = "\u2795  New folder here";
        names[2] = "\u2B06\uFE0F  .. (up)";
        for (int i = 0; i < sorted.length; i++) names[i + 3] = "\uD83D\uDCC1  " + sorted[i].getName();

        builder.setCustomTitle(header);
        builder.setItems(names, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    if (which == 0) { cb.onChosen(dir); return; }
                    if (which == 1) {
                        final EditText et = new EditText(MainActivity.this);
                        et.setHint("folder name");
                        et.setTextColor(0xFFD4D4D4);
                        et.setHintTextColor(0xFF666666);
                        new AlertDialog.Builder(MainActivity.this,
                                                android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("\u2795  New folder in " + dir.getName())
                            .setView(et)
                            .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dd, int ww) {
                                    String n = et.getText().toString().trim();
                                    if (n.length() > 0) new File(dir, n).mkdirs();
                                    showFolderPicker(dir, cb);
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                        return;
                    }
                    if (which == 2) {
                        File parent = dir.getParentFile();
                        showFolderPicker(parent != null ? parent : dir, cb);
                        return;
                    }
                    showFolderPicker(sorted[which - 3], cb);
                }
            });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private File findProjectRoot(File picked) {
        if (new File(picked, "AndroidManifest.xml").exists()) return picked;

        File a = new File(picked, "app/src/main");
        if (new File(a, "AndroidManifest.xml").exists()) return a;
        File b = new File(picked, "src/main");
        if (new File(b, "AndroidManifest.xml").exists()) return b;
        File c = new File(picked, "src");
        if (new File(c, "AndroidManifest.xml").exists()) return c;

        File[] kids = picked.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (!k.isDirectory()) continue;
                if (new File(k, "AndroidManifest.xml").exists()) return k;
                File k1 = new File(k, "app/src/main");
                if (new File(k1, "AndroidManifest.xml").exists()) return k1;
                File k2 = new File(k, "src/main");
                if (new File(k2, "AndroidManifest.xml").exists()) return k2;
                File k3 = new File(k, "src");
                if (new File(k3, "AndroidManifest.xml").exists()) return k3;
            }
        }
        return null;
    }

    // =========================================================
    //  New project
    // =========================================================

    private void showNewProjectDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        root.addView(label("App Name"));
        final EditText nameEt = new EditText(this);
        nameEt.setHint("MyApp");
        root.addView(nameEt);

        root.addView(label("Package"));
        final EditText pkgEt = new EditText(this);
        pkgEt.setText("com.example.myapp");
        root.addView(pkgEt);

        root.addView(label("Location"));
        final TextView locTv = new TextView(this);
        locTv.setText("(not selected)");
        locTv.setTextColor(0xFFFF5555);
        locTv.setPadding(0, 4, 0, 4);
        root.addView(locTv);

        final File[] chosen = new File[]{null};
        Button pick = new Button(this);
        pick.setText("Choose folder");
        pick.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    File startTmp = Environment.getExternalStorageDirectory();
                    if (!startTmp.exists() || !startTmp.canRead()) startTmp = getFilesDir();
                    final File start = startTmp;
                    showFolderPicker(start, new FolderCallback() {
                            @Override public void onChosen(File folder) {
                                chosen[0] = folder;
                                locTv.setText(folder.getAbsolutePath());
                                locTv.setTextColor(0xFF66FF66);
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
        tv.setTextColor(0xFFDCDCAA);
        int mt = (int)(10 * getResources().getDisplayMetrics().density);
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

    // =========================================================
    //  Build
    // =========================================================

    private void runBuild() {
        if (projectRoot == null) {
            toast("Pick or create a project first");
            return;
        }
        flushActiveTab();
        startBuild();
    }

    private void startBuild() {
        final EditText minEt = new EditText(this);
        minEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minEt.setText("21");
        minEt.setHint("min SDK (e.g. 21)");

        final EditText targetEt = new EditText(this);
        targetEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        targetEt.setText("34");
        targetEt.setHint("target SDK (e.g. 34)");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView minLbl = new TextView(this);
        minLbl.setText("Minimum SDK:");
        minLbl.setTextColor(0xFFDCDCAA);
        layout.addView(minLbl);
        layout.addView(minEt);

        TextView targetLbl = new TextView(this);
        targetLbl.setText("Target SDK:");
        targetLbl.setTextColor(0xFFDCDCAA);
        layout.addView(targetLbl);
        layout.addView(targetEt);

        TextView hint = new TextView(this);
        hint.setText("Common: min 21, target 34 or 35");
        hint.setTextColor(0xFF858585);
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

    // =========================================================
    //  Save APK
    // =========================================================

    private void askWhereToSave(final File apk) {
        File startTmp = Environment.getExternalStorageDirectory();
        if (!startTmp.exists() || !startTmp.canRead()) startTmp = getFilesDir();
        final File start = startTmp;
        showFolderPicker(start, new FolderCallback() {
                @Override public void onChosen(File folder) {
                    File saved = copyApkToFolder(apk, folder);
                    if (saved != null) {
                        askInstallOrClose(saved);
                    }
                }
            });
    }

    private void askInstallOrClose(final File saved) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("\u2705 Build Successful")
            .setMessage("APK saved to:\n" + saved.getAbsolutePath())
            .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    installApk(saved);
                }
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

    // =========================================================
    //  Save / load
    // =========================================================

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
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    private void saveFileAs() {
        if (projectRoot == null) { toast("Pick or create a project first"); return; }
        final EditText et = new EditText(this);
        et.setHint("MainActivity.java");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Save as (in project src/)")
            .setView(et)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String name = et.getText().toString().trim();
                    if (name.length() == 0) return;
                    if (!name.endsWith(".java")) name += ".java";
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

    // =========================================================
    //  Misc
    // =========================================================

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

    private int currentLang() {
        String path = null;
        if (currentFile != null) path = currentFile.getAbsolutePath();
        else if (lastLoaded != null && lastLoaded.file != null)
            path = lastLoaded.file.getAbsolutePath();
        return SyntaxHighlighter.detectLang(path);
    }

    private void updateTitle() {
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