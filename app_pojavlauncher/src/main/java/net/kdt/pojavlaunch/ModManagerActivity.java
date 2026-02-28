package net.kdt.pojavlaunch.modmanager;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/**
 * Мод-менеджер для unseriouslauncher (форк MojoLauncher)
 * Использует Modrinth API — бесплатно, без ключа
 */
public class ModManagerActivity extends AppCompatActivity {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final String PREFS_NAME = "mod_manager_prefs";
    private static final String PREFS_INSTALLED = "installed_mods";

    private RecyclerView recyclerView;
    private ModAdapter adapter;
    private EditText searchField;
    private ProgressBar progressBar;
    private TextView emptyText;
    private TabHost tabHost;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<ModInfo> currentMods = new ArrayList<>();
    private boolean showingInstalled = false;

    // Папка модов внутри директории лаунчера
    private File modsDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Получаем путь к папке игры из Intent или используем стандартный
        String gamePath = getIntent().getStringExtra("game_dir");
        if (gamePath == null) {
            gamePath = getExternalFilesDir(null) + "/.minecraft";
        }
        modsDir = new File(gamePath + "/mods");
        if (!modsDir.exists()) modsDir.mkdirs();

        setupUI();
        loadBrowseMods("");
    }

    private void setupUI() {
        // Создаём layout программно (без XML)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);

        // Toolbar
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setBackgroundColor(0xFF1B5E20);
        toolbar.setPadding(32, 32, 32, 32);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Мод-менеджер");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbar.addView(title);

        root.addView(toolbar);

        // Табы
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(0xFF1B5E20);

        Button tabBrowse = makeTabButton("Обзор", true);
        Button tabInstalled = makeTabButton("Установленные", false);

        tabBrowse.setOnClickListener(v -> {
            showingInstalled = false;
            tabBrowse.setBackgroundColor(0xFF2E7D32);
            tabInstalled.setBackgroundColor(0x00000000);
            searchField.setVisibility(View.VISIBLE);
            loadBrowseMods(searchField.getText().toString());
        });
        tabInstalled.setOnClickListener(v -> {
            showingInstalled = true;
            tabInstalled.setBackgroundColor(0xFF2E7D32);
            tabBrowse.setBackgroundColor(0x00000000);
            searchField.setVisibility(View.GONE);
            loadInstalledMods();
        });

        tabs.addView(tabBrowse);
        tabs.addView(tabInstalled);
        root.addView(tabs);

        // Поиск
        searchField = new EditText(this);
        searchField.setHint("Поиск модов...");
        searchField.setHintTextColor(0xFF888888);
        searchField.setTextColor(0xFFFFFFFF);
        searchField.setBackgroundColor(0xFF1E1E1E);
        searchField.setPadding(32, 24, 32, 24);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchField.setLayoutParams(searchParams);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            loadBrowseMods(searchField.getText().toString());
            return true;
        });
        root.addView(searchField);

        // Прогресс
        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        progressBar.setLayoutParams(pbParams);
        root.addView(progressBar);

        // Пустой список
        emptyText = new TextView(this);
        emptyText.setText("Ничего не найдено");
        emptyText.setTextColor(0xFF888888);
        emptyText.setTextSize(16);
        emptyText.setGravity(android.view.Gravity.CENTER);
        emptyText.setVisibility(View.GONE);
        root.addView(emptyText);

        // Список модов
        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ModAdapter();
        recyclerView.setAdapter(adapter);
        LinearLayout.LayoutParams rvParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        recyclerView.setLayoutParams(rvParams);
        root.addView(recyclerView);

        setContentView(root);
    }

    private Button makeTabButton(String text, boolean active) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(active ? 0xFF2E7D32 : 0x00000000);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        btn.setLayoutParams(p);
        return btn;
    }

    // ============ Загрузка модов с Modrinth ============

    private void loadBrowseMods(String query) {
        showLoading(true);
        executor.submit(() -> {
            List<ModInfo> mods = fetchFromModrinth(query);
            runOnUiThread(() -> {
                showLoading(false);
                currentMods.clear();
                currentMods.addAll(mods);
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(mods.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private List<ModInfo> fetchFromModrinth(String query) {
        List<ModInfo> result = new ArrayList<>();
        try {
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String urlStr = MODRINTH_API + "/search?query=" + encodedQuery
                    + "&facets=[[\"project_type:mod\"]]&limit=20";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "unseriouslauncher/1.0");
            conn.connect();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            JSONArray hits = json.getJSONArray("hits");

            Set<String> installedIds = getInstalledIds();

            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                ModInfo mod = new ModInfo();
                mod.id = hit.getString("project_id");
                mod.name = hit.getString("title");
                mod.description = hit.getString("description");
                mod.author = hit.getString("author");
                mod.downloads = hit.getLong("downloads");
                mod.iconUrl = hit.optString("icon_url", "");
                mod.isInstalled = installedIds.contains(mod.id);
                result.add(mod);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private void loadInstalledMods() {
        currentMods.clear();
        currentMods.addAll(getInstalledModsList());
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(currentMods.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ============ Установка мода ============

    private void installMod(ModInfo mod) {
        showLoading(true);
        executor.submit(() -> {
            try {
                // Получаем список версий с Modrinth
                URL url = new URL(MODRINTH_API + "/project/" + mod.id + "/version");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "unseriouslauncher/1.0");
                conn.connect();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONArray versions = new JSONArray(sb.toString());
                if (versions.length() == 0) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        toast("Нет доступных версий");
                    });
                    return;
                }

                // Берём последнюю версию
                JSONObject latest = versions.getJSONObject(0);
                JSONArray files = latest.getJSONArray("files");
                if (files.length() == 0) {
                    runOnUiThread(() -> { showLoading(false); toast("Нет файлов"); });
                    return;
                }

                String downloadUrl = files.getJSONObject(0).getString("url");
                String filename = files.getJSONObject(0).getString("filename");
                String versionName = latest.getString("version_number");

                // Скачиваем через DownloadManager
                String finalFilename = filename;
                String finalDownloadUrl = downloadUrl;
                String finalVersionName = versionName;
                runOnUiThread(() -> {
                    showLoading(false);
                    downloadMod(mod, finalDownloadUrl, finalFilename, finalVersionName);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> { showLoading(false); toast("Ошибка: " + e.getMessage()); });
            }
        });
    }

    private void downloadMod(ModInfo mod, String downloadUrl, String filename, String version) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle(mod.name);
            request.setDescription("Скачивание мода...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationUri(Uri.fromFile(new File(modsDir, filename)));
            dm.enqueue(request);

            // Сохраняем в список установленных
            mod.isInstalled = true;
            mod.isEnabled = true;
            mod.localFile = new File(modsDir, filename).getAbsolutePath();
            mod.version = version;
            saveInstalledMod(mod);

            adapter.notifyDataSetChanged();
            toast(mod.name + " скачивается...");
        } catch (Exception e) {
            toast("Ошибка скачивания: " + e.getMessage());
        }
    }

    // ============ Управление установленными модами ============

    private void toggleMod(ModInfo mod) {
        File file = new File(mod.localFile);
        if (!file.exists()) { toast("Файл не найден"); return; }

        File newFile;
        if (mod.isEnabled) {
            newFile = new File(mod.localFile + ".disabled");
            mod.isEnabled = false;
        } else {
            newFile = new File(mod.localFile.replace(".disabled", ""));
            mod.isEnabled = true;
        }
        file.renameTo(newFile);
        mod.localFile = newFile.getAbsolutePath();
        saveInstalledMod(mod);
        adapter.notifyDataSetChanged();
        toast(mod.name + (mod.isEnabled ? " включён" : " отключён"));
    }

    private void deleteMod(ModInfo mod) {
        new AlertDialog.Builder(this)
            .setTitle("Удалить мод?")
            .setMessage("Удалить " + mod.name + "?")
            .setPositiveButton("Удалить", (d, w) -> {
                new File(mod.localFile).delete();
                removeInstalledMod(mod.id);
                if (showingInstalled) loadInstalledMods();
                else {
                    mod.isInstalled = false;
                    adapter.notifyDataSetChanged();
                }
                toast(mod.name + " удалён");
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    // ============ SharedPreferences для хранения списка модов ============

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private Set<String> getInstalledIds() {
        Set<String> ids = new HashSet<>();
        for (ModInfo m : getInstalledModsList()) ids.add(m.id);
        return ids;
    }

    private List<ModInfo> getInstalledModsList() {
        List<ModInfo> list = new ArrayList<>();
        try {
            String json = getPrefs().getString(PREFS_INSTALLED, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ModInfo m = new ModInfo();
                m.id = o.getString("id");
                m.name = o.getString("name");
                m.description = o.optString("description", "");
                m.author = o.optString("author", "");
                m.version = o.optString("version", "");
                m.localFile = o.optString("localFile", "");
                m.isInstalled = true;
                m.isEnabled = o.optBoolean("isEnabled", true);
                list.add(m);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return list;
    }

    private void saveInstalledMod(ModInfo mod) {
        try {
            List<ModInfo> list = getInstalledModsList();
            list.removeIf(m -> m.id.equals(mod.id));
            list.add(mod);
            JSONArray arr = new JSONArray();
            for (ModInfo m : list) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("name", m.name);
                o.put("description", m.description);
                o.put("author", m.author);
                o.put("version", m.version);
                o.put("localFile", m.localFile);
                o.put("isEnabled", m.isEnabled);
                arr.put(o);
            }
            getPrefs().edit().putString(PREFS_INSTALLED, arr.toString()).apply();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void removeInstalledMod(String id) {
        try {
            List<ModInfo> list = getInstalledModsList();
            list.removeIf(m -> m.id.equals(id));
            JSONArray arr = new JSONArray();
            for (ModInfo m : list) {
                JSONObject o = new JSONObject();
                o.put("id", m.id); o.put("name", m.name);
                o.put("description", m.description); o.put("author", m.author);
                o.put("version", m.version); o.put("localFile", m.localFile);
                o.put("isEnabled", m.isEnabled);
                arr.put(o);
            }
            getPrefs().edit().putString(PREFS_INSTALLED, arr.toString()).apply();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ============ Вспомогательные методы ============

    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ============ Модель данных ============

    static class ModInfo {
        String id = "", name = "", description = "", author = "";
        String iconUrl = "", version = "", localFile = "";
        long downloads = 0;
        boolean isInstalled = false, isEnabled = true;
    }

    // ============ RecyclerView адаптер ============

    class ModAdapter extends RecyclerView.Adapter<ModAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, author, description, downloads, version;
            Button btnInstall, btnDelete, btnToggle;

            ViewHolder(View v) {
                super(v);
                name = v.findViewById(1001);
                author = v.findViewById(1002);
                description = v.findViewById(1003);
                downloads = v.findViewById(1004);
                version = v.findViewById(1005);
                btnInstall = v.findViewById(1006);
                btnDelete = v.findViewById(1007);
                btnToggle = v.findViewById(1008);
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // Создаём карточку мода программно
            LinearLayout card = new LinearLayout(ModManagerActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(0xFF1E1E1E);
            card.setPadding(32, 24, 32, 24);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(16, 8, 16, 8);
            card.setLayoutParams(cardParams);

            TextView name = makeText(18, 0xFFFFFFFF, true); name.setId(1001);
            TextView author = makeText(13, 0xFF888888, false); author.setId(1002);
            TextView desc = makeText(13, 0xFFCCCCCC, false); desc.setId(1003);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView dl = makeText(12, 0xFF666666, false); dl.setId(1004);
            TextView ver = makeText(12, 0xFF69F0AE, false); ver.setId(1005);

            card.addView(name);
            card.addView(author);
            card.addView(desc);
            card.addView(dl);
            card.addView(ver);

            LinearLayout btnRow = new LinearLayout(ModManagerActivity.this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, 16, 0, 0);

            Button btnInstall = new Button(ModManagerActivity.this);
            btnInstall.setId(1006);
            btnInstall.setText("Установить");
            btnInstall.setBackgroundColor(0xFF1B5E20);
            btnInstall.setTextColor(0xFFFFFFFF);

            Button btnToggle = new Button(ModManagerActivity.this);
            btnToggle.setId(1008);
            btnToggle.setText("Вкл/Выкл");
            btnToggle.setBackgroundColor(0xFF333333);
            btnToggle.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            toggleParams.setMargins(8, 0, 0, 0);
            btnToggle.setLayoutParams(toggleParams);

            Button btnDelete = new Button(ModManagerActivity.this);
            btnDelete.setId(1007);
            btnDelete.setText("Удалить");
            btnDelete.setBackgroundColor(0xFF7F0000);
            btnDelete.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            deleteParams.setMargins(8, 0, 0, 0);
            btnDelete.setLayoutParams(deleteParams);

            btnRow.addView(btnInstall);
            btnRow.addView(btnToggle);
            btnRow.addView(btnDelete);
            card.addView(btnRow);

            return new ViewHolder(card);
        }

        @Override
        public void onBindViewHolder(ViewHolder h, int pos) {
            ModInfo mod = currentMods.get(pos);
            h.name.setText(mod.name);
            h.author.setText("Автор: " + mod.author);
            h.description.setText(mod.description);
            h.downloads.setText("Скачиваний: " + formatCount(mod.downloads));
            h.version.setText(mod.isInstalled ? "v" + mod.version : "");

            if (mod.isInstalled) {
                h.btnInstall.setVisibility(View.GONE);
                h.btnToggle.setVisibility(View.VISIBLE);
                h.btnDelete.setVisibility(View.VISIBLE);
                h.btnToggle.setText(mod.isEnabled ? "Отключить" : "Включить");
                h.btnToggle.setBackgroundColor(mod.isEnabled ? 0xFF555555 : 0xFF1B5E20);
                h.btnToggle.setOnClickListener(v -> toggleMod(mod));
                h.btnDelete.setOnClickListener(v -> deleteMod(mod));
            } else {
                h.btnInstall.setVisibility(View.VISIBLE);
                h.btnToggle.setVisibility(View.GONE);
                h.btnDelete.setVisibility(View.GONE);
                h.btnInstall.setOnClickListener(v -> installMod(mod));
            }
        }

        @Override
        public int getItemCount() { return currentMods.size(); }

        private TextView makeText(int sizeSp, int color, boolean bold) {
            TextView tv = new TextView(ModManagerActivity.this);
            tv.setTextSize(sizeSp);
            tv.setTextColor(color);
            if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
            return tv;
        }

        private String formatCount(long n) {
            if (n >= 1_000_000) return (n / 1_000_000) + "M";
            if (n >= 1_000) return (n / 1_000) + "K";
            return String.valueOf(n);
        }
    }
}
