package net.kdt.pojavlaunch.modmanager;

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

    // LruCache для кэширования иконок — 8MB
    private static final LruCache<String, Bitmap> sIconCache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private List<JSONObject> mMods;
    private final OnInstallClickListener mInstallListener;
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

    public void setMods(List<JSONObject> mods) {
        this.mMods = mods;
        mExpandedPosition = -1;
        notifyDataSetChanged();
    }

    public void setSource(boolean isModrinth) { this.mIsModrinth = isModrinth; }
    public void setFilterEnabled(boolean enabled) { this.mFilterEnabled = enabled; }
    public void setVersionFilter(String version) { this.mVersionFilter = version != null ? version : ""; }
    public void setLoaderFilter(String loader) { this.mLoaderFilter = loader != null ? loader : ""; }

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
            int follows = mod.optInt("follows", mod.optInt("thumbsUpCount", 0));
            String updated = getUpdated(mod);

            holder.mName.setText(name);
            holder.mAuthor.setText("by " + author);
            holder.mSummary.setText(summary);
            holder.mDownloads.setText("↓ " + formatNumber(downloads));
            holder.mFollows.setText(follows > 0 ? "♥ " + formatNumber(follows) : "");
            holder.mUpdated.setText(updated.isEmpty() ? "" : "🕐 " + updated);

            // Теги
            buildTags(holder.mTagsRow, mod);

            // Иконка
            holder.mIcon.setImageResource(mIsModrinth ? R.drawable.ic_modrinth : R.drawable.ic_curseforge);
            String iconUrl = getIconUrl(mod);
            if (iconUrl != null) {
                holder.mIcon.setTag(iconUrl);
                loadIcon(holder.mIcon, iconUrl);
            }

            // Расширение карточки
            holder.mExpanded.setVisibility(isExpanded ? View.VISIBLE : View.GONE);

            // Если фильтр выключен показываем селекторы версии/лоадера
            if (isExpanded) {
                boolean showSelectors = !mFilterEnabled || mVersionFilter.isEmpty();
                holder.mVersionSelectors.setVisibility(showSelectors ? View.VISIBLE : View.GONE);

                if (showSelectors) {
                    holder.mVersionPicker.setText("All Game Versions ▾");
                    holder.mLoaderPicker.setText("All Mod Loaders ▾");

                    holder.mVersionPicker.setOnClickListener(v ->
                        showVersionPicker(holder.mVersionPicker, mod));
                    holder.mLoaderPicker.setOnClickListener(v ->
                        showLoaderPicker(holder.mLoaderPicker));
                }

                holder.mDownloadBtn.setOnClickListener(v -> {
                    String ver = mFilterEnabled && !mVersionFilter.isEmpty()
                        ? mVersionFilter
                        : (String) holder.mVersionPicker.getTag();
                    String loader = mFilterEnabled && !mLoaderFilter.isEmpty()
                        ? mLoaderFilter
                        : (String) holder.mLoaderPicker.getTag();
                    if (mInstallListener != null) mInstallListener.onInstall(mod, ver, loader);
                });
            }

        } catch (Exception ignored) {}

        // Клик разворачивает/сворачивает карточку
        holder.itemView.setOnClickListener(v -> {
            int prev = mExpandedPosition;
            mExpandedPosition = isExpanded ? -1 : position;
            if (prev != -1) notifyItemChanged(prev);
            notifyItemChanged(position);
        });
    }

    private String getAuthor(JSONObject mod) {
        try {
            // Modrinth
            String author = mod.optString("author", null);
            if (author != null && !author.isEmpty()) return author;
            // CurseForge
            JSONArray authors = mod.optJSONArray("authors");
            if (authors != null && authors.length() > 0) {
                return authors.getJSONObject(0).optString("name", "");
            }
        } catch (Exception ignored) {}
        return "?";
    }

    private String getUpdated(JSONObject mod) {
        try {
            String date = mod.optString("date_modified",
                          mod.optString("dateModified", ""));
            if (date.length() >= 10) return date.substring(0, 10);
        } catch (Exception ignored) {}
        return "";
    }

    private void buildTags(LinearLayout container, JSONObject mod) {
        container.removeAllViews();
        try {
            JSONArray cats = mod.optJSONArray("categories");
            if (cats == null) cats = mod.optJSONArray("categories");
            if (cats == null) return;

            int max = Math.min(cats.length(), 5);
            for (int i = 0; i < max; i++) {
                String tag = cats.getString(i);
                TextView tv = new TextView(container.getContext());
                tv.setText(tag);
                tv.setTextSize(11);
                tv.setTextColor(0xFFFFFFFF);
                tv.setBackgroundColor(0xFF2A2A2A);
                tv.setPadding(16, 4, 16, 4);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(6);
                tv.setLayoutParams(lp);
                container.addView(tv);
            }
        } catch (Exception ignored) {}
    }

    private void showVersionPicker(TextView picker, JSONObject mod) {
        // Простой диалог — реализуется в фрагменте через listener
        // Здесь просто placeholder
    }

    private void showLoaderPicker(TextView picker) {
        // Placeholder
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
        // Проверяем кэш
        Bitmap cached = sIconCache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        mIconExecutor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.connect();
                InputStream in = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(in);
                conn.disconnect();
                if (bmp != null) {
                    sIconCache.put(url, bmp);
                    mHandler.post(() -> {
                        if (url.equals(view.getTag())) {
                            view.setImageBitmap(bmp);
                        }
                    });
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
        TextView mName, mAuthor, mSummary, mDownloads, mFollows, mUpdated;
        TextView mVersionPicker, mLoaderPicker;
        ImageView mIcon;
        LinearLayout mTagsRow, mExpanded, mVersionSelectors;
        Button mDownloadBtn;

        ViewHolder(View view) {
            super(view);
            mName = view.findViewById(R.id.market_mod_name);
            mAuthor = view.findViewById(R.id.market_mod_author);
            mSummary = view.findViewById(R.id.market_mod_summary);
            mDownloads = view.findViewById(R.id.market_mod_downloads);
            mFollows = view.findViewById(R.id.market_mod_follows);
            mUpdated = view.findViewById(R.id.market_mod_updated);
            mIcon = view.findViewById(R.id.market_mod_icon);
            mTagsRow = view.findViewById(R.id.market_mod_tags_row);
            mExpanded = view.findViewById(R.id.market_mod_expanded);
            mVersionSelectors = view.findViewById(R.id.market_mod_version_selectors);
            mVersionPicker = view.findViewById(R.id.market_mod_version_picker);
            mLoaderPicker = view.findViewById(R.id.market_mod_loader_picker);
            mDownloadBtn = view.findViewById(R.id.market_mod_download_btn);
        }
    }
}
