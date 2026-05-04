package com.baseballstream;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class StreamAdapter extends RecyclerView.Adapter<StreamAdapter.ViewHolder> {

    public interface OnStreamClickListener {
        void onStreamClick(StreamItem item);
    }

    private List<StreamItem> items = new ArrayList<>();
    private final OnStreamClickListener listener;

    public StreamAdapter(OnStreamClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<StreamItem> newItems) {
        items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_stream, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StreamItem item = items.get(position);
        holder.title.setText(item.getTitle());

        if (item.getSubtitle().isEmpty()) {
            holder.subtitle.setVisibility(View.GONE);
        } else {
            holder.subtitle.setVisibility(View.VISIBLE);
            holder.subtitle.setText(item.getSubtitle());
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onStreamClick(item);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView subtitle;

        ViewHolder(View v) {
            super(v);
            title    = v.findViewById(R.id.text_stream_title);
            subtitle = v.findViewById(R.id.text_stream_subtitle);
        }
    }
}
