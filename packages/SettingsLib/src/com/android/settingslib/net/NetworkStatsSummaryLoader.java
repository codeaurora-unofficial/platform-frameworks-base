/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.net;

import android.app.usage.NetworkStatsManager;
import android.app.usage.NetworkStats;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import androidx.loader.content.AsyncTaskLoader;

/**
 * Loader for retrieving the network stats summary for all UIDs.
 */
public class NetworkStatsSummaryLoader extends AsyncTaskLoader<NetworkStats> {

    private static final String TAG = "NetworkDetailLoader";
    private final NetworkStatsManager mNetworkStatsManager;
    private final long mStart;
    private final long mEnd;
    private final String mSubId;
    private final int mNetworkType;

    private NetworkStatsSummaryLoader(Builder builder) {
        super(builder.mContext);
        mStart = builder.mStart;
        mEnd = builder.mEnd;
        mSubId = builder.mSubId;
        mNetworkType = builder.mNetworkType;
        mNetworkStatsManager = (NetworkStatsManager)
                builder.mContext.getSystemService(Context.NETWORK_STATS_SERVICE);
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();
        forceLoad();
    }

    @Override
    public NetworkStats loadInBackground() {
        try {
            return mNetworkStatsManager.querySummary(mNetworkType, mSubId, mStart, mEnd);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception querying network detail.", e);
            return null;
        }
    }

    @Override
    protected void onStopLoading() {
        super.onStopLoading();
        cancelLoad();
    }

    @Override
    protected void onReset() {
        super.onReset();
        cancelLoad();
    }

    public static class Builder {
        private final Context mContext;
        private long mStart;
        private long mEnd;
        private String mSubId;
        private int mNetworkType;

        public Builder(Context context) {
            mContext = context;
        }

        public Builder setStartTime(long start) {
            mStart = start;
            return this;
        }

        public Builder setEndTime(long end) {
            mEnd = end;
            return this;
        }

        public Builder setSubscriberId(String subId) {
            mSubId = subId;
            return this;
        }

        public Builder setNetworkType(int networkType) {
            mNetworkType = networkType;
            return this;
        }

        public NetworkStatsSummaryLoader build() {
            return new NetworkStatsSummaryLoader(this);
        }
    }
}
