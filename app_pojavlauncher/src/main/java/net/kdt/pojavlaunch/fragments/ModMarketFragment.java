package net.kdt.pojavlaunch.fragments;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
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

    private static final String[] MODRINTH_SORT_LABELS = {"Relevance", "Downloads", "Follows", "Newest", "Updated"};
    private static final String[] MODRINTH_SORT_VALUES = {"relevance", "downloads", "follows", "newest", "updated"};
    private static final String[] CF_SORT_LABELS = {"Relevance", "Popularity", "Downloads", "Newest", "Last Updated"};
    private static final String[] CF_SORT_VALUES = {"", "2", "6", "10", "1"};

    private int mSortIndex = 0; // По умолчанию relevance
    private int mSource = SOURCE_MODRINTH;
    private int mPage = 1;
    private int mTotalPages = 1;
    private String mLastQuery = "";

    // Фильтры из боковой панели
    private String mSelectedVersion = null;  // null = all
    private String mSelectedLoader = null;   // null = all

    private EditText mSearchEdit;
    private ImageButton mSearchButton;
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
    private String mVersionFilter = "";
    private String mLoaderFilter = "";
    private boolean mFilterEnabled = true;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Кэш версий MC и лоадеров
    private String[] mGameVersions = null;
    private static final String[] LOADERS = {"fabric", "forge", "neoforge", "quilt"};

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

        if (getArguments() != null) {
            String raw = getArguments().getString(ARG_VERSION, "");
            mRawVersion = raw != null ? raw : "";
        }
        mVersionFilter = CurseForgeApi.extractMcVersion(mRawVersion);
        mLoaderFilter = extractLoader(mRawVersion);

        // Показываем фильтр инстанса если есть версия
        if (!mVersionFilter.isEmpty()) {
            mFilterToggle.setVisibility(View.VISIBLE);
            mFilterToggle.setText(mFilterEnabled
                ? "🔍 " + mVersionFilter + (mLoaderFilter.isEmpty() ? "" : " · " + mLoaderFilter)
                : "Instance filter off");
            mFilterToggle.setOnClickListener(v -> {
                mFilterEnabled = !mFilterEnabled;
                mFilterToggle.setText(mFilterEnabled
                    ? "🔍 " + mVersionFilter + (mLoaderFilter.isEmpty() ? "" : " · " + mLoaderFilter)
                    : "Instance filter off");
                startNewSearch();
            });
        }

        mAdapter = new MarketModAdapter(new ArrayList<>(), this::onInstallClick);
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

        // Загружаем версии MC в фоне для пикера
        mExecutor.execute(() -> {
            try {
                mGameVersions = ModrinthApi.getGameVersions();
            } catch (Exception ignored) {}
        });

        mSearchButton.setOnClickListener(v -> startNewSearch());
        mSearchEdit.setOnEditorActionListener((v, a, e) -> { startNewSearch(); return true; });

        mSourceToggle.setOnClickListener(v -> {
            mSource = (mSource == SOURCE_MODRINTH) ? SOURCE_CURSEFORGE : SOURCE_MODRINTH;
            mSortIndex = 0;
            updateSourceIcon();
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

    private void toggleFilterPanel() {
        if (mFilterPanelOpen) closeFilterPanel();
        else openFilterPanel();
    }

    private void openFilterPanel() {
        mFilterPanel.setVisibility(View.VISIBLE);
        mFilterScrim.setVisibility(View.VISIBLE);
        ObjectAnimator.ofFloat(mFilterPanel, "translationX", 280f * getResources().getDisplayMetrics().density / getResources().getDisplayMetrics().density, 0f).setDuration(250).start();
        mFilterPanelOpen = true;
    }

    private void closeFilterPanel() {
        float panelWidth = mFilterPanel.getWidth();
        ObjectAnimator anim = ObjectAnimator.ofFloat(mFilterPanel, "translationX", 0f, panelWidth);
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

    private void showVersionPicker() {
        String[] versions = mGameVersions != null && mGameVersions.length > 0
            ? prependAll(mGameVersions) : new String[]{"All versions", "1.21.11", "1.21.1", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2"};

        new AlertDialog.Builder(requireContext())
            .setTitle("Game version")
            .setItems(versions, (d, which) -> {
                if (which == 0) {
                    mSelectedVersion = null;
                    mVersionPicker.setText("All versions ▾");
                    mVersionPicker.setTag(null);
                } else {
                    mSelectedVersion = versions[which];
                    mVersionPicker.setText(mSelectedVersion + " ▾");
                    mVersionPicker.setTag(mSelectedVersion);
                }
                startNewSearch();
            })
            .show();
    }

    private void showLoaderPicker() {
        String[] loaders = {"All loaders", "Fabric", "Forge", "NeoForge", "Quilt"};
        new AlertDialog.Builder(requireContext())
            .setTitle("Mod loader")
            .setItems(loaders, (d, which) -> {
                if (which == 0) {
                    mSelectedLoader = null;
                    mLoaderPicker.setText("All loaders ▾");
                    mLoaderPicker.setTag(null);
                } else {
                    mSelectedLoader = loaders[which].toLowerCase();
                    mLoaderPicker.setText(loaders[which] + " ▾");
                    mLoaderPicker.setTag(mSelectedLoader);
                }
                startNewSearch();
            })
            .show();
    }

    private String[] prependAll(String[] arr) {
        String[] result = new String[arr.length + 1];
        result[0] = "All versions";
        System.arraycopy(arr, 0, result, 1, arr.length);
        return result;
    }

    private void updateSourceIcon() {
        mSourceToggle.setImageResource(mSource == SOURCE_MODRINTH
            ? R.drawable.ic_modrinth : R.drawable.ic_curseforge);
        String[] labels = mSource == SOURCE_MODRINTH ? MODRINTH_SORT_LABELS : CF_SORT_LABELS;
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

        // Версия: приоритет — выбранная в боковой панели, потом фильтр инстанса
        final String version = mSelectedVersion != null ? mSelectedVersion
            : (mFilterEnabled && !mVersionFilter.isEmpty() ? mVersionFilter : null);

        // Лоадер: приоритет — выбранный в боковой панели, потом из инстанса
        final String loaderRaw = mSelectedLoader != null
            ? buildRawVersionForLoader(mSelectedLoader)
            : (mFilterEnabled && !mLoaderFilter.isEmpty() ? mRawVersion : null);

        final int source = mSource;
        final int offset = (page - 1) * PAGE_SIZE;
        final String sortBy = source == SOURCE_MODRINTH
            ? MODRINTH_SORT_VALUES[mSortIndex]
            : CF_SORT_VALUES[mSortIndex];

        mExecutor.execute(() -> {
            try {
                List<JSONObject> results;
                int[] totalHolder = new int[1];

                if (source == SOURCE_MODRINTH) {
                    results = ModrinthApi.searchMods(query, version, loaderRaw, offset, PAGE_SIZE, sortBy, totalHolder);
                } else {
                    results = CurseForgeApi.searchMods(query, version, loaderRaw, offset, PAGE_SIZE, sortBy, totalHolder);
                }

                final List<JSONObject> r = results;
                final int t = totalHolder[0];
                mHandler.post(() -> {
                    hideLoading();
                    mTotalPages = Math.max(1, (int) Math.ceil((double) t / PAGE_SIZE));

                    mAdapter.setSource(source == SOURCE_MODRINTH);
                    mAdapter.setFilterEnabled(mFilterEnabled);
                    mAdapter.setVersionFilter(version != null ? version : "");
                    mAdapter.setLoaderFilter(mSelectedLoader != null ? mSelectedLoader
                        : (mFilterEnabled ? mLoaderFilter : ""));

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

    // Строим rawVersion для передачи лоадера в API
    private String buildRawVersionForLoader(String loader) {
        return loader + "-dummy";
    }

    private void onInstallClick(JSONObject mod, String selectedVersion, String selectedLoader) {
        if (mSource == SOURCE_MODRINTH) installModrinth(mod, selectedVersion, selectedLoader);
        else installCurseForge(mod, selectedVersion, selectedLoader);
    }

    // ---- Modrinth ----

    private void installModrinth(JSONObject mod, String version, String rawVersion) {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mExecutor.execute(() -> {
            try {
                String projectId = mod.optString("project_id", mod.optString("slug", ""));
                JSONArray versions = ModrinthApi.getProjectVersions(projectId, version, rawVersion);
                if (versions == null || versions.length() == 0) {
                    mHandler.post(() -> { hideLoading(); Toast.makeText(getContext(), "No versions found", Toast.LENGTH_SHORT).show(); });
                    return;
                }
                JSONObject latest = versions.getJSONObject(0);
                String modName = mod.optString("title", "?");
                mHandler.post(() -> { hideLoading(); doInstallModrinth(modName, latest); });
            } catch (Exception e) {
                mHandler.post(() -> { hideLoading(); Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private void doInstallModrinth(String modName, JSONObject version) {
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
                String msg = "✓ " + modName + (deps.isEmpty() ? "" : " + " + deps.size() + " deps");
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
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

    // ---- CurseForge ----

    private void installCurseForge(JSONObject mod, String version, String rawVersion) {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mExecutor.execute(() -> {
            try {
                int modId = mod.getInt("id");
                JSONArray files = CurseForgeApi.getModFiles(modId, version, rawVersion);
                if (files == null || files.length() == 0) {
                    mHandler.post(() -> { hideLoading(); Toast.makeText(getContext(), "No versions found", Toast.LENGTH_SHORT).show(); });
                    return;
                }
                JSONObject latest = files.getJSONObject(0);
                String modName = mod.optString("name", "?");
                mHandler.post(() -> { hideLoading(); doInstallCF(modName, latest, version, rawVersion); });
            } catch (Exception e) {
                mHandler.post(() -> { hideLoading(); Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private void doInstallCF(String modName, JSONObject file, String version, String rawVersion) {
        mProgress.setIndeterminate(false);
        mProgress.setProgress(0);
        mProgress.setVisibility(View.VISIBLE);
        mExecutor.execute(() -> {
            try {
                File modsDir = getModsDir();
                if (modsDir == null) return;
                String url = file.getString("downloadUrl");
                String fileName = file.getString("fileName");
                List<String[]> deps = resolveCFDeps(file, version, rawVersion);
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
                String msg = "✓ " + modName + (deps.isEmpty() ? "" : " + " + deps.size() + " deps");
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                mHandler.post(() -> { mProgress.setVisibility(View.GONE); Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private List<String[]> resolveCFDeps(JSONObject file, String version, String rawVersion) {
        List<String[]> result = new ArrayList<>();
        try {
            JSONArray deps = file.optJSONArray("dependencies");
            if (deps == null) return result;
            for (int i = 0; i < deps.length(); i++) {
                JSONObject dep = deps.getJSONObject(i);
                if (dep.optInt("relationType", 0) == 3) {
                    JSONArray df = CurseForgeApi.getModFiles(dep.getInt("modId"), version, rawVersion);
                    if (df != null && df.length() > 0) {
                        JSONObject f = df.getJSONObject(0);
                        result.add(new String[]{f.getString("downloadUrl"), f.getString("fileName")});
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
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
