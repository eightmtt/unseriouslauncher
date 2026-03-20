package net.kdt.pojavlaunch.modmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MarketModAdapter extends RecyclerView.Adapter<MarketModAdapter.ViewHolder> {

    public interface OnInstallClickListener {
        void onInstall(JSONObject mod, String selectedVersion, String selectedLoader);
    }

    public interface OnVersionPickerListener {
        void onVersionPicker(JSONObject mod, TextView picker);
        void onLoaderPicker(JSONObject mod, TextView picker);
    }

    private static final LruCache<String, Bitmap> sIconCache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private List<JSONObject> mMods;
    private final OnInstallClickListener mInstallListener;
    private OnVersionPickerListener mPickerListener;
    private boolean mIsModrinth = true;
    private boolean mFilterEnabled = true;
    private String mVersionFilter = "";
    private String mLoaderFilter = "";
    private int mExpandedPosition = -1;

    private final ExecutorService mIconExecutor = Executors.newFixedThreadPool(4);
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public MarketModAdapter(List<JSONObject> mods, OnInstallClickListener listener) {
        this.mMods = mods;
        this.mInstallListener = listener;
    }

    public void setPickerListener(OnVersionPickerListener l) { this.mPickerListener = l; }
    public void setMods(List<JSONObject> mods) { this.mMods = mods; mExpandedPosition = -1; notifyDataSetChanged(); }
    public void setSource(boolean isModrinth) { this.mIsModrinth = isModrinth; }
    public void setFilterEnabled(boolean e) { this.mFilterEnabled = e; }
    public void setVersionFilter(String v) { this.mVersionFilter = v != null ? v : ""; }
    public void setLoaderFilter(String l) { this.mLoaderFilter = l != null ? l : ""; }

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
        boolean isExpanded = (position == mExpandedPosition);

        try {
            String name = mod.optString("title", mod.optString("name", "?"));
            String author = getAuthor(mod);
            String summary = mod.optString("description", mod.optString("summary", ""));
            int downloads = mod.optInt("downloads", mod.optInt("downloadCount", 0));
            String updated = getUpdated(mod);
            String loaders = getLoaders(mod);

            holder.mName.setText(name);
            holder.mAuthor.setText("by " + author);
            holder.mSummary.setText(summary);
            holder.mDownloads.setText(formatNumber(downloads));
            holder.mUpdated.setText(updated);
            holder.mLoaders.setText(loaders);

            buildTags(holder.mTagsRow, mod);

            // Иконка
            holder.mIcon.setImageResource(mIsModrinth ? R.drawable.ic_modrinth : R.drawable.ic_curseforge);
            String iconUrl = getIconUrl(mod);
            if (iconUrl != null) {
                holder.mIcon.setTag(iconUrl);
                loadIcon(holder.mIcon, iconUrl);
            } else {
                holder.mIcon.setTag(null);
            }

            // Расширенная панель
            holder.mExpanded.setVisibility(isExpanded ? View.VISIBLE : View.GONE);

            if (isExpanded) {
                boolean showSelectors = !mFilterEnabled || mVersionFilter.isEmpty();
                holder.mVersionSelectors.setVisibility(showSelectors ? View.VISIBLE : View.GONE);

                if (showSelectors) {
                    if (!(holder.mVersionPicker.getTag() instanceof String))
                        holder.mVersionPicker.setText("All Game Versions ▾");
                    if (!(holder.mLoaderPicker.getTag() instanceof String))
                        holder.mLoaderPicker.setText("All Mod Loaders ▾");

                    holder.mVersionPicker.setOnClickListener(v -> {
                        if (mPickerListener != null)
                            mPickerListener.onVersionPicker(mod, holder.mVersionPicker);
                    });
                    holder.mLoaderPicker.setOnClickListener(v -> {
                        if (mPickerListener != null)
                            mPickerListener.onLoaderPicker(mod, holder.mLoaderPicker);
                    });
                }

                // Скрываем прогресс изначально
                holder.mDownloadStatus.setVisibility(View.GONE);
                holder.mDownloadProgress.setVisibility(View.GONE);

                holder.mDownloadBtn.setOnClickListener(v -> {
                    String ver = (mFilterEnabled && !mVersionFilter.isEmpty())
                        ? mVersionFilter
                        : (holder.mVersionPicker.getTag() instanceof String
                            ? (String) holder.mVersionPicker.getTag() : null);
                    String loader = (mFilterEnabled && !mLoaderFilter.isEmpty())
                        ? mLoaderFilter
                        : (holder.mLoaderPicker.getTag() instanceof String
                            ? (String) holder.mLoaderPicker.getTag() : null);
                    if (mInstallListener != null) mInstallListener.onInstall(mod, ver, loader);
                });
            }

        } catch (Exception ignored) {}

        holder.itemView.setOnClickListener(v -> {
            int prev = mExpandedPosition;
            mExpandedPosition = isExpanded ? -1 : position;
            if (prev != -1) notifyItemChanged(prev);
            notifyItemChanged(position);
        });
    }

    // Обновить прогресс скачивания для конкретной позиции
    public void updateDownloadProgress(int position, String fileName, long downloaded, long total) {
        // Вызывается из фрагмента через notifyItemChanged с payload
        // Обрабатывается в onBindViewHolder через holder
    }

    private String getAuthor(JSONObject mod) {
        try {
            String a = mod.optString("author", null);
            if (a != null && !a.isEmpty()) return a;
            JSONArray authors = mod.optJSONArray("authors");
            if (authors != null && authors.length() > 0)
                return authors.getJSONObject(0).optString("name", "?");
        } catch (Exception ignored) {}
        return "?";
    }

    private String getUpdated(JSONObject mod) {
        try {
            String date = mod.optString("date_modified", mod.optString("dateModified", ""));
            if (date.length() >= 10) return date.substring(0, 10);
        } catch (Exception ignored) {}
        return "";
    }

    // Получить строку лоадеров из данных мода
    private String getLoaders(JSONObject mod) {
        try {
            // Modrinth — поле "loaders" в поисковой выдаче
            JSONArray loaders = mod.optJSONArray("loaders");
            if (loaders != null && loaders.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(loaders.length(), 3); i++) {
                    if (i > 0) sb.append(", ");
                    String l = loaders.getString(i);
                    sb.append(Character.toUpperCase(l.charAt(0))).append(l.substring(1));
                }
                return sb.toString();
            }
            // CurseForge — categories содержат лоадеры
        } catch (Exception ignored) {}
        return "";
    }

    private void buildTags(LinearLayout container, JSONObject mod) {
        container.removeAllViews();
        try {
            JSONArray cats = mod.optJSONArray("categories");
            if (cats == null) return;
            Context ctx = container.getContext();
            int max = Math.min(cats.length(), 3);
            for (int i = 0; i < max; i++) {
                String tag;
                Object cat = cats.get(i);
                if (cat instanceof JSONObject) {
                    tag = ((JSONObject) cat).optString("name", "");
                } else {
                    tag = cat.toString();
                }
                if (tag.isEmpty()) continue;

                TextView tv = new TextView(ctx);
                tv.setText(tag);
                tv.setTextSize(11);
                tv.setTextColor(0xFFB0B0B0);
                tv.setBackgroundColor(0xFF333333);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(6);
                tv.setLayoutParams(lp);
                tv.setPadding(16, 4, 16, 4);
                container.addView(tv);
            }
        } catch (Exception ignored) {}
    }

    private String getIconUrl(JSONObject mod) {
        try {
            String url = mod.optString("icon_url", null);
            if (url != null && !url.isEmpty()) return url;
            JSONObject logo = mod.optJSONObject("logo");
            if (logo != null) return logo.optString("thumbnailUrl", null);
        } catch (Exception ignored) {}
        return null;
    }

    private void loadIcon(ImageView view, String url) {
        Bitmap cached = sIconCache.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        mIconExecutor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.connect();
                Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                conn.disconnect();
                if (bmp != null) {
                    sIconCache.put(url, bmp);
                    mHandler.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(bmp); });
                }
            } catch (Exception ignored) {}
        });
    }

    private String formatNumber(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    @Override
    public int getItemCount() { return mMods.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView mName, mAuthor, mSummary, mDownloads, mUpdated, mLoaders;
        TextView mVersionPicker, mLoaderPicker;
        TextView mDownloadStatus;
        ProgressBar mDownloadProgress;
        ImageView mIcon;
        LinearLayout mTagsRow, mExpanded, mVersionSelectors;
        Button mDownloadBtn;

        ViewHolder(View view) {
            super(view);
            mName = view.findViewById(R.id.market_mod_name);
            mAuthor = view.findViewById(R.id.market_mod_author);
            mSummary = view.findViewById(R.id.market_mod_summary);
            mDownloads = view.findViewById(R.id.market_mod_downloads);
            mUpdated = view.findViewById(R.id.market_mod_updated);
            mLoaders = view.findViewById(R.id.market_mod_loaders);
            mIcon = view.findViewById(R.id.market_mod_icon);
            mTagsRow = view.findViewById(R.id.market_mod_tags_row);
            mExpanded = view.findViewById(R.id.market_mod_expanded);
            mVersionSelectors = view.findViewById(R.id.market_mod_version_selectors);
            mVersionPicker = view.findViewById(R.id.market_mod_version_picker);
            mLoaderPicker = view.findViewById(R.id.market_mod_loader_picker);
            mDownloadBtn = view.findViewById(R.id.market_mod_download_btn);
            mDownloadStatus = view.findViewById(R.id.market_mod_download_status);
            mDownloadProgress = view.findViewById(R.id.market_mod_download_progress);
        }
    }
}
