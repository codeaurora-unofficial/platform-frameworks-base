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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.notification.NotificationData;
import com.android.systemui.statusbar.notification.NotificationEntryManager;

import java.util.Set;

/**
 * Handles when smart replies are added to a notification
 * and clicked upon.
 */
public class SmartReplyController {
    private IStatusBarService mBarService;
    private Set<String> mSendingKeys = new ArraySet<>();
    private Callback mCallback;

    public SmartReplyController() {
        mBarService = Dependency.get(IStatusBarService.class);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void smartReplySent(NotificationData.Entry entry, int replyIndex, CharSequence reply) {
        mCallback.onSmartReplySent(entry, reply);
        mSendingKeys.add(entry.key);
        try {
            mBarService.onNotificationSmartReplySent(entry.notification.getKey(),
                    replyIndex);
        } catch (RemoteException e) {
            // Nothing to do, system going down
        }
    }

    /**
     * Have we posted an intent to an app about sending a smart reply from the
     * notification with this key.
     */
    public boolean isSendingSmartReply(String key) {
        return mSendingKeys.contains(key);
    }

    public void smartRepliesAdded(final NotificationData.Entry entry, int replyCount) {
        try {
            mBarService.onNotificationSmartRepliesAdded(entry.notification.getKey(),
                    replyCount);
        } catch (RemoteException e) {
            // Nothing to do, system going down
        }
    }

    public void stopSending(final NotificationData.Entry entry) {
        if (entry != null) {
            mSendingKeys.remove(entry.notification.getKey());
        }
    }

    /**
     * Callback for any class that needs to do something in response to a smart reply being sent.
     */
    public interface Callback {
        /**
         * A smart reply has just been sent for a notification
         *
         * @param entry the entry for the notification
         * @param reply the reply that was sent
         */
        void onSmartReplySent(NotificationData.Entry entry, CharSequence reply);
    }
}
