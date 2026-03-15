package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.modmanager.CurseForgeApi;
import net.kdt.pojavlaunch.modmanager.ModrinthApi;
import net.kdt.pojavlaunch.modmanager.MarketModAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModMarketFragment extends Fragment {
    public static final String TAG = "ModMarketFragment";
    public static final String ARG_VERSION = "version";

    private static final int SOURCE_MODRINTH = 0;
    private static final int SOURCE_CURSEFORGE = 1;
    private static final int PAGE_SIZE = 20;

    // Сортировка Modrinth
    private static final String[] SORT_LABELS = {"Популярные", "Новые", "Обновлённые", "Релевантные"};
    private static final String[] SORT_VALUES = {"downloads", "newest", "updated", "relevance"};
    private int mSortIndex = 0;

    private int mSource = SOURCE_MODRINTH;
    private int mPage = 1;
    private int mTotalPages = 1;
    private String mLastQuery = "";

    private EditText mSearchEdit;
    private Button mSearchButton;
    private Button mFilterToggle;
    private Button mSourceToggle;
    private Button mSortButton;
    private RecyclerView mModList;
    private ProgressBar mProgress;
    private TextView mStatusText;
    private LinearLayout mPagination;
    private Button mPrevButton;
    private Button mNextButton;
    private TextView mPageText;
    private View mOverlay;
    private MarketModAdapter mAdapter;

    private String mRawVersion = "";
    private String mVersionFilter = "";
    private boolean mFilterEnabled = true;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public ModMarketFragment() {
        super(R.layout.fragment_mod_market);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSearchEdit = view.findViewById(R.id.market_search_edit);
        mSearchButton = view.findViewById(R.id.market_search_button);
        mFilterToggle = view.findViewById(R.id.market_filter_toggle);
        mSourceToggle = view.findViewById(R.id.market_source_toggle);
        mSortButton = view.findViewById(R.id.market_sort_button);
        mModList = view.findViewById(R.id.market_mod_list);
        mProgress = view.findViewById(R.id.market_progress);
        mStatusText = view.findViewById(R.id.market_status_text);
        mPagination = view.findViewById(R.id.market_pagination);
        mPrevButton = view.findViewById(R.id.market_prev_button);
        mNextButton = view.findViewById(R.id.market_next_button);
        mPageText = view.findViewById(R.id.market_page_text);
        mOverlay = view.findViewById(R.id.market_overlay);

        if (getArguments() != null) {
            String raw = getArguments().getString(ARG_VERSION, "");
            mRawVersion = raw != null ? raw : "";
        }
        mVersionFilter = CurseForgeApi.extractMcVersion(mRawVersion);

        mAdapter = new MarketModAdapter(new ArrayList<>(), this::onModClick);
        mModList.setLayoutManager(new LinearLayoutManager(getContext()));
        mModList.setAdapter(mAdapter);

        // Прячем шапку при скролле
        final float[] overlayTopCache = {mOverlay.getHeight()};
        mOverlay.post(() -> overlayTopCache[0] = mOverlay.getHeight());
        mModList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                float newY = mOverlay.getY() - dy;
                float minY = -overlayTopCache[0];
                mOverlay.setY(Math.max(minY, Math.min(0, newY)));
            }
        });

        updateUI();

        mSearchButton.setOnClickListener(v -> startNewSearch());
        mSearchEdit.setOnEditorActionListener((v, actionId, event) -> {
            startNewSearch();
            return true;
        });

        mFilterToggle.setOnClickListener(v -> {
            mFilterEnabled = !mFilterEnabled;
            updateUI();
            startNewSearch();
        });

        mSourceToggle.setOnClickListener(v -> {
            mSource = (mSource == SOURCE_MODRINTH) ? SOURCE_CURSEFORGE : SOURCE_MODRINTH;
            updateUI();
            startNewSearch();
        });

        mSortButton.setOnClickListener(v -> showSortDialog());

        mPrevButton.setOnClickListener(v -> {
            if (mPage > 1) { mPage--; search(mLastQuery, mPage); }
        });
        mNextButton.setOnClickListener(v -> {
            if (mPage < mTotalPages) { mPage++; search(mLastQuery, mPage); }
        });

        startNewSearch();
    }

    private void showSortDialog() {
        new AlertDialog.Builder(requireContext())
            .setTitle("Сортировка")
            .setSingleChoiceItems(SORT_LABELS, mSortIndex, (d, which) -> {
                mSortIndex = which;
                mSortButton.setText("↓ " + SORT_LABELS[which]);
                d.dismiss();
                startNewSearch();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void startNewSearch() {
        mPage = 1;
        mLastQuery = mSearchEdit.getText().toString().trim();
        search(mLastQuery, mPage);
    }

    private void updateUI() {
        mSourceToggle.setText(mSource == SOURCE_MODRINTH ? "Modrinth" : "CurseForge");
        mSortButton.setVisibility(mSource == SOURCE_MODRINTH ? View.VISIBLE : View.GONE);

        if (mVersionFilter.isEmpty()) {
            mFilterToggle.setVisibility(View.GONE);
        } else {
            mFilterToggle.setVisibility(View.VISIBLE);
            mFilterToggle.setText(mFilterEnabled ? "🔍 " + mVersionFilter : "Фильтр выкл.");
        }
    }

    private void updatePagination() {
        if (mTotalPages <= 1) {
            mPagination.setVisibility(View.GONE);
            return;
        }
        mPagination.setVisibility(View.VISIBLE);
        mPageText.setText(mPage + " / " + mTotalPages);
        mPrevButton.setEnabled(mPage > 1);
        mNextButton.setEnabled(mPage < mTotalPages);
    }

    private void showLoading() {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mStatusText.setVisibility(View.GONE);
        mModList.setVisibility(View.GONE);
        mPagination.setVisibility(View.GONE);
    }

    private void hideLoading() {
        mProgress.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        mStatusText.setText("⚠ " + msg);
        mStatusText.setVisibility(View.VISIBLE);
    }

    private void search(String query, int page) {
        showLoading();
        final String version = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;
        final String rawVersion = mFilterEnabled ? mRawVersion : null;
        final int source = mSource;
        final int offset = (page - 1) * PAGE_SIZE;
        final String sortBy = SORT_VALUES[mSortIndex];

        mExecutor.execute(() -> {
            try {
                List<JSONObject> results;
                int total;
                int[] totalHolder = new int[1];

                if (source == SOURCE_MODRINTH) {
                    results = ModrinthApi.searchMods(query, version, rawVersion, offset, PAGE_SIZE, sortBy, totalHolder);
                } else {
                    results = CurseForgeApi.searchMods(query, version, rawVersion, offset, PAGE_SIZE, totalHolder);
                }
                total = totalHolder[0];

                final List<JSONObject> r = results;
                final int t = total;
                mHandler.post(() -> {
                    hideLoading();
                    mTotalPages = Math.max(1, (int) Math.ceil((double) t / PAGE_SIZE));
                    if (r.isEmpty()) {
                        showError("Ничего не найдено");
                    } else {
                        mModList.setVisibility(View.VISIBLE);
                        mAdapter.setSource(source == SOURCE_MODRINTH);
                        mAdapter.setMods(r);
                        mModList.scrollToPosition(0);
                        mOverlay.setY(0);
                        updatePagination();
                    }
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    hideLoading();
                    showError(e.getClass().getSimpleName() + ": " + e.getMessage());
                });
            }
        });
    }

    private void onModClick(JSONObject mod) {
        if (mSource == SOURCE_MODRINTH) showModrinthVersions(mod);
        else showCurseForgeVersions(mod);
    }

    // ---- CurseForge ----

    private void showCurseForgeVersions(JSONObject mod) {
        try {
            String name = mod.optString("name", "?");
            int modId = mod.getInt("id");
            String version = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;
            String rawVersion = mFilterEnabled ? mRawVersion : null;

            showLoading();
            mExecutor.execute(() -> {
                try {
                    JSONArray files = CurseForgeApi.getModFiles(modId, version, rawVersion);
                    mHandler.post(() -> {
                        hideLoading();
                        mModList.setVisibility(View.VISIBLE);
                        updatePagination();
                        if (files == null || files.length() == 0) {
                            Toast.makeText(getContext(), "Нет доступных версий", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showVersionBottomSheet(name, buildCurseForgeFileNames(files), i -> {
                            try { confirmCurseForgeInstall(name, files.getJSONObject(i), version, rawVersion); }
                            catch (Exception ignored) {}
                        });
                    });
                } catch (Exception e) {
                    mHandler.post(() -> {
                        hideLoading();
                        mModList.setVisibility(View.VISIBLE);
                        updatePagination();
                        Toast.makeText(getContext(), "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
        }
    }

    private String[] buildCurseForgeFileNames(JSONArray files) {
        String[] names = new String[files.length()];
        for (int i = 0; i < files.length(); i++) {
            try {
                JSONObject f = files.getJSONObject(i);
                String fn = f.optString("fileName", "?");
                JSONArray gv = f.optJSONArray("gameVersions");
                String ver = (gv != null && gv.length() > 0) ? " [" + gv.getString(0) + "]" : "";
                names[i] = fn + ver;
            } catch (Exception e) { names[i] = "Версия " + i; }
        }
        return names;
    }

    private void confirmCurseForgeInstall(String modName, JSONObject file, String version, String rawVersion) {
        try {
            int deps = countCurseForgeDeps(file);
            String msg = file.optString("fileName", "?");
            if (deps > 0) msg += "\n\n+ " + deps + " зависимостей";
            new AlertDialog.Builder(requireContext())
                .setTitle("Установить " + modName + "?")
                .setMessage(msg)
                .setPositiveButton("Установить", (d, w) -> installCurseForgeMod(modName, file, version, rawVersion))
                .setNegativeButton("Отмена", null)
                .show();
        } catch (Exception ignored) {}
    }

    private int countCurseForgeDeps(JSONObject file) {
        try {
            JSONArray deps = file.optJSONArray("dependencies");
            if (deps == null) return 0;
            int count = 0;
            for (int i = 0; i < deps.length(); i++)
                if (deps.getJSONObject(i).optInt("relationType", 0) == 3) count++;
            return count;
        } catch (Exception e) { return 0; }
    }

    private void installCurseForgeMod(String modName, JSONObject file, String version, String rawVersion) {
        mProgress.setIndeterminate(false);
        mProgress.setProgress(0);
        mProgress.setVisibility(View.VISIBLE);
        mExecutor.execute(() -> {
            try {
                File modsDir = getModsDir();
                if (modsDir == null) return;
                String url = file.getString("downloadUrl");
                String fileName = file.getString("fileName");
                List<String[]> deps = resolveCurseForgeDeps(file, version, rawVersion);
                int total = 1 + deps.size();
                final int[] cur = {0};
                CurseForgeApi.downloadFileWithProgress(url, new File(modsDir, fileName), p ->
                    mHandler.post(() -> mProgress.setProgress((cur[0] * 100 + p) / total)));
                cur[0]++;
                for (String[] dep : deps) {
                    CurseForgeApi.downloadFileWithProgress(dep[0], new File(modsDir, dep[1]), p ->
                        mHandler.post(() -> mProgress.setProgress((cur[0] * 100 + p) / total)));
                    cur[0]++;
                }
                String msg = "✓ " + modName + (deps.isEmpty() ? "" : " + " + deps.size() + " зависимостей");
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private List<String[]> resolveCurseForgeDeps(JSONObject file, String version, String rawVersion) {
        List<String[]> result = new ArrayList<>();
        try {
            JSONArray deps = file.optJSONArray("dependencies");
            if (deps == null) return result;
            for (int i = 0; i < deps.length(); i++) {
                JSONObject dep = deps.getJSONObject(i);
                if (dep.optInt("relationType", 0) == 3) {
                    JSONArray depFiles = CurseForgeApi.getModFiles(dep.getInt("modId"), version, rawVersion);
                    if (depFiles != null && depFiles.length() > 0) {
                        JSONObject df = depFiles.getJSONObject(0);
                        result.add(new String[]{df.getString("downloadUrl"), df.getString("fileName")});
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ---- Modrinth ----

    private void showModrinthVersions(JSONObject mod) {
        try {
            String name = mod.optString("title", "?");
            String projectId = mod.optString("project_id", mod.optString("slug", ""));
            String version = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;
            String rawVersion = mFilterEnabled ? mRawVersion : null;

            showLoading();
            mExecutor.execute(() -> {
                try {
                    JSONArray versions = ModrinthApi.getProjectVersions(projectId, version, rawVersion);
                    mHandler.post(() -> {
                        hideLoading();
                        mModList.setVisibility(View.VISIBLE);
                        updatePagination();
                        if (versions == null || versions.length() == 0) {
                            Toast.makeText(getContext(), "Нет доступных версий", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showVersionBottomSheet(name, buildModrinthVersionNames(versions), i -> {
                            try { confirmModrinthInstall(name, versions.getJSONObject(i)); }
                            catch (Exception ignored) {}
                        });
                    });
                } catch (Exception e) {
                    mHandler.post(() -> {
                        hideLoading();
                        mModList.setVisibility(View.VISIBLE);
                        updatePagination();
                        Toast.makeText(getContext(), "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
        }
    }

    private String[] buildModrinthVersionNames(JSONArray versions) {
        String[] names = new String[versions.length()];
        for (int i = 0; i < versions.length(); i++) {
            try {
                JSONObject v = versions.getJSONObject(i);
                String vnum = v.optString("version_number", "?");
                JSONArray gv = v.optJSONArray("game_versions");
                String mcVer = (gv != null && gv.length() > 0) ? gv.getString(0) : "";
                names[i] = vnum + (mcVer.isEmpty() ? "" : " [" + mcVer + "]");
            } catch (Exception e) { names[i] = "Версия " + i; }
        }
        return names;
    }

    private void confirmModrinthInstall(String modName, JSONObject version) {
        try {
            JSONArray files = version.optJSONArray("files");
            if (files == null || files.length() == 0) {
                Toast.makeText(getContext(), "Нет файлов", Toast.LENGTH_SHORT).show();
                return;
            }
            String fileName = files.getJSONObject(0).optString("filename", "mod.jar");
            int deps = countModrinthDeps(version);
            String msg = fileName + (deps > 0 ? "\n\n+ " + deps + " зависимостей" : "");
            new AlertDialog.Builder(requireContext())
                .setTitle("Установить " + modName + "?")
                .setMessage(msg)
                .setPositiveButton("Установить", (d, w) -> installModrinthMod(modName, version))
                .setNegativeButton("Отмена", null)
                .show();
        } catch (Exception ignored) {}
    }

    private int countModrinthDeps(JSONObject version) {
        try {
            JSONArray deps = version.optJSONArray("dependencies");
            if (deps == null) return 0;
            int count = 0;
            for (int i = 0; i < deps.length(); i++)
                if ("required".equals(deps.getJSONObject(i).optString("dependency_type"))) count++;
            return count;
        } catch (Exception e) { return 0; }
    }

    private void installModrinthMod(String modName, JSONObject version) {
        mProgress.setIndeterminate(false);
        mProgress.setProgress(0);
        mProgress.setVisibility(View.VISIBLE);
        mExecutor.execute(() -> {
            try {
                File modsDir = getModsDir();
                if (modsDir == null) return;
                JSONArray files = version.getJSONArray("files");
                JSONObject pf = files.getJSONObject(0);
                String url = pf.getString("url");
                String fileName = pf.optString("filename", "mod.jar");
                List<String[]> deps = resolveModrinthDeps(version);
                int total = 1 + deps.size();
                final int[] cur = {0};
                CurseForgeApi.downloadFileWithProgress(url, new File(modsDir, fileName), p ->
                    mHandler.post(() -> mProgress.setProgress((cur[0] * 100 + p) / total)));
                cur[0]++;
                for (String[] dep : deps) {
                    CurseForgeApi.downloadFileWithProgress(dep[0], new File(modsDir, dep[1]), p ->
                        mHandler.post(() -> mProgress.setProgress((cur[0] * 100 + p) / total)));
                    cur[0]++;
                }
                String msg = "✓ " + modName + (deps.isEmpty() ? "" : " + " + deps.size() + " зависимостей");
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private List<String[]> resolveModrinthDeps(JSONObject version) {
        List<String[]> result = new ArrayList<>();
        try {
            JSONArray deps = version.optJSONArray("dependencies");
            if (deps == null) return result;
            for (int i = 0; i < deps.length(); i++) {
                JSONObject dep = deps.getJSONObject(i);
                if ("required".equals(dep.optString("dependency_type"))) {
                    String depVersionId = dep.optString("version_id", "");
                    if (!depVersionId.isEmpty()) {
                        JSONObject dv = ModrinthApi.getVersion(depVersionId);
                        if (dv != null) {
                            JSONArray df = dv.optJSONArray("files");
                            if (df != null && df.length() > 0) {
                                JSONObject f = df.getJSONObject(0);
                                result.add(new String[]{f.getString("url"), f.optString("filename", "dep.jar")});
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ---- Версии выезжающим снизу диалогом ----

    interface OnVersionSelected { void onSelected(int index); }

    private void showVersionBottomSheet(String title, String[] versions, OnVersionSelected callback) {
        new AlertDialog.Builder(requireContext())
            .setTitle("Версии: " + title)
            .setItems(versions, (d, i) -> callback.onSelected(i))
            .setNegativeButton("Отмена", null)
            .show();
    }

    // ---- Утилиты ----

    private File getModsDir() {
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) {
            mHandler.post(() -> Toast.makeText(getContext(), "Инстанс не найден", Toast.LENGTH_SHORT).show());
            return null;
        }
        File modsDir = new File(instance.getGameDirectory(), "mods");
        if (!modsDir.exists()) modsDir.mkdirs();
        return modsDir;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }
}
