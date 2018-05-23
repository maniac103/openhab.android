/*
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   which accompanies this distribution, and is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.habdroid.ui;

import android.content.Context;
import android.net.Uri;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.openhab.habdroid.R;
import org.openhab.habdroid.core.connection.Connection;
import org.openhab.habdroid.core.connection.ConnectionFactory;
import org.openhab.habdroid.model.OpenHABNotification;
import org.openhab.habdroid.util.MySmartImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OpenHABNotificationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface LoadMoreListener {
        void loadMoreItems();
    }

    private static final int VIEW_TYPE_NOTIFICATION = 0;
    private static final int VIEW_TYPE_LOADING = 1;

    private final ArrayList<OpenHABNotification> mItems = new ArrayList<>();
    private final LayoutInflater mInflater;
    private final Context mContext;
    private final LoadMoreListener mLoadMoreListener;
    private boolean mHasMoreItems;
    private boolean mWaitingForMoreData;
    private int mHighlightedPosition = -1;

    public OpenHABNotificationAdapter(Context context, LoadMoreListener loadMoreListener) {
        super();
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mLoadMoreListener = loadMoreListener;
        mHasMoreItems = false;
    }

    public void addLoadedItems(List<OpenHABNotification> items, boolean hasMoreItems) {
        mItems.addAll(items);
        mHasMoreItems = hasMoreItems;
        mWaitingForMoreData = false;
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        mHasMoreItems = false;
        mWaitingForMoreData = false;
        notifyDataSetChanged();
    }

    public int findPositionForId(String id) {
        for (int i = 0; i < mItems.size(); i++) {
            if (TextUtils.equals(mItems.get(i).id(), id)) {
                return i;
            }
        }
        return -1;
    }

    public void highlightItem(int position) {
        mHighlightedPosition = position;
        notifyItemChanged(position);
    }

    @Override
    public int getItemCount() {
        return mItems.size() + (mHasMoreItems ? 1 : 0);
    }

    @Override
    public int getItemViewType(int position) {
        return position == mItems.size() ? VIEW_TYPE_LOADING : VIEW_TYPE_NOTIFICATION;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_LOADING) {
            return new LoadingIndicatorViewHolder(mInflater, parent);
        } else {
            return new NotificationViewHolder(mInflater, parent);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof NotificationViewHolder) {
            bindNotification((NotificationViewHolder) holder, mItems.get(position));
        } else {
            // loading indicator
            holder.itemView.setVisibility(mHasMoreItems ? View.VISIBLE : View.GONE);
            if (mHasMoreItems && !mWaitingForMoreData) {
                mLoadMoreListener.loadMoreItems();
                mWaitingForMoreData = true;
            }
        }

        if (position == mHighlightedPosition) {
            final View v = holder.itemView;
            v.post(() -> {
                if (v.getBackground() != null) {
                    final int centerX = v.getWidth() / 2;
                    final int centerY = v.getHeight() / 2;
                    DrawableCompat.setHotspot(v.getBackground(), centerX, centerY);
                }
                v.setPressed(true);
                v.setPressed(false);
                mHighlightedPosition = -1;
            });
        }
    }

    private void bindNotification(NotificationViewHolder holder, OpenHABNotification notification) {
        holder.mCreatedView.setText(DateUtils.getRelativeDateTimeString(mContext,
                notification.createdTimestamp(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0));
        holder.mMessageView.setText(notification.message());

        if (notification.icon() != null) {
            Connection conn = ConnectionFactory.getConnection(Connection.TYPE_CLOUD);
            String iconUrl = String.format(Locale.US, "%simages/%s.png",
                    conn.getOpenHABUrl(), Uri.encode(notification.icon()));
            holder.mIconView.setImageUrl(iconUrl, conn.getUsername(), conn.getPassword(),
                    R.drawable.ic_openhab_appicon_24dp);
        } else {
            holder.mIconView.setImageResource(R.drawable.ic_openhab_appicon_24dp);
        }
    }

    public static class NotificationViewHolder extends RecyclerView.ViewHolder {
        final TextView mCreatedView;
        final TextView mMessageView;
        final MySmartImageView mIconView;

        public NotificationViewHolder(LayoutInflater inflater, ViewGroup parent) {
            super(inflater.inflate(R.layout.openhabnotificationlist_item, parent, false));
            mCreatedView = itemView.findViewById(R.id.notificationCreated);
            mMessageView = itemView.findViewById(R.id.notificationMessage);
            mIconView = itemView.findViewById(R.id.notificationImage);
        }
    }

    public static class LoadingIndicatorViewHolder extends RecyclerView.ViewHolder {
        public LoadingIndicatorViewHolder(LayoutInflater inflater, ViewGroup parent) {
            super(inflater.inflate(R.layout.list_loading_item, parent, false));
        }
    }
}
