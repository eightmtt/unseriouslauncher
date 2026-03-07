package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.modmanager.InstalledModAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ModManagerFragment extends Fragment {
    public static final String TAG = "ModManagerFragment";

    private RecyclerView mModList;
    private TextView mEmptyText;
    private Button mOpenMarketButton;
    private InstalledModAdapter mAdapter;
    private Instance mInstance;

    public ModManagerFragment() {
        super(R.layout.fragment_mod_manager);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mModList = view.findViewById(R.id.mod_manager_list);
        mEmptyText = view.findViewById(R.id.mod_manager_empty_text);
        mOpenMarketButton = view.findViewById(R.id.mod_manager_market_button);

        mInstance = Instances.loadSelectedInstance();

        mAdapter = new InstalledModAdapter(getMods(), mod -> refreshList());
        mModList.setLayoutManager(new LinearLayoutManager(getContext()));
        mModList.setAdapter(mAdapter);

        mOpenMarketButton.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            if (mInstance != null) {
                bundle.putString(ModMarketFragment.ARG_VERSION, mInstance.versionId);
            }
            Tools.swapFragment(requireActivity(), ModMarketFragment.class, ModMarketFragment.TAG, bundle);
        });

        refreshList();
    }

    private List<File> getMods() {
        List<File> mods = new ArrayList<>();
        if (mInstance == null) return mods;
        File modsDir = new File(mInstance.getGameDirectory(), "mods");
        if (!modsDir.exists()) modsDir.mkdirs();
        File[] files = modsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".jar") || f.getName().endsWith(".jar.disabled")) {
                    mods.add(f);
                }
            }
        }
        return mods;
    }

    private void refreshList() {
        List<File> mods = getMods();
        mAdapter.setMods(mods);
        mEmptyText.setVisibility(mods.isEmpty() ? View.VISIBLE : View.GONE);
        mModList.setVisibility(mods.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
