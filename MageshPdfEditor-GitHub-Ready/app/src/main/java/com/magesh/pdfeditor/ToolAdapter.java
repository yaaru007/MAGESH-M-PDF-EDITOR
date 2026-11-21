package com.magesh.pdfeditor;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ToolAdapter extends RecyclerView.Adapter<ToolAdapter.VH> {

    public interface OnToolClickListener {
        void onToolClick(ToolItem item);
    }

    private final List<ToolItem> allItems;
    private final List<ToolItem> visibleItems;
    private final OnToolClickListener listener;

    public ToolAdapter(List<ToolItem> items, OnToolClickListener listener) {
        this.allItems = new ArrayList<>(items);
        this.visibleItems = new ArrayList<>(items);
        this.listener = listener;
    }

    public void filter(String query) {
        visibleItems.clear();
        String q = query == null ? "" : query.trim().toLowerCase();
        for (ToolItem item : allItems) {
            if (q.isEmpty()
                    || item.title.toLowerCase().contains(q)
                    || item.category.toLowerCase().contains(q)
                    || item.description.toLowerCase().contains(q)) {
                visibleItems.add(item);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tool_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ToolItem item = visibleItems.get(position);
        holder.title.setText(item.title);
        holder.category.setText(item.category);
        holder.desc.setText(item.description);
        holder.itemView.setOnClickListener(v -> listener.onToolClick(item));
    }

    @Override
    public int getItemCount() {
        return visibleItems.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, category, desc;
        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tvToolTitle);
            category = itemView.findViewById(R.id.tvToolCategory);
            desc = itemView.findViewById(R.id.tvToolDesc);
        }
    }
}
