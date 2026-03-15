package net.kdt.pojavlaunch.modmanager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MarketModAdapter extends RecyclerView.Adapter<MarketModAdapter.ViewHolder> {

    public interface OnModClickListener {
        void onClick(JSONObject mod);
    }

    private List<JSONObject> mMods;
    private final OnModClickListener mListener;
    private final ExecutorService mIconExecutor = Executors.newFixedThreadPool(3);
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public MarketModAdapter(List<JSONObject> mods, OnModClickListener listener) {
        this.mMods = mods;
        this.mListener = listener;
    }

    public void setMods(List<JSONObject> mods) {
        this.mMods = mods;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_market_mod, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        JSONObject mod = mMods.get(position);
        try {
            // Поддержка обоих форматов: CurseForge и Modrinth
            // CurseForge: name, summary, downloadCount, logo.thumbnailUrl
            // Modrinth:   title, description, downloads, icon_url
            String name = mod.optString("title",
                          mod.optString("name", "?"));
            String summary = mod.optString("description",
                             mod.optString("summary", ""));
            int downloads = mod.optInt("downloads",
                            mod.optInt("downloadCount", 0));

            holder.mName.setText(name);
            holder.mSummary.setText(summary);
            holder.mDownloads.setText("↓ " + formatNumber(downloads));

            // Иконка
            holder.mIcon.setImageResource(R.drawable.ic_curseforge);
            holder.mIcon.setTag(position);
            String iconUrl = getIconUrl(mod);
            if (iconUrl != null) {
                loadIcon(holder.mIcon, iconUrl, position);
            }
        } catch (Exception ignored) {}

        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) mListener.onClick(mod);
        });
    }

    private String getIconUrl(JSONObject mod) {
        try {
            // Modrinth: icon_url
            String iconUrl = mod.optString("icon_url", null);
            if (iconUrl != null && !iconUrl.isEmpty()) return iconUrl;
            // CurseForge: logo.thumbnailUrl
            JSONObject logo = mod.optJSONObject("logo");
            if (logo != null) return logo.optString("thumbnailUrl", null);
        } catch (Exception ignored) {}
        return null;
    }

    private void loadIcon(ImageView view, String url, int position) {
        mIconExecutor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.connect();
                InputStream in = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(in);
                conn.disconnect();
                if (bmp != null) {
                    mHandler.post(() -> {
                        if (view.getTag() != null && (int) view.getTag() == position) {
                            view.setImageBitmap(bmp);
                        }
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private String formatNumber(int n) {
        if (n >= 1_000_000) return (n / 1_000_000) + "M";
        if (n >= 1_000) return (n / 1_000) + "K";
        return String.valueOf(n);
    }

    @Override
    public int getItemCount() {
        return mMods.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView mName, mSummary, mDownloads;
        ImageView mIcon;

        ViewHolder(View view) {
            super(view);
            mName = view.findViewById(R.id.market_mod_name);
            mSummary = view.findViewById(R.id.market_mod_summary);
            mDownloads = view.findViewById(R.id.market_mod_downloads);
            mIcon = view.findViewById(R.id.market_mod_icon);
        }
    }
}
