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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        // Открыть страницу мода на CurseForge
        holder.mCurseForge.setOnClickListener(v -> {
            String searchUrl = "https://www.curseforge.com/minecraft/mc-mods/search?search="
                    + displayName.replace(" ", "+");
            v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)));
        });

        // Загружаем иконку
        holder.mIcon.setImageResource(R.drawable.ic_curseforge);
        holder.mIcon.setTag(position);
        loadModIcon(holder.mIcon, displayName, position);
    }

    private void loadModIcon(ImageView view, String modName, int position) {
        mExecutor.execute(() -> {
            try {
                String url = "https://api.curseforge.com/v1/mods/search?gameId=432&classId=6&pageSize=1&searchFilter="
                        + URLEncoder.encode(modName, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("x-api-key", CurseForgeApi.API_KEY);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.connect();

                InputStream in = conn.getInputStream();
                byte[] buf = new byte[65536];
                int n = 0, read;
                while ((read = in.read(buf, n, buf.length - n)) != -1) n += read;
                String json = new String(buf, 0, n, "UTF-8");
                conn.disconnect();

                JSONObject response = new JSONObject(json);
                JSONArray data = response.optJSONArray("data");
                if (data != null && data.length() > 0) {
                    JSONObject logo = data.getJSONObject(0).optJSONObject("logo");
                    if (logo != null) {
                        String iconUrl = logo.optString("thumbnailUrl", null);
                        if (iconUrl != null) {
                            HttpURLConnection iconConn = (HttpURLConnection) new URL(iconUrl).openConnection();
                            iconConn.setConnectTimeout(5000);
                            iconConn.setReadTimeout(5000);
                            iconConn.connect();
                            Bitmap bmp = BitmapFactory.decodeStream(iconConn.getInputStream());
                            iconConn.disconnect();
                            if (bmp != null) {
                                mHandler.post(() -> {
                                    if (view.getTag() != null && (int) view.getTag() == position) {
                                        view.setImageBitmap(bmp);
                                    }
                                });
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
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
