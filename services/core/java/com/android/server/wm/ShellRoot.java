/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;

import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;

import android.annotation.NonNull;
import android.graphics.Point;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.SurfaceControl;
import android.view.animation.Animation;

/**
 * Represents a piece of the hierarchy under which a client Shell can manage sub-windows.
 */
public class ShellRoot {
    private static final String TAG = "ShellRoot";
    private final DisplayContent mDisplayContent;
    private IWindow mClient;
    private WindowToken mToken;
    private final IBinder.DeathRecipient mDeathRecipient;
    private SurfaceControl mSurfaceControl = null;

    ShellRoot(@NonNull IWindow client, @NonNull DisplayContent dc, final int windowType) {
        mDisplayContent = dc;
        mDeathRecipient = () -> mDisplayContent.removeShellRoot(windowType);
        try {
            client.asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to add shell root for layer " + windowType + " on display "
                    + dc.getDisplayId(), e);
            return;
        }
        mClient = client;
        mToken = new WindowToken(
                dc.mWmService, client.asBinder(), windowType, true, dc, true, false);
        mSurfaceControl = mToken.makeChildSurface(null)
                .setContainerLayer().setName("Shell Root Leash " + dc.getDisplayId()).build();
        mToken.getPendingTransaction().show(mSurfaceControl);
    }

    void clear() {
        if (mClient != null) {
            mClient.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mClient = null;
        }
        if (mToken != null) {
            mToken.removeImmediately();
            mToken = null;
        }
    }

    SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    IWindow getClient() {
        return mClient;
    }

    void startAnimation(Animation anim) {
        // Only do this for the divider
        if (mToken.windowType != TYPE_DOCK_DIVIDER) {
            return;
        }

        DisplayInfo displayInfo = mToken.getFixedRotationTransformDisplayInfo();
        if (displayInfo == null) {
            displayInfo = mDisplayContent.getDisplayInfo();
        }

        // Mostly copied from WindowState to enable keyguard transition animation
        anim.initialize(displayInfo.logicalWidth, displayInfo.logicalHeight,
                displayInfo.appWidth, displayInfo.appHeight);
        anim.restrictDuration(MAX_ANIMATION_DURATION);
        anim.scaleCurrentDuration(mDisplayContent.mWmService.getWindowAnimationScaleLocked());
        final AnimationAdapter adapter = new LocalAnimationAdapter(
                new WindowAnimationSpec(anim, new Point(0, 0), false /* canSkipFirstFrame */,
                        0 /* windowCornerRadius */),
                mDisplayContent.mWmService.mSurfaceAnimationRunner);
        mToken.startAnimation(mToken.getPendingTransaction(), adapter, false /* hidden */,
                ANIMATION_TYPE_WINDOW_ANIMATION, null /* animationFinishedCallback */);
    }
}

