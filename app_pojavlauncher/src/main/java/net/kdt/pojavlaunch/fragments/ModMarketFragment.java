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

    private EditText mSearchEdit;
    private Button mSearchButton;
    private Button mFilterToggle;
    private RecyclerView mModList;
    private ProgressBar mProgress;
    private TextView mStatusText;
    private MarketModAdapter mAdapter;

    private String mVersionFilter;
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
        mModList = view.findViewById(R.id.market_mod_list);
        mProgress = view.findViewById(R.id.market_progress);
        mStatusText = view.findViewById(R.id.market_status_text);

        if (getArguments() != null) {
            mVersionFilter = getArguments().getString(ARG_VERSION, "");
        }
        if (mVersionFilter == null) mVersionFilter = "";

        // Сразу извлекаем чистую версию MC для отображения
        String displayVersion = CurseForgeApi.extractMcVersion(mVersionFilter);
        if (!displayVersion.isEmpty()) mVersionFilter = displayVersion;

        updateFilterButton();

        mAdapter = new MarketModAdapter(new ArrayList<>(), this::showVersionDialog);
        mModList.setLayoutManager(new LinearLayoutManager(getContext()));
        mModList.setAdapter(mAdapter);

        mSearchButton.setOnClickListener(v -> search(mSearchEdit.getText().toString().trim()));

        mFilterToggle.setOnClickListener(v -> {
            mFilterEnabled = !mFilterEnabled;
            updateFilterButton();
            search(mSearchEdit.getText().toString().trim());
        });

        search("");
    }

    private void updateFilterButton() {
        if (mVersionFilter.isEmpty()) {
            mFilterToggle.setVisibility(View.GONE);
            return;
        }
        mFilterToggle.setVisibility(View.VISIBLE);
        mFilterToggle.setText(mFilterEnabled ? "Фильтр: " + mVersionFilter + " ✓" : "Фильтр выключен");
    }

    private void search(String query) {
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.VISIBLE);
        mStatusText.setVisibility(View.GONE);
        mModList.setVisibility(View.GONE);

        String version = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;

        mExecutor.execute(() -> {
            try {
                List<JSONObject> results = CurseForgeApi.searchMods(query, version);
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
            } catch (Exception e) {
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    mStatusText.setText("Ошибка: " + e.getMessage());
                    mStatusText.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void showVersionDialog(JSONObject mod) {
        try {
            String name = mod.optString("name", "?");
            int modId = mod.getInt("id");
            String mcVersion = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;

            mProgress.setIndeterminate(true);
            mProgress.setVisibility(View.VISIBLE);

            mExecutor.execute(() -> {
                try {
                    JSONArray files = CurseForgeApi.getModFiles(modId, mcVersion);
                    mHandler.post(() -> {
                        mProgress.setVisibility(View.GONE);
                        if (files == null || files.length() == 0) {
                            Toast.makeText(getContext(), "Нет доступных версий", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        String[] versionNames = new String[files.length()];
                        for (int i = 0; i < files.length(); i++) {
                            try {
                                JSONObject file = files.getJSONObject(i);
                                String fileName = file.optString("fileName", "?");
                                JSONArray gameVersions = file.optJSONArray("gameVersions");
                                String gv = "";
                                if (gameVersions != null && gameVersions.length() > 0) {
                                    gv = " [" + gameVersions.getString(0) + "]";
                                }
                                versionNames[i] = fileName + gv;
                            } catch (Exception e) {
                                versionNames[i] = "Версия " + i;
                            }
                        }

                        new AlertDialog.Builder(requireContext())
                            .setTitle("Выберите версию: " + name)
                            .setItems(versionNames, (d, which) -> {
                                try {
                                    showInstallConfirm(name, files.getJSONObject(which), mcVersion);
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

    private void showInstallConfirm(String modName, JSONObject file, String mcVersion) {
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
                .setPositiveButton("Установить", (d, w) -> installMod(modName, file, mcVersion))
                .setNegativeButton("Отмена", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
        }
    }

    private void installMod(String modName, JSONObject file, String mcVersion) {
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

                // Собираем зависимости
                JSONArray deps = file.optJSONArray("dependencies");
                List<String[]> depDownloads = new ArrayList<>();
                if (deps != null) {
                    for (int i = 0; i < deps.length(); i++) {
                        JSONObject dep = deps.getJSONObject(i);
                        if (dep.optInt("relationType", 0) == 3) {
                            int depId = dep.getInt("modId");
                            JSONArray depFiles = CurseForgeApi.getModFiles(depId, mcVersion);
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

                // Скачиваем основной мод
                CurseForgeApi.downloadFileWithProgress(downloadUrl, new File(modsDir, fileName), percent -> {
                    int overall = (current[0] * 100 + percent) / total;
                    mHandler.post(() -> mProgress.setProgress(overall));
                });
                current[0]++;

                // Скачиваем зависимости
                for (String[] dep : depDownloads) {
                    CurseForgeApi.downloadFileWithProgress(dep[0], new File(modsDir, dep[1]), percent -> {
                        int overall = (current[0] * 100 + percent) / total;
                        mHandler.post(() -> mProgress.setProgress(overall));
                    });
                    current[0]++;
                }

                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    mProgress.setProgress(0);
                    String msg = "Установлен: " + modName;
                    if (!depDownloads.isEmpty()) msg += " + " + depDownloads.size() + " зависимостей";
                    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
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
}
