package net.kdt.pojavlaunch.fragments;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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

    // Modrinth sort
    private static final String[] MODRINTH_SORT_LABELS = {"Relevance", "Downloads", "Follows", "Newest", "Updated"};
    private static final String[] MODRINTH_SORT_VALUES = {"relevance", "downloads", "follows", "newest", "updated"};

    // CurseForge sort — отдельные значения
    private static final String[] CF_SORT_LABELS = {"Relevance", "Popularity", "Downloads", "Newest", "Last Updated"};
    private static final String[] CF_SORT_VALUES = {"", "2", "6", "10", "1"};

    private int mSortIndex = 0;
    private int mSource = SOURCE_MODRINTH;
    private int mPage = 1;
    private int mTotalPages = 1;
    private String mLastQuery = "";

    // Фильтры — отдельные для каждого источника
    private String mModrinthVersion = null;
    private String mModrinthLoader = null;
    private String mCfVersion = null;
    private String mCfLoader = null;

    private EditText mSearchEdit;
    private ImageButton mFilterButton;
    private ImageButton mSourceToggle;
    private TextView mSortButton;
    private TextView mVersionPicker;
    private TextView mLoaderPicker;
    private TextView mFilterToggle;
    private RecyclerView mModList;
    private ProgressBar mProgress;
    private TextView mStatusText;
    private LinearLayout mPagination;
    private TextView mPrevButton;
    private TextView mNextButton;
    private TextView mPageText;
    private View mOverlay;
    private LinearLayout mFilterPanel;
    private View mFilterScrim;
    private boolean mFilterPanelOpen = false;
    private MarketModAdapter mAdapter;

    private String mRawVersion = "";
    private String mVersionFilter = "";  // из инстанса
    private String mLoaderFilter = "";   // из инстанса
    private boolean mFilterEnabled = true;

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private String[] mGameVersions = null;

    public ModMarketFragment() {
        super(R.layout.fragment_mod_market);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSearchEdit = view.findViewById(R.id.market_search_edit);
        mFilterButton = view.findViewById(R.id.market_filter_button);
        mSourceToggle = view.findViewById(R.id.market_source_toggle);
        mSortButton = view.findViewById(R.id.market_sort_button);
        mVersionPicker = view.findViewById(R.id.market_version_picker);
        mLoaderPicker = view.findViewById(R.id.market_loader_picker);
        mFilterToggle = view.findViewById(R.id.market_filter_toggle);
        mModList = view.findViewById(R.id.market_mod_list);
        mProgress = view.findViewById(R.id.market_progress);
        mStatusText = view.findViewById(R.id.market_status_text);
        mPagination = view.findViewById(R.id.market_pagination);
        mPrevButton = view.findViewById(R.id.market_prev_button);
        mNextButton = view.findViewById(R.id.market_next_button);
        mPageText = view.findViewById(R.id.market_page_text);
        mOverlay = view.findViewById(R.id.market_overlay);
        mFilterPanel = view.findViewById(R.id.market_filter_panel);
        mFilterScrim = view.findViewById(R.id.market_filter_scrim);
        mFilterPanel.setVisibility(View.GONE);
        mFilterScrim.setVisibility(View.GONE);

        if (getArguments() != null) {
            String raw = getArguments().getString(ARG_VERSION, "");
            mRawVersion = raw != null ? raw : "";
        }
        mVersionFilter = CurseForgeApi.extractMcVersion(mRawVersion);
        mLoaderFilter = extractLoader(mRawVersion);

        // Устанавливаем фильтры инстанса как дефолт
        if (!mVersionFilter.isEmpty()) {
            mModrinthVersion = mVersionFilter;
            mCfVersion = mVersionFilter;
        }
        if (!mLoaderFilter.isEmpty()) {
            mModrinthLoader = mLoaderFilter;
            mCfLoader = mLoaderFilter;
        }

        if (!mVersionFilter.isEmpty()) {
            mFilterToggle.setVisibility(View.VISIBLE);
            updateFilterToggleText();
            mFilterToggle.setOnClickListener(v -> {
                mFilterEnabled = !mFilterEnabled;
                updateFilterToggleText();
                startNewSearch();
            });
        }

        mAdapter = new MarketModAdapter(new ArrayList<>(), this::onInstallClick);
        mAdapter.setPickerListener(new MarketModAdapter.OnVersionPickerListener() {
            @Override
            public void onVersionPicker(JSONObject mod, TextView picker) {
                showVersionPickerForCard(mod, picker);
            }
            @Override
            public void onLoaderPicker(JSONObject mod, TextView picker) {
                showLoaderPickerForCard(mod, picker);
            }
        });
        mModList.setLayoutManager(new LinearLayoutManager(getContext()));
        mModList.setAdapter(mAdapter);

        // Прячем шапку при скролле
        mOverlay.post(() -> {
            final float h = mOverlay.getHeight();
            mModList.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    float y = mOverlay.getY() - dy;
                    mOverlay.setY(Math.max(-h, Math.min(0f, y)));
                }
            });
        });

        // Загружаем версии MC в фоне
        mExecutor.execute(() -> {
            try { mGameVersions = ModrinthApi.getGameVersions(); }
            catch (Exception ignored) {}
        });

        mSearchEdit.setOnEditorActionListener((v, a, e) -> { startNewSearch(); return true; });

        mSourceToggle.setOnClickListener(v -> {
            mSource = (mSource == SOURCE_MODRINTH) ? SOURCE_CURSEFORGE : SOURCE_MODRINTH;
            mSortIndex = 0;
            updateSourceIcon();
            updateFilterPanelForSource();
            startNewSearch();
        });

        mFilterButton.setOnClickListener(v -> toggleFilterPanel());
        mFilterScrim.setOnClickListener(v -> closeFilterPanel());

        mSortButton.setOnClickListener(v -> {
            String[] labels = mSource == SOURCE_MODRINTH ? MODRINTH_SORT_LABELS : CF_SORT_LABELS;
            new AlertDialog.Builder(requireContext())
                .setTitle("Sort by")
                .setSingleChoiceItems(labels, mSortIndex, (d, which) -> {
                    mSortIndex = which;
                    mSortButton.setText("↓ " + labels[which]);
                    d.dismiss();
                    startNewSearch();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        mVersionPicker.setOnClickListener(v -> showVersionPicker());
        mLoaderPicker.setOnClickListener(v -> showLoaderPicker());

        mPrevButton.setOnClickListener(v -> { if (mPage > 1) { mPage--; search(mLastQuery, mPage); } });
        mNextButton.setOnClickListener(v -> { if (mPage < mTotalPages) { mPage++; search(mLastQuery, mPage); } });

        updateSourceIcon();
        startNewSearch();
    }

    private void updateFilterToggleText() {
        mFilterToggle.setText(mFilterEnabled
            ? "🔍 " + mVersionFilter + (mLoaderFilter.isEmpty() ? "" : " · " + mLoaderFilter)
            : "Instance filter off");
    }

    private void updateFilterPanelForSource() {
        // Обновляем текст пикеров при смене источника
        String ver = mSource == SOURCE_MODRINTH ? mModrinthVersion : mCfVersion;
        String loader = mSource == SOURCE_MODRINTH ? mModrinthLoader : mCfLoader;
        mVersionPicker.setText(ver != null ? ver + " ▾" : "All versions ▾");
        mLoaderPicker.setText(loader != null ? capitalize(loader) + " ▾" : "All loaders ▾");
    }

    private void toggleFilterPanel() {
        if (mFilterPanelOpen) closeFilterPanel();
        else openFilterPanel();
    }

    private void openFilterPanel() {
        mFilterPanel.setVisibility(View.VISIBLE);
        mFilterScrim.setVisibility(View.VISIBLE);
        mFilterPanel.post(() -> {
            float w = mFilterPanel.getWidth();
            ObjectAnimator.ofFloat(mFilterPanel, "translationX", w, 0f).setDuration(250).start();
        });
        mFilterPanelOpen = true;
    }

    private void closeFilterPanel() {
        float w = mFilterPanel.getWidth();
        ObjectAnimator anim = ObjectAnimator.ofFloat(mFilterPanel, "translationX", 0f, w);
        anim.setDuration(200);
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mFilterPanel.setVisibility(View.GONE);
                mFilterScrim.setVisibility(View.GONE);
            }
        });
        anim.start();
        mFilterPanelOpen = false;
    }

    // Пикер версии в боковой панели (глобальный фильтр)
    private void showVersionPicker() {
        String[] versions = mGameVersions != null && mGameVersions.length > 0
            ? prependAll(mGameVersions)
            : new String[]{"All versions", "1.21.11", "1.21.1", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2"};

        String current = mSource == SOURCE_MODRINTH ? mModrinthVersion : mCfVersion;
        int selected = 0;
        if (current != null) {
            for (int i = 1; i < versions.length; i++) {
                if (versions[i].equals(current)) { selected = i; break; }
            }
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("Game version")
            .setSingleChoiceItems(versions, selected, (d, which) -> {
                String v = which == 0 ? null : versions[which];
                if (mSource == SOURCE_MODRINTH) mModrinthVersion = v;
                else mCfVersion = v;
                mVersionPicker.setText(v != null ? v + " ▾" : "All versions ▾");
                d.dismiss();
                startNewSearch();
            })
            .show();
    }

    // Пикер лоадера в боковой панели (глобальный фильтр)
    private void showLoaderPicker() {
        String[] loaders = {"All loaders", "Fabric", "Forge", "NeoForge", "Quilt"};
        String current = mSource == SOURCE_MODRINTH ? mModrinthLoader : mCfLoader;
        int selected = 0;
        if (current != null) {
            for (int i = 1; i < loaders.length; i++) {
                if (loaders[i].equalsIgnoreCase(current)) { selected = i; break; }
            }
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("Mod loader")
            .setSingleChoiceItems(loaders, selected, (d, which) -> {
                String l = which == 0 ? null : loaders[which].toLowerCase();
                if (mSource == SOURCE_MODRINTH) mModrinthLoader = l;
                else mCfLoader = l;
                mLoaderPicker.setText(which == 0 ? "All loaders ▾" : loaders[which] + " ▾");
                d.dismiss();
                startNewSearch();
            })
            .show();
    }

    // Пикер версии прямо в карточке мода — показывает версии конкретного мода
    private void showVersionPickerForCard(JSONObject mod, TextView picker) {
        mExecutor.execute(() -> {
            try {
                String[] versions;
                if (mSource == SOURCE_MODRINTH) {
                    String projectId = mod.optString("project_id", mod.optString("slug", ""));
                    JSONObject info = ModrinthApi.getProjectInfo(projectId);
                    JSONArray gv = info != null ? info.optJSONArray("game_versions") : null;
                    if (gv != null && gv.length() > 0) {
                        versions = new String[gv.length() + 1];
                        versions[0] = "All versions";
                        for (int i = 0; i < gv.length(); i++) versions[i + 1] = gv.getString(i);
                    } else {
                        versions = prependAll(mGameVersions != null ? mGameVersions
                            : new String[]{"1.21.11", "1.21.1", "1.20.1"});
                    }
                } else {
                    // CurseForge — берём из gameVersions файлов мода
                    int modId = mod.optInt("id", -1);
                    JSONArray files = modId != -1 ? CurseForgeApi.getModFiles(modId, null, null) : null;
                    List<String> vlist = new ArrayList<>();
                    vlist.add("All versions");
                    if (files != null) {
                        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                        for (int i = 0; i < files.length(); i++) {
                            JSONArray gv = files.getJSONObject(i).optJSONArray("gameVersions");
                            if (gv != null) for (int j = 0; j < gv.length(); j++) {
                                String v = gv.getString(j);
                                if (v.matches("\\d+\\.\\d+.*")) seen.add(v);
                            }
                        }
                        vlist.addAll(seen);
                    }
                    versions = vlist.toArray(new String[0]);
                }

                final String[] finalVersions = versions;
                mHandler.post(() -> {
                    new AlertDialog.Builder(requireContext())
                        .setTitle("Game version")
                        .setItems(finalVersions, (d, which) -> {
                            picker.setText(which == 0 ? "All Game Versions ▾" : finalVersions[which] + " ▾");
                            picker.setTag(which == 0 ? null : finalVersions[which]);
                        })
                        .show();
                });
            } catch (Exception e) {
                mHandler.post(() -> Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // Пикер лоадера в карточке — показывает лоадеры конкретного мода
    private void showLoaderPickerForCard(JSONObject mod, TextView picker) {
        mExecutor.execute(() -> {
            try {
                String[] loaders;
                if (mSource == SOURCE_MODRINTH) {
                    String projectId = mod.optString("project_id", mod.optString("slug", ""));
                    JSONObject info = ModrinthApi.getProjectInfo(projectId);
                    JSONArray lArr = info != null ? info.optJSONArray("loaders") : null;
                    if (lArr != null && lArr.length() > 0) {
                        loaders = new String[lArr.length() + 1];
                        loaders[0] = "All loaders";
                        for (int i = 0; i < lArr.length(); i++)
                            loaders[i + 1] = capitalize(lArr.getString(i));
                    } else {
                        loaders = new String[]{"All loaders", "Fabric", "Forge", "NeoForge", "Quilt"};
                    }
                } else {
                    loaders = new String[]{"All loaders", "Fabric", "Forge", "NeoForge", "Quilt"};
                }

                final String[] finalLoaders = loaders;
                mHandler.post(() -> {
                    new AlertDialog.Builder(requireContext())
                        .setTitle("Mod loader")
                        .setItems(finalLoaders, (d, which) -> {
                            picker.setText(which == 0 ? "All Mod Loaders ▾" : finalLoaders[which] + " ▾");
                            picker.setTag(which == 0 ? null : finalLoaders[which].toLowerCase());
                        })
                        .show();
                });
            } catch (Exception e) {
                mHandler.post(() -> Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String[] prependAll(String[] arr) {
        if (arr == null) return new String[]{"All versions"};
        String[] r = new String[arr.length + 1];
        r[0] = "All versions";
        System.arraycopy(arr, 0, r, 1, arr.length);
        return r;
    }

    private void updateSourceIcon() {
        mSourceToggle.setImageResource(mSource == SOURCE_MODRINTH
            ? R.drawable.ic_modrinth : R.drawable.ic_curseforge);
        String[] labels = mSource == SOURCE_MODRINTH ? MODRINTH_SORT_LABELS : CF_SORT_LABELS;
        if (mSortIndex >= labels.length) mSortIndex = 0;
        mSortButton.setText("↓ " + labels[mSortIndex]);
    }

    private void startNewSearch() {
        mPage = 1;
        mLastQuery = mSearchEdit.getText().toString().trim();
        search(mLastQuery, mPage);
    }

    private void showLoading() {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mStatusText.setVisibility(View.GONE);
        mModList.setVisibility(View.GONE);
        mPagination.setVisibility(View.GONE);
    }

    private void hideLoading() { mProgress.setVisibility(View.GONE); }

    private void updatePagination() {
        if (mTotalPages <= 1) { mPagination.setVisibility(View.GONE); return; }
        mPagination.setVisibility(View.VISIBLE);
        mPageText.setText(mPage + " / " + mTotalPages);
        mPrevButton.setAlpha(mPage > 1 ? 1f : 0.3f);
        mNextButton.setAlpha(mPage < mTotalPages ? 1f : 0.3f);
    }

    private void search(String query, int page) {
        showLoading();
        final int source = mSource;
        final int offset = (page - 1) * PAGE_SIZE;

        // Получаем фильтры для текущего источника
        final String version;
        final String loader;
        if (source == SOURCE_MODRINTH) {
            version = mFilterEnabled && mModrinthVersion != null ? mModrinthVersion : null;
            loader = mFilterEnabled && mModrinthLoader != null ? mModrinthLoader : null;
        } else {
            version = mFilterEnabled && mCfVersion != null ? mCfVersion : null;
            loader = mFilterEnabled && mCfLoader != null ? mCfLoader : null;
        }

        final String sortBy = source == SOURCE_MODRINTH
            ? MODRINTH_SORT_VALUES[mSortIndex]
            : CF_SORT_VALUES[mSortIndex];

        mExecutor.execute(() -> {
            try {
                List<JSONObject> results;
                int[] totalHolder = new int[1];

                if (source == SOURCE_MODRINTH) {
                    results = ModrinthApi.searchMods(query, version, loader, offset, PAGE_SIZE, sortBy, totalHolder);
                } else {
                    results = CurseForgeApi.searchMods(query, version, loader, offset, PAGE_SIZE, sortBy, totalHolder);
                }

                final List<JSONObject> r = results;
                final int t = totalHolder[0];
                mHandler.post(() -> {
                    hideLoading();
                    mTotalPages = Math.max(1, (int) Math.ceil((double) t / PAGE_SIZE));
                    mAdapter.setSource(source == SOURCE_MODRINTH);
                    mAdapter.setFilterEnabled(mFilterEnabled);
                    mAdapter.setVersionFilter(version != null ? version : "");
                    mAdapter.setLoaderFilter(loader != null ? loader : "");

                    if (r.isEmpty()) {
                        mStatusText.setText("Nothing found");
                        mStatusText.setVisibility(View.VISIBLE);
                    } else {
                        mAdapter.setMods(r);
                        mModList.setVisibility(View.VISIBLE);
                        mModList.scrollToPosition(0);
                        mOverlay.setY(0);
                        updatePagination();
                    }
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    hideLoading();
                    mStatusText.setText("⚠ " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    mStatusText.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void onInstallClick(JSONObject mod, String selectedVersion, String selectedLoader) {
        if (mSource == SOURCE_MODRINTH) installModrinth(mod, selectedVersion, selectedLoader);
        else installCurseForge(mod, selectedVersion, selectedLoader);
    }

    // ---- Modrinth ----

    private void installModrinth(JSONObject mod, String version, String loader) {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mExecutor.execute(() -> {
            try {
                String projectId = mod.optString("project_id", mod.optString("slug", ""));
                JSONArray versions = ModrinthApi.getProjectVersions(projectId, version, loader);
                if (versions == null || versions.length() == 0) {
                    mHandler.post(() -> { hideLoading(); Toast.makeText(getContext(), "No versions found", Toast.LENGTH_SHORT).show(); });
                    return;
                }
                JSONObject latest = versions.getJSONObject(0);
                String modName = mod.optString("title", "?");

                // Собираем зависимости
                List<ModrinthDep> deps = collectModrinthDeps(latest);

                mHandler.post(() -> {
                    hideLoading();
                    if (deps.isEmpty()) {
                        doInstallModrinth(modName, latest, new ArrayList<>());
                    } else {
                        showDepsDialog(modName, deps, selected ->
                            doInstallModrinth(modName, latest, selected));
                    }
                });
            } catch (Exception e) {
                mHandler.post(() -> { hideLoading(); Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private static class ModrinthDep {
        String name;
        String url;
        String fileName;
        boolean selected = true;

        ModrinthDep(String name, String url, String fileName) {
            this.name = name; this.url = url; this.fileName = fileName;
        }
    }

    private List<ModrinthDep> collectModrinthDeps(JSONObject version) {
        List<ModrinthDep> result = new ArrayList<>();
        try {
            JSONArray deps = version.optJSONArray("dependencies");
            if (deps == null) return result;
            for (int i = 0; i < deps.length(); i++) {
                JSONObject dep = deps.getJSONObject(i);
                if ("required".equals(dep.optString("dependency_type"))) {
                    String depVersionId = dep.optString("version_id", "");
                    String depProjectId = dep.optString("project_id", "");
                    if (!depVersionId.isEmpty()) {
                        JSONObject dv = ModrinthApi.getVersion(depVersionId);
                        if (dv != null) {
                            JSONArray df = dv.optJSONArray("files");
                            if (df != null && df.length() > 0) {
                                JSONObject f = df.getJSONObject(0);
                                String name = dv.optString("name", depProjectId);
                                result.add(new ModrinthDep(name, f.getString("url"), f.optString("filename", "dep.jar")));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private interface DepsCallback {
        void onDone(List<ModrinthDep> selected);
    }

    private void showDepsDialog(String modName, List<ModrinthDep> deps, DepsCallback callback) {
        // Строим экран выбора зависимостей
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 24, 48, 8);

        List<CheckBox> checkboxes = new ArrayList<>();
        for (ModrinthDep dep : deps) {
            CheckBox cb = new CheckBox(requireContext());
            cb.setText(dep.name);
            cb.setChecked(true);
            container.addView(cb);
            checkboxes.add(cb);
        }

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(container);

        new AlertDialog.Builder(requireContext())
            .setTitle("Dependencies for " + modName)
            .setMessage("Select which dependencies to install:")
            .setView(scroll)
            .setPositiveButton("Install", (d, w) -> {
                List<ModrinthDep> selected = new ArrayList<>();
                for (int i = 0; i < deps.size(); i++) {
                    if (checkboxes.get(i).isChecked()) selected.add(deps.get(i));
                }
                callback.onDone(selected);
            })
            .setNegativeButton("Skip all", (d, w) -> callback.onDone(new ArrayList<>()))
            .show();
    }

    private void doInstallModrinth(String modName, JSONObject version, List<ModrinthDep> deps) {
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

                int total = 1 + deps.size();
                final int[] cur = {0};

                CurseForgeApi.downloadFileWithProgress(url, new File(modsDir, fileName),
                    (p, dl, size, fn) -> mHandler.post(() -> {
                        mProgress.setProgress((cur[0] * 100 + p) / total);
                        updateDownloadStatus(fn, dl, size);
                    }));
                cur[0]++;

                for (ModrinthDep dep : deps) {
                    CurseForgeApi.downloadFileWithProgress(dep.url, new File(modsDir, dep.fileName),
                        (p, dl, size, fn) -> mHandler.post(() -> {
                            mProgress.setProgress((cur[0] * 100 + p) / total);
                            updateDownloadStatus(fn, dl, size);
                        }));
                    cur[0]++;
                }

                String msg = "✓ " + modName + (deps.isEmpty() ? "" : " + " + deps.size() + " deps");
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    // ---- CurseForge ----

    private void installCurseForge(JSONObject mod, String version, String loader) {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mExecutor.execute(() -> {
            try {
                int modId = mod.getInt("id");
                JSONArray files = CurseForgeApi.getModFiles(modId, version, loader);
                if (files == null || files.length() == 0) {
                    mHandler.post(() -> { hideLoading(); Toast.makeText(getContext(), "No versions found", Toast.LENGTH_SHORT).show(); });
                    return;
                }
                JSONObject latest = files.getJSONObject(0);
                String modName = mod.optString("name", "?");

                List<CFDep> deps = collectCFDeps(latest, version, loader);

                mHandler.post(() -> {
                    hideLoading();
                    if (deps.isEmpty()) {
                        doInstallCF(modName, latest, new ArrayList<>(), version, loader);
                    } else {
                        showCFDepsDialog(modName, deps, selected ->
                            doInstallCF(modName, latest, selected, version, loader));
                    }
                });
            } catch (Exception e) {
                mHandler.post(() -> { hideLoading(); Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private static class CFDep {
        String name;
        String downloadUrl;
        String fileName;
        boolean selected = true;

        CFDep(String name, String downloadUrl, String fileName) {
            this.name = name; this.downloadUrl = downloadUrl; this.fileName = fileName;
        }
    }

    private List<CFDep> collectCFDeps(JSONObject file, String version, String loader) {
        List<CFDep> result = new ArrayList<>();
        try {
            JSONArray deps = file.optJSONArray("dependencies");
            if (deps == null) return result;
            for (int i = 0; i < deps.length(); i++) {
                JSONObject dep = deps.getJSONObject(i);
                if (dep.optInt("relationType", 0) == 3) {
                    int depId = dep.getInt("modId");
                    JSONArray df = CurseForgeApi.getModFiles(depId, version, loader);
                    if (df != null && df.length() > 0) {
                        JSONObject f = df.getJSONObject(0);
                        // Получаем имя мода
                        JSONObject modInfo = CurseForgeApi.getModInfo(depId);
                        String name = modInfo != null ? modInfo.optString("name", "Dep " + depId) : "Dep " + depId;
                        result.add(new CFDep(name, f.getString("downloadUrl"), f.getString("fileName")));
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private interface CFDepsCallback {
        void onDone(List<CFDep> selected);
    }

    private void showCFDepsDialog(String modName, List<CFDep> deps, CFDepsCallback callback) {
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 24, 48, 8);

        List<CheckBox> checkboxes = new ArrayList<>();
        for (CFDep dep : deps) {
            CheckBox cb = new CheckBox(requireContext());
            cb.setText(dep.name);
            cb.setChecked(true);
            container.addView(cb);
            checkboxes.add(cb);
        }

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(container);

        new AlertDialog.Builder(requireContext())
            .setTitle("Dependencies for " + modName)
            .setView(scroll)
            .setPositiveButton("Install", (d, w) -> {
                List<CFDep> selected = new ArrayList<>();
                for (int i = 0; i < deps.size(); i++) {
                    if (checkboxes.get(i).isChecked()) selected.add(deps.get(i));
                }
                callback.onDone(selected);
            })
            .setNegativeButton("Skip all", (d, w) -> callback.onDone(new ArrayList<>()))
            .show();
    }

    private void doInstallCF(String modName, JSONObject file, List<CFDep> deps, String version, String loader) {
        mProgress.setIndeterminate(false);
        mProgress.setProgress(0);
        mProgress.setVisibility(View.VISIBLE);
        mExecutor.execute(() -> {
            try {
                File modsDir = getModsDir();
                if (modsDir == null) return;
                String url = file.getString("downloadUrl");
                String fileName = file.getString("fileName");
                int total = 1 + deps.size();
                final int[] cur = {0};

                CurseForgeApi.downloadFileWithProgress(url, new File(modsDir, fileName),
                    (p, dl, size, fn) -> mHandler.post(() -> {
                        mProgress.setProgress((cur[0] * 100 + p) / total);
                        updateDownloadStatus(fn, dl, size);
                    }));
                cur[0]++;

                for (CFDep dep : deps) {
                    CurseForgeApi.downloadFileWithProgress(dep.downloadUrl, new File(modsDir, dep.fileName),
                        (p, dl, size, fn) -> mHandler.post(() -> {
                            mProgress.setProgress((cur[0] * 100 + p) / total);
                            updateDownloadStatus(fn, dl, size);
                        }));
                    cur[0]++;
                }

                String msg = "✓ " + modName + (deps.isEmpty() ? "" : " + " + deps.size() + " deps");
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private void updateDownloadStatus(String fileName, long downloaded, long total) {
        // Показываем статус в прогресс баре шапки через title
        String dl = formatBytes(downloaded);
        String tot = total > 0 ? " / " + formatBytes(total) : "";
        // Toast слишком навязчив — просто обновляем прогресс
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    // ---- Утилиты ----

    private String extractLoader(String rawVersion) {
        if (rawVersion == null || rawVersion.isEmpty()) return "";
        String lower = rawVersion.toLowerCase();
        if (lower.startsWith("fabric")) return "fabric";
        if (lower.startsWith("neoforge")) return "neoforge";
        if (lower.startsWith("forge")) return "forge";
        if (lower.startsWith("quilt")) return "quilt";
        return "";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private File getModsDir() {
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) {
            mHandler.post(() -> Toast.makeText(getContext(), "Instance not found", Toast.LENGTH_SHORT).show());
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
