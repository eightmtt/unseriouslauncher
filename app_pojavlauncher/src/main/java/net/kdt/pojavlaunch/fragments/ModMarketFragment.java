package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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

    // Источник модов
    private static final int SOURCE_CURSEFORGE = 0;
    private static final int SOURCE_MODRINTH = 1;
    private int mSource = SOURCE_CURSEFORGE;

    private EditText mSearchEdit;
    private Button mSearchButton;
    private Button mFilterToggle;
    private Button mSourceToggle;
    private RecyclerView mModList;
    private ProgressBar mProgress;
    private TextView mStatusText;
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
        mModList = view.findViewById(R.id.market_mod_list);
        mProgress = view.findViewById(R.id.market_progress);
        mStatusText = view.findViewById(R.id.market_status_text);

        if (getArguments() != null) {
            mRawVersion = getArguments().getString(ARG_VERSION, "");
            if (mRawVersion == null) mRawVersion = "";
        }

        mAdapter = new MarketModAdapter(new ArrayList<>(), this::onModClick);
        mModList.setLayoutManager(new LinearLayoutManager(getContext()));
        mModList.setAdapter(mAdapter);

        mSearchButton.setOnClickListener(v -> search(mSearchEdit.getText().toString().trim()));

        mFilterToggle.setOnClickListener(v -> {
            mFilterEnabled = !mFilterEnabled;
            updateFilterButton();
            search(mSearchEdit.getText().toString().trim());
        });

        mSourceToggle.setOnClickListener(v -> {
            mSource = (mSource == SOURCE_CURSEFORGE) ? SOURCE_MODRINTH : SOURCE_CURSEFORGE;
            updateSourceButton();
            search(mSearchEdit.getText().toString().trim());
        });

        updateSourceButton();
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mFilterToggle.setVisibility(View.GONE);

        // Парсим версию в фоне
        mExecutor.execute(() -> {
            String parsed = CurseForgeApi.extractMcVersion(mRawVersion);
            mHandler.post(() -> {
                mVersionFilter = parsed;
                updateFilterButton();
                search("");
            });
        });
    }

    private void updateFilterButton() {
        if (mVersionFilter.isEmpty()) {
            mFilterToggle.setVisibility(View.GONE);
            return;
        }
        mFilterToggle.setVisibility(View.VISIBLE);
        mFilterToggle.setText(mFilterEnabled ? "Фильтр: " + mVersionFilter + " ✓" : "Фильтр выключен");
    }

    private void updateSourceButton() {
        mSourceToggle.setText(mSource == SOURCE_CURSEFORGE ? "CurseForge" : "Modrinth");
    }

    private void search(String query) {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mStatusText.setVisibility(View.GONE);
        mModList.setVisibility(View.GONE);

        String version = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;
        String rawVersion = mFilterEnabled ? mRawVersion : null;
        int source = mSource;

        mExecutor.execute(() -> {
            try {
                List<JSONObject> results;
                if (source == SOURCE_MODRINTH) {
                    results = ModrinthApi.searchMods(query, version, rawVersion);
                } else {
                    results = CurseForgeApi.searchMods(query, version, rawVersion);
                }
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    if (results.isEmpty()) {
                        mStatusText.setText("Ничего не найдено");
                        mStatusText.setVisibility(View.VISIBLE);
                    } else {
                        mModList.setVisibility(View.VISIBLE);
                        mAdapter.setMods(results);
                    }
                });
            } catch (Throwable e) {
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    mStatusText.setText("Ошибка: " + e.getClass().getName() + "\n" + e.getMessage());
                    mStatusText.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void onModClick(JSONObject mod) {
        if (mSource == SOURCE_MODRINTH) {
            showModrinthVersionDialog(mod);
        } else {
            showCurseForgeVersionDialog(mod);
        }
    }

    // ---- CurseForge ----

    private void showCurseForgeVersionDialog(JSONObject mod) {
        try {
            String name = mod.optString("name", "?");
            int modId = mod.getInt("id");
            String version = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;
            String rawVersion = mFilterEnabled ? mRawVersion : null;

            mProgress.setIndeterminate(true);
            mProgress.setVisibility(View.VISIBLE);

            mExecutor.execute(() -> {
                try {
                    JSONArray files = CurseForgeApi.getModFiles(modId, version, rawVersion);
                    mHandler.post(() -> {
                        mProgress.setVisibility(View.GONE);
                        if (files == null || files.length() == 0) {
                            Toast.makeText(getContext(), "Нет доступных версий", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String[] names = new String[files.length()];
                        for (int i = 0; i < files.length(); i++) {
                            try {
                                JSONObject f = files.getJSONObject(i);
                                String fn = f.optString("fileName", "?");
                                JSONArray gv = f.optJSONArray("gameVersions");
                                names[i] = fn + (gv != null && gv.length() > 0 ? " [" + gv.getString(0) + "]" : "");
                            } catch (Exception e) {
                                names[i] = "Версия " + i;
                            }
                        }
                        new AlertDialog.Builder(requireContext())
                            .setTitle("Выберите версию: " + name)
                            .setItems(names, (d, which) -> {
                                try {
                                    showCurseForgeInstallConfirm(name, files.getJSONObject(which), version, rawVersion);
                                } catch (Exception e) {
                                    Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                    });
                } catch (Exception e) {
                    mHandler.post(() -> {
                        mProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Ошибка загрузки версий", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
        }
    }

    private void showCurseForgeInstallConfirm(String modName, JSONObject file, String version, String rawVersion) {
        try {
            JSONArray deps = file.optJSONArray("dependencies");
            int requiredDeps = 0;
            if (deps != null) {
                for (int i = 0; i < deps.length(); i++) {
                    if (deps.getJSONObject(i).optInt("relationType", 0) == 3) requiredDeps++;
                }
            }
            String msg = "Файл: " + file.optString("fileName", "?");
            if (requiredDeps > 0) msg += "\n\nЗависимостей: " + requiredDeps + " (установятся автоматически)";

            new AlertDialog.Builder(requireContext())
                .setTitle("Установить " + modName + "?")
                .setMessage(msg)
                .setPositiveButton("Установить", (d, w) -> installCurseForgeMod(modName, file, version, rawVersion))
                .setNegativeButton("Отмена", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
        }
    }

    private void installCurseForgeMod(String modName, JSONObject file, String version, String rawVersion) {
        mProgress.setIndeterminate(false);
        mProgress.setProgress(0);
        mProgress.setVisibility(View.VISIBLE);

        mExecutor.execute(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) {
                    mHandler.post(() -> Toast.makeText(getContext(), "Инстанс не найден", Toast.LENGTH_SHORT).show());
                    return;
                }
                File modsDir = new File(instance.getGameDirectory(), "mods");
                if (!modsDir.exists()) modsDir.mkdirs();

                String downloadUrl = file.getString("downloadUrl");
                String fileName = file.getString("fileName");

                JSONArray deps = file.optJSONArray("dependencies");
                List<String[]> depDownloads = new ArrayList<>();
                if (deps != null) {
                    for (int i = 0; i < deps.length(); i++) {
                        JSONObject dep = deps.getJSONObject(i);
                        if (dep.optInt("relationType", 0) == 3) {
                            int depId = dep.getInt("modId");
                            JSONArray depFiles = CurseForgeApi.getModFiles(depId, version, rawVersion);
                            if (depFiles != null && depFiles.length() > 0) {
                                JSONObject depFile = depFiles.getJSONObject(0);
                                depDownloads.add(new String[]{
                                    depFile.getString("downloadUrl"),
                                    depFile.getString("fileName")
                                });
                            }
                        }
                    }
                }

                int total = 1 + depDownloads.size();
                final int[] current = {0};

                CurseForgeApi.downloadFileWithProgress(downloadUrl, new File(modsDir, fileName), percent -> {
                    mHandler.post(() -> mProgress.setProgress((current[0] * 100 + percent) / total));
                });
                current[0]++;

                for (String[] dep : depDownloads) {
                    CurseForgeApi.downloadFileWithProgress(dep[0], new File(modsDir, dep[1]), percent -> {
                        mHandler.post(() -> mProgress.setProgress((current[0] * 100 + percent) / total));
                    });
                    current[0]++;
                }

                String finalMsg = "Установлен: " + modName + (depDownloads.isEmpty() ? "" : " + " + depDownloads.size() + " зависимостей");
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    mProgress.setProgress(0);
                    Toast.makeText(getContext(), finalMsg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    mProgress.setProgress(0);
                    Toast.makeText(getContext(), "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ---- Modrinth ----

    private void showModrinthVersionDialog(JSONObject mod) {
        try {
            String name = mod.optString("title", "?");
            String projectId = mod.optString("project_id", mod.optString("slug", ""));
            String version = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;
            String rawVersion = mFilterEnabled ? mRawVersion : null;

            mProgress.setIndeterminate(true);
            mProgress.setVisibility(View.VISIBLE);

            mExecutor.execute(() -> {
                try {
                    JSONArray versions = ModrinthApi.getProjectVersions(projectId, version, rawVersion);
                    mHandler.post(() -> {
                        mProgress.setVisibility(View.GONE);
                        if (versions == null || versions.length() == 0) {
                            Toast.makeText(getContext(), "Нет доступных версий", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String[] names = new String[versions.length()];
                        for (int i = 0; i < versions.length(); i++) {
                            try {
                                JSONObject v = versions.getJSONObject(i);
                                String vname = v.optString("name", "?");
                                String vnum = v.optString("version_number", "");
                                JSONArray gameVersions = v.optJSONArray("game_versions");
                                String gv = gameVersions != null && gameVersions.length() > 0
                                    ? " [" + gameVersions.getString(0) + "]" : "";
                                names[i] = vname + " (" + vnum + ")" + gv;
                            } catch (Exception e) {
                                names[i] = "Версия " + i;
                            }
                        }
                        new AlertDialog.Builder(requireContext())
                            .setTitle("Выберите версию: " + name)
                            .setItems(names, (d, which) -> {
                                try {
                                    showModrinthInstallConfirm(name, versions.getJSONObject(which));
                                } catch (Exception e) {
                                    Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                    });
                } catch (Exception e) {
                    mHandler.post(() -> {
                        mProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Ошибка загрузки версий", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
        }
    }

    private void showModrinthInstallConfirm(String modName, JSONObject version) {
        try {
            JSONArray files = version.optJSONArray("files");
            if (files == null || files.length() == 0) {
                Toast.makeText(getContext(), "Нет файлов", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject primaryFile = files.getJSONObject(0);
            String fileName = primaryFile.optString("filename", "mod.jar");

            JSONArray deps = version.optJSONArray("dependencies");
            int requiredDeps = 0;
            if (deps != null) {
                for (int i = 0; i < deps.length(); i++) {
                    if ("required".equals(deps.getJSONObject(i).optString("dependency_type"))) requiredDeps++;
                }
            }

            String msg = "Файл: " + fileName;
            if (requiredDeps > 0) msg += "\n\nЗависимостей: " + requiredDeps + " (установятся автоматически)";

            new AlertDialog.Builder(requireContext())
                .setTitle("Установить " + modName + "?")
                .setMessage(msg)
                .setPositiveButton("Установить", (d, w) -> installModrinthMod(modName, version))
                .setNegativeButton("Отмена", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
        }
    }

    private void installModrinthMod(String modName, JSONObject version) {
        mProgress.setIndeterminate(false);
        mProgress.setProgress(0);
        mProgress.setVisibility(View.VISIBLE);

        mExecutor.execute(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) {
                    mHandler.post(() -> Toast.makeText(getContext(), "Инстанс не найден", Toast.LENGTH_SHORT).show());
                    return;
                }
                File modsDir = new File(instance.getGameDirectory(), "mods");
                if (!modsDir.exists()) modsDir.mkdirs();

                // Основной файл
                JSONArray files = version.getJSONArray("files");
                JSONObject primaryFile = files.getJSONObject(0);
                String downloadUrl = primaryFile.getString("url");
                String fileName = primaryFile.optString("filename", "mod.jar");

                // Зависимости
                JSONArray deps = version.optJSONArray("dependencies");
                List<String[]> depDownloads = new ArrayList<>();
                if (deps != null) {
                    for (int i = 0; i < deps.length(); i++) {
                        JSONObject dep = deps.getJSONObject(i);
                        if ("required".equals(dep.optString("dependency_type"))) {
                            String depVersionId = dep.optString("version_id", "");
                            if (!depVersionId.isEmpty()) {
                                JSONObject depVersion = ModrinthApi.getVersion(depVersionId);
                                if (depVersion != null) {
                                    JSONArray depFiles = depVersion.optJSONArray("files");
                                    if (depFiles != null && depFiles.length() > 0) {
                                        JSONObject df = depFiles.getJSONObject(0);
                                        depDownloads.add(new String[]{
                                            df.getString("url"),
                                            df.optString("filename", "dep.jar")
                                        });
                                    }
                                }
                            }
                        }
                    }
                }

                int total = 1 + depDownloads.size();
                final int[] current = {0};

                CurseForgeApi.downloadFileWithProgress(downloadUrl, new File(modsDir, fileName), percent -> {
                    mHandler.post(() -> mProgress.setProgress((current[0] * 100 + percent) / total));
                });
                current[0]++;

                for (String[] dep : depDownloads) {
                    CurseForgeApi.downloadFileWithProgress(dep[0], new File(modsDir, dep[1]), percent -> {
                        mHandler.post(() -> mProgress.setProgress((current[0] * 100 + percent) / total));
                    });
                    current[0]++;
                }

                String finalMsg = "Установлен: " + modName + (depDownloads.isEmpty() ? "" : " + " + depDownloads.size() + " зависимостей");
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    mProgress.setProgress(0);
                    Toast.makeText(getContext(), finalMsg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    mProgress.setProgress(0);
                    Toast.makeText(getContext(), "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }
}
