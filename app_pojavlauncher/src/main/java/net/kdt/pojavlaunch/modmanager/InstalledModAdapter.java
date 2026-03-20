package net.kdt.pojavlaunch.modmanager;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

        // Затемняем иконку если мод выключен
        holder.mIcon.setAlpha(enabled ? 1.0f : 0.4f);
        if (!enabled) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            holder.mIcon.setColorFilter(new ColorMatrixColorFilter(cm));
        } else {
            holder.mIcon.setColorFilter(null);
        }

        // Иконка из jar
        holder.mIcon.setImageResource(R.drawable.ic_mojo_full);
        holder.mIcon.setTag(position);
        loadIconFromJar(holder.mIcon, mod, position);

        // Нажатие на иконку — открываем страницу мода
        holder.mIcon.setOnClickListener(v -> {
            String slug = extractSlug(displayName);
            String url = "https://modrinth.com/mod/" + slug;
            v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });

        // Кнопка ⋮ — popup меню
        holder.mMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(v.getContext(), v);
            popup.getMenu().add(0, 1, 0, enabled ? "Выключить" : "Включить");
            popup.getMenu().add(0, 2, 1, "Удалить");

            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    File parent = mod.getParentFile();
                    File newFile = new File(parent, displayName + (enabled ? ".jar.disabled" : ".jar"));
                    mod.renameTo(newFile);
                    mMods.set(position, newFile);
                    if (mListener != null) mListener.onModChanged(newFile);
                    return true;
                } else if (item.getItemId() == 2) {
                    mod.delete();
                    mMods.remove(position);
                    notifyItemRemoved(position);
                    notifyItemRangeChanged(position, mMods.size());
                    if (mListener != null) mListener.onModChanged(mod);
                    return true;
                }
                return false;
            });
            popup.show();
        });
    }

    private String extractSlug(String fileName) {
        if (fileName == null || fileName.isEmpty()) return fileName;
        String[] parts = fileName.split("-");
        StringBuilder slug = new StringBuilder();
        for (String part : parts) {
            if (part.matches("\\d+.*") || part.contains("+")) break;
            if (slug.length() > 0) slug.append("-");
            slug.append(part.toLowerCase());
        }
        return slug.length() > 0 ? slug.toString() : fileName.toLowerCase();
    }

    private void loadIconFromJar(ImageView view, File modFile, int position) {
        final File jarFile = modFile.getName().endsWith(".disabled")
            ? new File(modFile.getParent(), modFile.getName().replace(".jar.disabled", ".jar"))
            : modFile;

        mExecutor.execute(() -> {
            Bitmap bmp = null;
            try (ZipFile zip = new ZipFile(jarFile.exists() ? jarFile : modFile)) {
                ZipEntry entry = zip.getEntry("icon.png");
                if (entry == null) {
                    java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.getName().toLowerCase().endsWith("icon.png") && !e.isDirectory()) {
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
    public int getItemCount() { return mMods.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView mName;
        ImageView mIcon;
        ImageButton mMenu;

        ViewHolder(View view) {
            super(view);
            mName = view.findViewById(R.id.mod_item_name);
            mIcon = view.findViewById(R.id.mod_item_icon);
            mMenu = view.findViewById(R.id.mod_item_menu);
        }
    }
}
