/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shows the four Glyph sources as a list the user reorders by dragging the handles. The order
 * is stored in the same {@link GlyphSettings#KEY_PRIORITY} slots the engine reads.
 */
public class PriorityOrderPreference extends Preference {

    private final List<String> mOrder = new ArrayList<>();
    private final List<String> mValues;
    private final String[] mLabels;
    private final Adapter mAdapter = new Adapter();
    private ItemTouchHelper mTouchHelper;

    public PriorityOrderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_priority_order);
        setSelectable(false);
        setPersistent(false);
        Resources res = context.getResources();
        mValues = Arrays.asList(res.getStringArray(R.array.source_values));
        mLabels = res.getStringArray(R.array.source_entries);
        mOrder.addAll(GlyphSettings.getPriorityOrder(context));
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);
        RecyclerView list = (RecyclerView) holder.findViewById(R.id.priority_list);
        if (list.getAdapter() != mAdapter) {
            list.setLayoutManager(new LinearLayoutManager(getContext()));
            list.setNestedScrollingEnabled(false);
            list.setAdapter(mAdapter);
            if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
            mTouchHelper = new ItemTouchHelper(new DragCallback());
            mTouchHelper.attachToRecyclerView(list);
        }
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onDependencyChanged(@NonNull Preference dependency, boolean disableDependent) {
        super.onDependencyChanged(dependency, disableDependent);
        notifyChanged();
    }

    private void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= mOrder.size() || to >= mOrder.size()) {
            return;
        }
        Collections.swap(mOrder, from, to);
        mAdapter.notifyItemMoved(from, to);
    }

    /** Writes the current order and renumbers the rows. */
    private void save() {
        SharedPreferences.Editor editor = GlyphSettings.prefs(getContext()).edit();
        for (int i = 0; i < GlyphSettings.KEY_PRIORITY.length; i++) {
            editor.putString(GlyphSettings.KEY_PRIORITY[i], mOrder.get(i));
        }
        editor.apply();
        mAdapter.notifyItemRangeChanged(0, mOrder.size());
    }

    private String labelFor(String source) {
        int i = mValues.indexOf(source);
        return i >= 0 ? mLabels[i] : source;
    }

    private class Holder extends RecyclerView.ViewHolder {
        final TextView rank;
        final TextView label;
        final ImageView handle;

        Holder(View v) {
            super(v);
            rank = v.findViewById(R.id.priority_rank);
            label = v.findViewById(R.id.priority_label);
            handle = v.findViewById(R.id.priority_handle);
        }
    }

    private class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.priority_order_item, parent, false));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            boolean enabled = isEnabled();
            h.rank.setText(String.valueOf(position + 1));
            h.label.setText(labelFor(mOrder.get(position)));
            h.itemView.setEnabled(enabled);
            h.rank.setEnabled(enabled);
            h.label.setEnabled(enabled);
            h.handle.setEnabled(enabled);
            h.itemView.setAlpha(enabled ? 1f : 0.38f);
            h.itemView.setContentDescription(getContext().getString(
                    R.string.priority_item_description, position + 1, h.label.getText()));
            h.handle.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && isEnabled()) {
                    // Keep the settings list from scrolling while the row is dragged.
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    mTouchHelper.startDrag(h);
                }
                return false;
            });
            h.itemView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host,
                        AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    int pos = h.getBindingAdapterPosition();
                    if (!isEnabled() || pos == RecyclerView.NO_POSITION) return;
                    if (pos > 0) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                R.id.action_move_up,
                                getContext().getString(R.string.priority_move_up)));
                    }
                    if (pos < mOrder.size() - 1) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                R.id.action_move_down,
                                getContext().getString(R.string.priority_move_down)));
                    }
                }

                @Override
                public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    int pos = h.getBindingAdapterPosition();
                    if (action == R.id.action_move_up && pos > 0) {
                        move(pos, pos - 1);
                        save();
                        return true;
                    }
                    if (action == R.id.action_move_down && pos >= 0
                            && pos < mOrder.size() - 1) {
                        move(pos, pos + 1);
                        save();
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mOrder.size();
        }
    }

    private class DragCallback extends ItemTouchHelper.SimpleCallback {
        DragCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            // Long-pressing anywhere on a row also starts a drag.
            return isEnabled();
        }

        @Override
        public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                @NonNull RecyclerView.ViewHolder to) {
            move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
            super.onSelectedChanged(vh, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                vh.itemView.setActivated(true);
                vh.itemView.setElevation(8f * vh.itemView.getResources()
                        .getDisplayMetrics().density);
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            vh.itemView.setActivated(false);
            vh.itemView.setElevation(0f);
            save();
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {}
    }
}
