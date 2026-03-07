package net.kdt.pojavlaunch.modmanager;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import java.io.File;
import java.util.List;

public class InstalledModAdapter extends RecyclerView.Adapter<InstalledModAdapter.ViewHolder> {

    public interface OnModChangedListener {
        void onModChanged(File mod);
    }

    private List<File> mMods;
    private final OnModChangedListener mListener;

    public InstalledModAdapter(List<File> mods, OnModChangedListener listener) {
        this.mMods = mods;
        this.mListener = listener;
    }

    public void setMods(List<File> mods) {
        this.mMods = mods;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_installed_mod, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File mod = mMods.get(position);
        boolean enabled = !mod.getName().endsWith(".disabled");
        String displayName = mod.getName()
                .replace(".jar.disabled", "")
                .replace(".jar", "");

        holder.mName.setText(displayName);
        holder.mToggle.setChecked(enabled);
        holder.mToggle.setOnCheckedChangeListener(null);
        holder.mToggle.setOnCheckedChangeListener((btn, isChecked) -> {
            File parent = mod.getParentFile();
            File newFile;
            if (isChecked) {
                newFile = new File(parent, displayName + ".jar");
            } else {
                newFile = new File(parent, displayName + ".jar.disabled");
            }
            mod.renameTo(newFile);
            mMods.set(position, newFile);
            if (mListener != null) mListener.onModChanged(newFile);
        });

        holder.mDelete.setOnClickListener(v -> {
            mod.delete();
            mMods.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, mMods.size());
            if (mListener != null) mListener.onModChanged(mod);
        });
    }

    @Override
    public int getItemCount() {
        return mMods.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView mName;
        Switch mToggle;
        ImageButton mDelete;

        ViewHolder(View view) {
            super(view);
            mName = view.findViewById(R.id.mod_item_name);
            mToggle = view.findViewById(R.id.mod_item_toggle);
            mDelete = view.findViewById(R.id.mod_item_delete);
        }
    }
}
