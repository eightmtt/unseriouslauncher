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
    private static final String[] MODRINTH_SORT_LABELS = {"Downloads", "Newest", "Updated", "Relevance", "Follows"};
    private static final String[] MODRINTH_SORT_VALUES = {"downloads", "newest", "updated", "relevance", "follows"};

    // Сортировка CurseForge
    private static final String[] CF_SORT_LABELS = {"Popularity", "Last Updated", "Name", "Downloads", "Newest"};
    private static final String[] CF_SORT_VALUES = {"2", "1", "3", "6", "10"};

    private int mSortIndex = 0;
    private int mSource = SOURCE_MODRINTH;
    private int mPage = 1;
    private int mTotalPages = 1;
    private String mLastQuery = "";

    private EditText mSearchEdit;
    private TextView mSearchButton;
    private TextView mFilterToggle;
    private TextView mSourceToggle;
    private TextView mSortButton;
    private RecyclerView mModList;
    private ProgressBar mProgress;
    private TextView mStatusText;
    private LinearLayout mPagination;
    private TextView mPrevButton;
    private TextView mNextButton;
    private TextView mPageText;
    private View mOverlay;
    private MarketModAdapter mAdapter;

    private String mRawVersion = "";
    private String mVersionFilter = "";
    private String mLoaderFilter = "";
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
        mLoaderFilter = extractLoader(mRawVersion);

        mAdapter = new MarketModAdapter(new ArrayList<>(), this::onInstallClick);
        mModList.setLayoutManager(new LinearLayoutManager(getContext()));
        mModList.setAdapter(mAdapter);

        // Прячем шапку при скролле
        mOverlay.post(() -> {
            final float overlayH = mOverlay.getHeight();
            mModList.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    float newY = mOverlay.getY() - dy;
                    mOverlay.setY(Math.max(-overlayH, Math.min(0f, newY)));
                }
            });
        });

        updateUI();

        mSearchButton.setOnClickListener(v -> startNewSearch());
        mSearchEdit.setOnEditorActionListener((v, actionId, event) -> { startNewSearch(); return true; });

        mFilterToggle.setOnClickListener(v -> {
            mFilterEnabled = !mFilterEnabled;
            updateUI();
            startNewSearch();
        });

        mSourceToggle.setOnClickListener(v -> {
            mSource = (mSource == SOURCE_MODRINTH) ? SOURCE_CURSEFORGE : SOURCE_MODRINTH;
            mSortIndex = 0;
            updateUI();
            startNewSearch();
        });

        mSortButton.setOnClickListener(v -> showSortDialog());

        mPrevButton.setOnClickListener(v -> { if (mPage > 1) { mPage--; search(mLastQuery, mPage); } });
        mNextButton.setOnClickListener(v -> { if (mPage < mTotalPages) { mPage++; search(mLastQuery, mPage); } });

        startNewSearch();
    }

    private void showSortDialog() {
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
    }

    private void startNewSearch() {
        mPage = 1;
        mLastQuery = mSearchEdit.getText().toString().trim();
        search(mLastQuery, mPage);
    }

    private void updateUI() {
        mSourceToggle.setText(mSource == SOURCE_MODRINTH ? "Modrinth" : "CurseForge");

        String[] labels = mSource == SOURCE_MODRINTH ? MODRINTH_SORT_LABELS : CF_SORT_LABELS;
        mSortButton.setText("↓ " + labels[mSortIndex]);

        if (mVersionFilter.isEmpty()) {
            mFilterToggle.setVisibility(View.GONE);
        } else {
            mFilterToggle.setVisibility(View.VISIBLE);
            mFilterToggle.setText(mFilterEnabled
                ? "🔍 " + mVersionFilter + (mLoaderFilter.isEmpty() ? "" : " · " + mLoaderFilter)
                : "Filter off");
        }

        mAdapter.setSource(mSource == SOURCE_MODRINTH);
        mAdapter.setFilterEnabled(mFilterEnabled);
        mAdapter.setVersionFilter(mFilterEnabled ? mVersionFilter : "");
        mAdapter.setLoaderFilter(mFilterEnabled ? mLoaderFilter : "");
    }

    private void showLoading() {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mStatusText.setVisibility(View.GONE);
        mModList.setVisibility(View.GONE);
        mPagination.setVisibility(View.GONE);
    }

    private void hideLoading() { mProgress.setVisibility(View.GONE); }

    private void showError(String msg) {
        mStatusText.setText("⚠ " + msg);
        mStatusText.setVisibility(View.VISIBLE);
    }

    private void updatePagination() {
        if (mTotalPages <= 1) { mPagination.setVisibility(View.GONE); return; }
        mPagination.setVisibility(View.VISIBLE);
        mPageText.setText(mPage + " / " + mTotalPages);
        mPrevButton.setAlpha(mPage > 1 ? 1f : 0.3f);
        mNextButton.setAlpha(mPage < mTotalPages ? 1f : 0.3f);
    }

    private void search(String query, int page) {
        showLoading();
        final String version = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;
        final String rawVersion = mFilterEnabled ? mRawVersion : null;
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
                    results = ModrinthApi.searchMods(query, version, rawVersion, offset, PAGE_SIZE, sortBy, totalHolder);
                } else {
                    results = CurseForgeApi.searchMods(query, version, rawVersion, offset, PAGE_SIZE, sortBy, totalHolder);
                }

                final List<JSONObject> r = results;
                final int t = totalHolder[0];
                mHandler.post(() -> {
                    hideLoading();
                    mTotalPages = Math.max(1, (int) Math.ceil((double) t / PAGE_SIZE));
                    if (r.isEmpty()) {
                        showError("Nothing found");
                    } else {
                        mAdapter.setSource(source == SOURCE_MODRINTH);
                        mAdapter.setFilterEnabled(mFilterEnabled);
                        mAdapter.setVersionFilter(mFilterEnabled ? mVersionFilter : "");
                        mAdapter.setLoaderFilter(mFilterEnabled ? mLoaderFilter : "");
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
                    showError(e.getClass().getSimpleName() + ": " + e.getMessage());
                });
            }
        });
    }

    private void onInstallClick(JSONObject mod, String selectedVersion, String selectedLoader) {
        if (mSource == SOURCE_MODRINTH) {
            installModrinth(mod, selectedVersion, selectedLoader);
        } else {
            installCurseForge(mod, selectedVersion, selectedLoader);
        }
    }

    // ---- Modrinth установка ----

    private void installModrinth(JSONObject mod, String version, String rawVersion) {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);

        mExecutor.execute(() -> {
            try {
                String projectId = mod.optString("project_id", mod.optString("slug", ""));
                JSONArray versions = ModrinthApi.getProjectVersions(projectId, version, rawVersion);

                if (versions == null || versions.length() == 0) {
                    mHandler.post(() -> {
                        hideLoading();
                        Toast.makeText(getContext(), "No versions found", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // Берём первую (последнюю) версию
                JSONObject latestVersion = versions.getJSONObject(0);
                String modName = mod.optString("title", "?");

                mHandler.post(() -> {
                    hideLoading();
                    confirmModrinthInstall(modName, latestVersion);
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirmModrinthInstall(String modName, JSONObject version) {
        try {
            JSONArray files = version.optJSONArray("files");
            if (files == null || files.length() == 0) {
                Toast.makeText(getContext(), "No files", Toast.LENGTH_SHORT).show();
                return;
            }
            String fileName = files.getJSONObject(0).optString("filename", "mod.jar");
            int deps = countModrinthDeps(version);
            String msg = fileName + (deps > 0 ? "\n\n+ " + deps + " dependencies" : "");

            new AlertDialog.Builder(requireContext())
                .setTitle("Install " + modName + "?")
                .setMessage(msg)
                .setPositiveButton("Install", (d, w) -> doInstallModrinth(modName, version))
                .setNegativeButton("Cancel", null)
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
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
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

    // ---- CurseForge установка ----

    private void installCurseForge(JSONObject mod, String version, String rawVersion) {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);

        mExecutor.execute(() -> {
            try {
                int modId = mod.getInt("id");
                JSONArray files = CurseForgeApi.getModFiles(modId, version, rawVersion);

                if (files == null || files.length() == 0) {
                    mHandler.post(() -> {
                        hideLoading();
                        Toast.makeText(getContext(), "No versions found", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                JSONObject latestFile = files.getJSONObject(0);
                String modName = mod.optString("name", "?");
                mHandler.post(() -> {
                    hideLoading();
                    confirmCFInstall(modName, latestFile, version, rawVersion);
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirmCFInstall(String modName, JSONObject file, String version, String rawVersion) {
        try {
            int deps = countCFDeps(file);
            String msg = file.optString("fileName", "?") + (deps > 0 ? "\n\n+ " + deps + " dependencies" : "");
            new AlertDialog.Builder(requireContext())
                .setTitle("Install " + modName + "?")
                .setMessage(msg)
                .setPositiveButton("Install", (d, w) -> doInstallCF(modName, file, version, rawVersion))
                .setNegativeButton("Cancel", null)
                .show();
        } catch (Exception ignored) {}
    }

    private int countCFDeps(JSONObject file) {
        try {
            JSONArray deps = file.optJSONArray("dependencies");
            if (deps == null) return 0;
            int count = 0;
            for (int i = 0; i < deps.length(); i++)
                if (deps.getJSONObject(i).optInt("relationType", 0) == 3) count++;
            return count;
        } catch (Exception e) { return 0; }
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
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
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
