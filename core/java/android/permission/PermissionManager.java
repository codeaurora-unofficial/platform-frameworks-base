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

package android.permission;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;

import com.android.internal.annotations.Immutable;
import com.android.server.SystemConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * System level service for accessing the permission capabilities of the platform.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.PERMISSION_SERVICE)
public final class PermissionManager {
    /**
     * {@link android.content.pm.PackageParser} needs access without having a {@link Context}.
     *
     * @hide
     */
    public static final ArrayList<SplitPermissionInfo> SPLIT_PERMISSIONS =
            SystemConfig.getInstance().getSplitPermissions();

    private final @NonNull Context mContext;

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate.
     * @hide
     */
    public PermissionManager(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Get set of permissions that have been split into more granular or dependent permissions.
     *
     * <p>E.g. before {@link android.os.Build.VERSION_CODES#P0} an app that was granted
     * {@link Manifest.permission#ACCESS_COARSE_LOCATION} could access he location while it was in
     * foreground and background. On platforms after {@link android.os.Build.VERSION_CODES#P0}
     * the location permission only grants location access while the app is in foreground. This
     * would break apps that target before {@link android.os.Build.VERSION_CODES#P0}. Hence whenever
     * such an old app asks for a location permission (i.e. the
     * {@link SplitPermissionInfo#getSplitPermission()}), then the
     * {@link Manifest.permission#ACCESS_BACKGROUND_LOCATION} permission (inside
     * {@link SplitPermissionInfo#getNewPermissions}) is added.
     *
     * <p>Note: Regular apps do not have to worry about this. The platform and permission controller
     * automatically add the new permissions where needed.
     *
     * @return All permissions that are split.
     */
    public @NonNull List<SplitPermissionInfo> getSplitPermissions() {
        return SPLIT_PERMISSIONS;
    }

    /**
     * A permission that was added in a previous API level might have split into several
     * permissions. This object describes one such split.
     */
    @Immutable
    public static final class SplitPermissionInfo {
        private final @NonNull String mSplitPerm;
        private final @NonNull List<String> mNewPerms;
        private final int mTargetSdk;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SplitPermissionInfo that = (SplitPermissionInfo) o;
            return mTargetSdk == that.mTargetSdk
                    && Objects.equals(mSplitPerm, that.mSplitPerm);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSplitPerm, mTargetSdk);
        }

        /**
         * Get the permission that is split.
         */
        public @NonNull String getSplitPermission() {
            return mSplitPerm;
        }

        /**
         * Get the permissions that are added.
         */
        public @NonNull List<String> getNewPermissions() {
            return mNewPerms;
        }

        /**
         * Get the target API level when the permission was split.
         */
        public int getTargetSdk() {
            return mTargetSdk;
        }

        /**
         * Constructs a split permission.
         *
         * @param splitPerm old permission that will be split
         * @param newPerms list of new permissions that {@code rootPerm} will be split into
         * @param targetSdk apps targetting SDK versions below this will have {@code rootPerm}
         * split into {@code newPerms}
         * @hide
         */
        public SplitPermissionInfo(@NonNull String splitPerm, @NonNull List<String> newPerms,
                int targetSdk) {
            mSplitPerm = splitPerm;
            mNewPerms = newPerms;
            mTargetSdk = targetSdk;
        }
    }
}
