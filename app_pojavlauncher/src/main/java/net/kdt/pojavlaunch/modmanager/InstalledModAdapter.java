package net.kdt.pojavlaunch.modmanager;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class InstalledModAdapter extends RecyclerView.Adapter<InstalledModAdapter.ViewHolder> {

    public interface OnModChangedListener {
        void onModChanged(File mod);
    }

    private List<File> mMods;
    private final OnModChangedListener mListener;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(3);
    private final Handler mHandler = new Handler(Looper.getMainLooper());

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
            File newFile = new File(parent, displayName + (isChecked ? ".jar" : ".jar.disabled"));
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

        holder.mCurseForge.setOnClickListener(v -> {
            String url = "https://www.curseforge.com/minecraft/mc-mods/search?search="
                    + displayName.replace(" ", "+");
            v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });

        // Загружаем иконку из jar файла
        holder.mIcon.setImageResource(R.drawable.ic_mojo_full);
        holder.mIcon.setTag(position);
        loadIconFromJar(holder.mIcon, mod, position);
    }

    private void loadIconFromJar(ImageView view, File modFile, int position) {
        // Ищем только .jar файл (не .disabled)
        File jarFile = modFile;
        if (modFile.getName().endsWith(".disabled")) {
            String baseName = modFile.getName().replace(".jar.disabled", ".jar");
            jarFile = new File(modFile.getParent(), baseName);
            if (!jarFile.exists()) jarFile = modFile; // фолбэк
        }

        final File finalJar = jarFile;
        mExecutor.execute(() -> {
            Bitmap bmp = null;
            try (ZipFile zip = new ZipFile(finalJar)) {
                // Ищем icon.png в корне jar
                ZipEntry entry = zip.getEntry("icon.png");
                if (entry == null) {
                    // Некоторые моды кладут иконку в assets/
                    java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        String name = e.getName().toLowerCase();
                        if (name.endsWith("icon.png") && !e.isDirectory()) {
                            entry = e;
                            break;
                        }
                    }
                }
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        bmp = BitmapFactory.decodeStream(in);
                    }
                }
            } catch (Exception ignored) {}

            final Bitmap finalBmp = bmp;
            mHandler.post(() -> {
                if (view.getTag() != null && (int) view.getTag() == position) {
                    if (finalBmp != null) {
                        view.setImageBitmap(finalBmp);
                    } else {
                        view.setImageResource(R.drawable.ic_mojo_full);
                    }
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        return mMods.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView mName;
        SwitchCompat mToggle;
        ImageButton mDelete;
        ImageButton mCurseForge;
        ImageView mIcon;

        ViewHolder(View view) {
            super(view);
            mName = view.findViewById(R.id.mod_item_name);
            mToggle = view.findViewById(R.id.mod_item_toggle);
            mDelete = view.findViewById(R.id.mod_item_delete);
            mCurseForge = view.findViewById(R.id.mod_item_curseforge);
            mIcon = view.findViewById(R.id.mod_item_icon);
        }
    }
}
