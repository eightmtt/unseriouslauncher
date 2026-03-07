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
import net.kdt.pojavlaunch.Tools;
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

        // Получаем версию из аргументов
        if (getArguments() != null) {
            mVersionFilter = getArguments().getString(ARG_VERSION, "");
        }
        if (mVersionFilter == null) mVersionFilter = "";

        updateFilterButton();

        mAdapter = new MarketModAdapter(new ArrayList<>(), mod -> showInstallDialog(mod));
        mModList.setLayoutManager(new LinearLayoutManager(getContext()));
        mModList.setAdapter(mAdapter);

        mSearchButton.setOnClickListener(v -> search(mSearchEdit.getText().toString().trim()));

        mFilterToggle.setOnClickListener(v -> {
            mFilterEnabled = !mFilterEnabled;
            updateFilterButton();
            search(mSearchEdit.getText().toString().trim());
        });

        // Начальный поиск
        search("");
    }

    private void updateFilterButton() {
        if (mVersionFilter.isEmpty()) {
            mFilterToggle.setVisibility(View.GONE);
            return;
        }
        mFilterToggle.setVisibility(View.VISIBLE);
        if (mFilterEnabled) {
            mFilterToggle.setText("Фильтр: " + mVersionFilter + " ✓");
        } else {
            mFilterToggle.setText("Фильтр выключен");
        }
    }

    private void search(String query) {
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

    private void showInstallDialog(JSONObject mod) {
        try {
            String name = mod.getString("name");
            String summary = mod.optString("summary", "");
            int modId = mod.getInt("id");

            new AlertDialog.Builder(requireContext())
                .setTitle(name)
                .setMessage(summary + "\n\nЗагрузить зависимости автоматически?")
                .setPositiveButton("Установить", (d, w) -> installMod(modId, name))
                .setNegativeButton("Отмена", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Ошибка", Toast.LENGTH_SHORT).show();
        }
    }

    private void installMod(int modId, String modName) {
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

                // Получаем версии мода
                String mcVersion = (mFilterEnabled && !mVersionFilter.isEmpty()) ? mVersionFilter : null;
                JSONArray files = CurseForgeApi.getModFiles(modId, mcVersion);

                if (files == null || files.length() == 0) {
                    mHandler.post(() -> {
                        mProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Нет файлов для этой версии", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                JSONObject latestFile = files.getJSONObject(0);
                String downloadUrl = latestFile.getString("downloadUrl");
                String fileName = latestFile.getString("fileName");

                // Скачиваем зависимости
                JSONArray deps = latestFile.optJSONArray("dependencies");
                List<String[]> depDownloads = new ArrayList<>();
                if (deps != null) {
                    for (int i = 0; i < deps.length(); i++) {
                        JSONObject dep = deps.getJSONObject(i);
                        if (dep.optInt("relationType", 0) == 3) { // required
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

                // Скачиваем основной мод
                CurseForgeApi.downloadFile(downloadUrl, new File(modsDir, fileName));

                // Скачиваем зависимости
                for (String[] dep : depDownloads) {
                    CurseForgeApi.downloadFile(dep[0], new File(modsDir, dep[1]));
                }

                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    String msg = "Установлен: " + modName;
                    if (!depDownloads.isEmpty()) msg += " + " + depDownloads.size() + " зависимостей";
                    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Ошибка установки: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
