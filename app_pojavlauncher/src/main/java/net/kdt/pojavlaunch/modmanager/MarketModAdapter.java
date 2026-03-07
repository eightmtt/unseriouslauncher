package net.kdt.pojavlaunch.modmanager;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import org.json.JSONObject;

import java.util.List;

public class MarketModAdapter extends RecyclerView.Adapter<MarketModAdapter.ViewHolder> {

    public interface OnModClickListener {
        void onClick(JSONObject mod);
    }

    private List<JSONObject> mMods;
    private final OnModClickListener mListener;

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
            holder.mName.setText(mod.optString("name", "?"));
            holder.mSummary.setText(mod.optString("summary", ""));
            int downloads = mod.optInt("downloadCount", 0);
            holder.mDownloads.setText("↓ " + formatNumber(downloads));
        } catch (Exception ignored) {}

        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) mListener.onClick(mod);
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

        ViewHolder(View view) {
            super(view);
            mName = view.findViewById(R.id.market_mod_name);
            mSummary = view.findViewById(R.id.market_mod_summary);
            mDownloads = view.findViewById(R.id.market_mod_downloads);
        }
    }
}
