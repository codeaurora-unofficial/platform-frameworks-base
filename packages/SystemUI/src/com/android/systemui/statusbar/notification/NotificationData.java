/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import static android.app.Notification.CATEGORY_ALARM;
import static android.app.Notification.CATEGORY_CALL;
import static android.app.Notification.CATEGORY_EVENT;
import static android.app.Notification.CATEGORY_MESSAGE;
import static android.app.Notification.CATEGORY_REMINDER;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.View;
import android.widget.ImageView;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The list of currently displaying notifications.
 */
public class NotificationData {

    private final Environment mEnvironment;
    private HeadsUpManager mHeadsUpManager;

    final ZenModeController mZen = Dependency.get(ZenModeController.class);
    final ForegroundServiceController mFsc = Dependency.get(ForegroundServiceController.class);

    public static final class Entry {
        private static final long LAUNCH_COOLDOWN = 2000;
        private static final long REMOTE_INPUT_COOLDOWN = 500;
        private static final long INITIALIZATION_DELAY = 400;
        private static final long NOT_LAUNCHED_YET = -LAUNCH_COOLDOWN;
        private static final int COLOR_INVALID = 1;
        public String key;
        public StatusBarNotification notification;
        public NotificationChannel channel;
        public StatusBarIconView icon;
        public StatusBarIconView expandedIcon;
        public ExpandableNotificationRow row; // the outer expanded view
        private boolean interruption;
        public boolean autoRedacted; // whether the redacted notification was generated by us
        public int targetSdk;
        private long lastFullScreenIntentLaunchTime = NOT_LAUNCHED_YET;
        public RemoteViews cachedContentView;
        public RemoteViews cachedBigContentView;
        public RemoteViews cachedHeadsUpContentView;
        public RemoteViews cachedPublicContentView;
        public RemoteViews cachedAmbientContentView;
        public CharSequence remoteInputText;
        public List<SnoozeCriterion> snoozeCriteria;
        public int userSentiment = Ranking.USER_SENTIMENT_NEUTRAL;
        @NonNull
        public List<Notification.Action> smartActions = Collections.emptyList();
        public CharSequence[] smartReplies = new CharSequence[0];

        private int mCachedContrastColor = COLOR_INVALID;
        private int mCachedContrastColorIsFor = COLOR_INVALID;
        private InflationTask mRunningTask = null;
        private Throwable mDebugThrowable;
        public CharSequence remoteInputTextWhenReset;
        public long lastRemoteInputSent = NOT_LAUNCHED_YET;
        public ArraySet<Integer> mActiveAppOps = new ArraySet<>(3);
        public CharSequence headsUpStatusBarText;
        public CharSequence headsUpStatusBarTextPublic;

        private long initializationTime = -1;

        /**
         * Whether or not this row represents a system notification. Note that if this is
         * {@code null}, that means we were either unable to retrieve the info or have yet to
         * retrieve the info.
         */
        public Boolean mIsSystemNotification;

        /**
         * Has the user sent a reply through this Notification.
         */
        private boolean hasSentReply;

        public Entry(StatusBarNotification n) {
            this(n, null);
        }

        public Entry(StatusBarNotification n, @Nullable Ranking ranking) {
            this.key = n.getKey();
            this.notification = n;
            if (ranking != null) {
                populateFromRanking(ranking);
            }
        }

        public void populateFromRanking(@NonNull Ranking ranking) {
            channel = ranking.getChannel();
            snoozeCriteria = ranking.getSnoozeCriteria();
            userSentiment = ranking.getUserSentiment();
            smartActions = ranking.getSmartActions() == null
                    ? Collections.emptyList() : ranking.getSmartActions();
            smartReplies = ranking.getSmartReplies() == null
                    ? new CharSequence[0]
                    : ranking.getSmartReplies().toArray(new CharSequence[0]);
        }

        public void setInterruption() {
            interruption = true;
        }

        public boolean hasInterrupted() {
            return interruption;
        }

        /**
         * Resets the notification entry to be re-used.
         */
        public void reset() {
            if (row != null) {
                row.reset();
            }
        }

        public View getExpandedContentView() {
            return row.getPrivateLayout().getExpandedChild();
        }

        public View getPublicContentView() {
            return row.getPublicLayout().getContractedChild();
        }

        public void notifyFullScreenIntentLaunched() {
            setInterruption();
            lastFullScreenIntentLaunchTime = SystemClock.elapsedRealtime();
        }

        public boolean hasJustLaunchedFullScreenIntent() {
            return SystemClock.elapsedRealtime() < lastFullScreenIntentLaunchTime + LAUNCH_COOLDOWN;
        }

        public boolean hasJustSentRemoteInput() {
            return SystemClock.elapsedRealtime() < lastRemoteInputSent + REMOTE_INPUT_COOLDOWN;
        }

        public boolean hasFinishedInitialization() {
            return initializationTime == -1 ||
                    SystemClock.elapsedRealtime() > initializationTime + INITIALIZATION_DELAY;
        }

        /**
         * Create the icons for a notification
         * @param context the context to create the icons with
         * @param sbn the notification
         * @throws InflationException
         */
        public void createIcons(Context context, StatusBarNotification sbn)
                throws InflationException {
            Notification n = sbn.getNotification();
            final Icon smallIcon = n.getSmallIcon();
            if (smallIcon == null) {
                throw new InflationException("No small icon in notification from "
                        + sbn.getPackageName());
            }

            // Construct the icon.
            icon = new StatusBarIconView(context,
                    sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

            // Construct the expanded icon.
            expandedIcon = new StatusBarIconView(context,
                    sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
            expandedIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            final StatusBarIcon ic = new StatusBarIcon(
                    sbn.getUser(),
                    sbn.getPackageName(),
                    smallIcon,
                    n.iconLevel,
                    n.number,
                    StatusBarIconView.contentDescForNotification(context, n));
            if (!icon.set(ic) || !expandedIcon.set(ic)) {
                icon = null;
                expandedIcon = null;
                throw new InflationException("Couldn't create icon: " + ic);
            }
            expandedIcon.setVisibility(View.INVISIBLE);
            expandedIcon.setOnVisibilityChangedListener(
                    newVisibility -> {
                        if (row != null) {
                            row.setIconsVisible(newVisibility != View.VISIBLE);
                        }
                    });
        }

        public void setIconTag(int key, Object tag) {
            if (icon != null) {
                icon.setTag(key, tag);
                expandedIcon.setTag(key, tag);
            }
        }

        /**
         * Update the notification icons.
         *
         * @param context the context to create the icons with.
         * @param sbn the notification to read the icon from.
         * @throws InflationException
         */
        public void updateIcons(Context context, StatusBarNotification sbn)
                throws InflationException {
            if (icon != null) {
                // Update the icon
                Notification n = sbn.getNotification();
                final StatusBarIcon ic = new StatusBarIcon(
                        notification.getUser(),
                        notification.getPackageName(),
                        n.getSmallIcon(),
                        n.iconLevel,
                        n.number,
                        StatusBarIconView.contentDescForNotification(context, n));
                icon.setNotification(sbn);
                expandedIcon.setNotification(sbn);
                if (!icon.set(ic) || !expandedIcon.set(ic)) {
                    throw new InflationException("Couldn't update icon: " + ic);
                }
            }
        }

        public int getContrastedColor(Context context, boolean isLowPriority,
                int backgroundColor) {
            int rawColor = isLowPriority ? Notification.COLOR_DEFAULT :
                    notification.getNotification().color;
            if (mCachedContrastColorIsFor == rawColor && mCachedContrastColor != COLOR_INVALID) {
                return mCachedContrastColor;
            }
            final int contrasted = ContrastColorUtil.resolveContrastColor(context, rawColor,
                    backgroundColor);
            mCachedContrastColorIsFor = rawColor;
            mCachedContrastColor = contrasted;
            return mCachedContrastColor;
        }

        /**
         * Abort all existing inflation tasks
         */
        public void abortTask() {
            if (mRunningTask != null) {
                mRunningTask.abort();
                mRunningTask = null;
            }
        }

        public void setInflationTask(InflationTask abortableTask) {
            // abort any existing inflation
            InflationTask existing = mRunningTask;
            abortTask();
            mRunningTask = abortableTask;
            if (existing != null && mRunningTask != null) {
                mRunningTask.supersedeTask(existing);
            }
        }

        public void onInflationTaskFinished() {
            mRunningTask = null;
        }

        @VisibleForTesting
        public InflationTask getRunningTask() {
            return mRunningTask;
        }

        /**
         * Set a throwable that is used for debugging
         *
         * @param debugThrowable the throwable to save
         */
        public void setDebugThrowable(Throwable debugThrowable) {
            mDebugThrowable = debugThrowable;
        }

        public Throwable getDebugThrowable() {
            return mDebugThrowable;
        }

        public void onRemoteInputInserted() {
            lastRemoteInputSent = NOT_LAUNCHED_YET;
            remoteInputTextWhenReset = null;
        }

        public void setHasSentReply() {
            hasSentReply = true;
        }

        public boolean isLastMessageFromReply() {
            if (!hasSentReply) {
                return false;
            }
            Bundle extras = notification.getNotification().extras;
            CharSequence[] replyTexts = extras.getCharSequenceArray(
                    Notification.EXTRA_REMOTE_INPUT_HISTORY);
            if (!ArrayUtils.isEmpty(replyTexts)) {
                return true;
            }
            Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (messages != null && messages.length > 0) {
                Parcelable message = messages[messages.length - 1];
                if (message instanceof Bundle) {
                    Notification.MessagingStyle.Message lastMessage =
                            Notification.MessagingStyle.Message.getMessageFromBundle(
                                    (Bundle) message);
                    if (lastMessage != null) {
                        Person senderPerson = lastMessage.getSenderPerson();
                        if (senderPerson == null) {
                            return true;
                        }
                        Person user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
                        return Objects.equals(user, senderPerson);
                    }
                }
            }
            return false;
        }

        public void setInitializationTime(long time) {
            if (initializationTime == -1) {
                initializationTime = time;
            }
        }
    }

    private final ArrayMap<String, Entry> mEntries = new ArrayMap<>();
    private final ArrayList<Entry> mSortedAndFiltered = new ArrayList<>();
    private final ArrayList<Entry> mFilteredForUser = new ArrayList<>();

    private NotificationGroupManager mGroupManager;

    private RankingMap mRankingMap;
    private final Ranking mTmpRanking = new Ranking();

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    private final Comparator<Entry> mRankingComparator = new Comparator<Entry>() {
        private final Ranking mRankingA = new Ranking();
        private final Ranking mRankingB = new Ranking();

        @Override
        public int compare(Entry a, Entry b) {
            final StatusBarNotification na = a.notification;
            final StatusBarNotification nb = b.notification;
            int aImportance = NotificationManager.IMPORTANCE_DEFAULT;
            int bImportance = NotificationManager.IMPORTANCE_DEFAULT;
            int aRank = 0;
            int bRank = 0;

            if (mRankingMap != null) {
                // RankingMap as received from NoMan
                getRanking(a.key, mRankingA);
                getRanking(b.key, mRankingB);
                aImportance = mRankingA.getImportance();
                bImportance = mRankingB.getImportance();
                aRank = mRankingA.getRank();
                bRank = mRankingB.getRank();
            }

            String mediaNotification = mEnvironment.getCurrentMediaNotificationKey();

            // IMPORTANCE_MIN media streams are allowed to drift to the bottom
            final boolean aMedia = a.key.equals(mediaNotification)
                    && aImportance > NotificationManager.IMPORTANCE_MIN;
            final boolean bMedia = b.key.equals(mediaNotification)
                    && bImportance > NotificationManager.IMPORTANCE_MIN;

            boolean aSystemMax = aImportance >= NotificationManager.IMPORTANCE_HIGH &&
                    isSystemNotification(na);
            boolean bSystemMax = bImportance >= NotificationManager.IMPORTANCE_HIGH &&
                    isSystemNotification(nb);

            boolean isHeadsUp = a.row.isHeadsUp();
            if (isHeadsUp != b.row.isHeadsUp()) {
                return isHeadsUp ? -1 : 1;
            } else if (isHeadsUp) {
                // Provide consistent ranking with headsUpManager
                return mHeadsUpManager.compare(a, b);
            } else if (a.row.isAmbientPulsing() != b.row.isAmbientPulsing()) {
                return a.row.isAmbientPulsing() ? -1 : 1;
            } else if (aMedia != bMedia) {
                // Upsort current media notification.
                return aMedia ? -1 : 1;
            } else if (aSystemMax != bSystemMax) {
                // Upsort PRIORITY_MAX system notifications
                return aSystemMax ? -1 : 1;
            } else if (aRank != bRank) {
                return aRank - bRank;
            } else {
                return Long.compare(nb.getNotification().when, na.getNotification().when);
            }
        }
    };

    public NotificationData(Environment environment) {
        mEnvironment = environment;
        mGroupManager = environment.getGroupManager();
    }

    /**
     * Returns the sorted list of active notifications (depending on {@link Environment}
     *
     * <p>
     * This call doesn't update the list of active notifications. Call {@link #filterAndSort()}
     * when the environment changes.
     * <p>
     * Don't hold on to or modify the returned list.
     */
    public ArrayList<Entry> getActiveNotifications() {
        return mSortedAndFiltered;
    }

    public ArrayList<Entry> getNotificationsForCurrentUser() {
        mFilteredForUser.clear();

        synchronized (mEntries) {
            final int N = mEntries.size();
            for (int i = 0; i < N; i++) {
                Entry entry = mEntries.valueAt(i);
                final StatusBarNotification sbn = entry.notification;
                if (!mEnvironment.isNotificationForCurrentProfiles(sbn)) {
                    continue;
                }
                mFilteredForUser.add(entry);
            }
        }
        return mFilteredForUser;
    }

    public Entry get(String key) {
        return mEntries.get(key);
    }

    public void add(Entry entry) {
        synchronized (mEntries) {
            mEntries.put(entry.notification.getKey(), entry);
        }
        mGroupManager.onEntryAdded(entry);

        updateRankingAndSort(mRankingMap);
    }

    public Entry remove(String key, RankingMap ranking) {
        Entry removed = null;
        synchronized (mEntries) {
            removed = mEntries.remove(key);
        }
        if (removed == null) return null;
        mGroupManager.onEntryRemoved(removed);
        updateRankingAndSort(ranking);
        return removed;
    }

    public void updateRanking(RankingMap ranking) {
        updateRankingAndSort(ranking);
    }

    public void updateAppOp(int appOp, int uid, String pkg, String key, boolean showIcon) {
        synchronized (mEntries) {
            final int N = mEntries.size();
            for (int i = 0; i < N; i++) {
                Entry entry = mEntries.valueAt(i);
                if (uid == entry.notification.getUid()
                        && pkg.equals(entry.notification.getPackageName())
                        && key.equals(entry.key)) {
                    if (showIcon) {
                        entry.mActiveAppOps.add(appOp);
                    } else {
                        entry.mActiveAppOps.remove(appOp);
                    }
                }
            }
        }
    }

    public boolean isAmbient(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.isAmbient();
        }
        return false;
    }

    public int getVisibilityOverride(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getVisibilityOverride();
        }
        return Ranking.VISIBILITY_NO_OVERRIDE;
    }

    public boolean shouldSuppressFullScreenIntent(Entry entry) {
        return shouldSuppressVisualEffect(entry, SUPPRESSED_EFFECT_FULL_SCREEN_INTENT);
    }

    public boolean shouldSuppressPeek(Entry entry) {
        return shouldSuppressVisualEffect(entry, SUPPRESSED_EFFECT_PEEK);
    }

    public boolean shouldSuppressStatusBar(Entry entry) {
        return shouldSuppressVisualEffect(entry, SUPPRESSED_EFFECT_STATUS_BAR);
    }

    public boolean shouldSuppressAmbient(Entry entry) {
        return shouldSuppressVisualEffect(entry, SUPPRESSED_EFFECT_AMBIENT);
    }

    public boolean shouldSuppressNotificationList(Entry entry) {
        return shouldSuppressVisualEffect(entry, SUPPRESSED_EFFECT_NOTIFICATION_LIST);
    }

    private boolean shouldSuppressVisualEffect(Entry entry, int effect) {
        if (isExemptFromDndVisualSuppression(entry)) {
            return false;
        }
        String key = entry.key;
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return (mTmpRanking.getSuppressedVisualEffects() & effect) != 0;
        }
        return false;
    }

    protected boolean isExemptFromDndVisualSuppression(Entry entry) {
        if (isNotificationBlockedByPolicy(entry.notification.getNotification())) {
            return false;
        }

        if ((entry.notification.getNotification().flags
                & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return true;
        }
        if (entry.notification.getNotification().isMediaNotification()) {
            return true;
        }
        if (entry.mIsSystemNotification != null && entry.mIsSystemNotification) {
            return true;
        }
        return false;
    }

    /**
     * Categories that are explicitly called out on DND settings screens are always blocked, if
     * DND has flagged them, even if they are foreground or system notifications that might
     * otherwise visually bypass DND.
     */
    protected boolean isNotificationBlockedByPolicy(Notification n) {
        if (isCategory(CATEGORY_CALL, n)
                || isCategory(CATEGORY_MESSAGE, n)
                || isCategory(CATEGORY_ALARM, n)
                || isCategory(CATEGORY_EVENT, n)
                || isCategory(CATEGORY_REMINDER, n)) {
            return true;
        }
        return false;
    }

    private boolean isCategory(String category, Notification n) {
        return Objects.equals(n.category, category);
    }

    public int getImportance(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getImportance();
        }
        return NotificationManager.IMPORTANCE_UNSPECIFIED;
    }

    public String getOverrideGroupKey(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getOverrideGroupKey();
        }
        return null;
    }

    public List<SnoozeCriterion> getSnoozeCriteria(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getSnoozeCriteria();
        }
        return null;
    }

    public NotificationChannel getChannel(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getChannel();
        }
        return null;
    }

    public int getRank(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getRank();
        }
        return 0;
    }

    public boolean shouldHide(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.isSuspended();
        }
        return false;
    }

    private void updateRankingAndSort(RankingMap ranking) {
        if (ranking != null) {
            mRankingMap = ranking;
            synchronized (mEntries) {
                final int N = mEntries.size();
                for (int i = 0; i < N; i++) {
                    Entry entry = mEntries.valueAt(i);
                    if (!getRanking(entry.key, mTmpRanking)) {
                        continue;
                    }
                    final StatusBarNotification oldSbn = entry.notification.cloneLight();
                    final String overrideGroupKey = getOverrideGroupKey(entry.key);
                    if (!Objects.equals(oldSbn.getOverrideGroupKey(), overrideGroupKey)) {
                        entry.notification.setOverrideGroupKey(overrideGroupKey);
                        mGroupManager.onEntryUpdated(entry, oldSbn);
                    }
                    entry.populateFromRanking(mTmpRanking);
                }
            }
        }
        filterAndSort();
    }

    /**
     * Get the ranking from the current ranking map.
     *
     * @param key the key to look up
     * @param outRanking the ranking to populate
     *
     * @return {@code true} if the ranking was properly obtained.
     */
    @VisibleForTesting
    protected boolean getRanking(String key, Ranking outRanking) {
        return mRankingMap.getRanking(key, outRanking);
    }

    // TODO: This should not be public. Instead the Environment should notify this class when
    // anything changed, and this class should call back the UI so it updates itself.
    public void filterAndSort() {
        mSortedAndFiltered.clear();

        synchronized (mEntries) {
            final int N = mEntries.size();
            for (int i = 0; i < N; i++) {
                Entry entry = mEntries.valueAt(i);

                if (shouldFilterOut(entry)) {
                    continue;
                }

                mSortedAndFiltered.add(entry);
            }
        }

        Collections.sort(mSortedAndFiltered, mRankingComparator);
    }

    /**
     * @return true if this notification should NOT be shown right now
     */
    public boolean shouldFilterOut(Entry entry) {
        final StatusBarNotification sbn = entry.notification;
        if (!(mEnvironment.isDeviceProvisioned() ||
                showNotificationEvenIfUnprovisioned(sbn))) {
            return true;
        }

        if (!mEnvironment.isNotificationForCurrentProfiles(sbn)) {
            return true;
        }

        if (mEnvironment.isSecurelyLocked(sbn.getUserId()) &&
                (sbn.getNotification().visibility == Notification.VISIBILITY_SECRET
                        || mEnvironment.shouldHideNotifications(sbn.getUserId())
                        || mEnvironment.shouldHideNotifications(sbn.getKey()))) {
            return true;
        }

        if (mEnvironment.isDozing() && shouldSuppressAmbient(entry)) {
            return true;
        }

        if (!mEnvironment.isDozing() && shouldSuppressNotificationList(entry)) {
            return true;
        }

        if (shouldHide(sbn.getKey())) {
            return true;
        }

        if (!StatusBar.ENABLE_CHILD_NOTIFICATIONS
                && mGroupManager.isChildInGroupWithSummary(sbn)) {
            return true;
        }

        if (mFsc.isDungeonNotification(sbn) && !mFsc.isDungeonNeededForUser(sbn.getUserId())) {
            // this is a foreground-service disclosure for a user that does not need to show one
            return true;
        }
        if (mFsc.isSystemAlertNotification(sbn)) {
            final String[] apps = sbn.getNotification().extras.getStringArray(
                    Notification.EXTRA_FOREGROUND_APPS);
            if (apps != null && apps.length >= 1) {
                if (!mFsc.isSystemAlertWarningNeeded(sbn.getUserId(), apps[0])) {
                    return true;
                }
            }
        }

        return false;
    }

    // Q: What kinds of notifications should show during setup?
    // A: Almost none! Only things coming from packages with permission
    // android.permission.NOTIFICATION_DURING_SETUP that also have special "kind" tags marking them
    // as relevant for setup (see below).
    public static boolean showNotificationEvenIfUnprovisioned(StatusBarNotification sbn) {
        return showNotificationEvenIfUnprovisioned(AppGlobals.getPackageManager(), sbn);
    }

    @VisibleForTesting
    static boolean showNotificationEvenIfUnprovisioned(IPackageManager packageManager,
            StatusBarNotification sbn) {
        return checkUidPermission(packageManager, Manifest.permission.NOTIFICATION_DURING_SETUP,
                sbn.getUid()) == PackageManager.PERMISSION_GRANTED
                && sbn.getNotification().extras.getBoolean(Notification.EXTRA_ALLOW_DURING_SETUP);
    }

    private static int checkUidPermission(IPackageManager packageManager, String permission,
            int uid) {
        try {
            return packageManager.checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void dump(PrintWriter pw, String indent) {
        int N = mSortedAndFiltered.size();
        pw.print(indent);
        pw.println("active notifications: " + N);
        int active;
        for (active = 0; active < N; active++) {
            NotificationData.Entry e = mSortedAndFiltered.get(active);
            dumpEntry(pw, indent, active, e);
        }
        synchronized (mEntries) {
            int M = mEntries.size();
            pw.print(indent);
            pw.println("inactive notifications: " + (M - active));
            int inactiveCount = 0;
            for (int i = 0; i < M; i++) {
                Entry entry = mEntries.valueAt(i);
                if (!mSortedAndFiltered.contains(entry)) {
                    dumpEntry(pw, indent, inactiveCount, entry);
                    inactiveCount++;
                }
            }
        }
    }

    private void dumpEntry(PrintWriter pw, String indent, int i, Entry e) {
        getRanking(e.key, mTmpRanking);
        pw.print(indent);
        pw.println("  [" + i + "] key=" + e.key + " icon=" + e.icon);
        StatusBarNotification n = e.notification;
        pw.print(indent);
        pw.println("      pkg=" + n.getPackageName() + " id=" + n.getId() + " importance=" +
                mTmpRanking.getImportance());
        pw.print(indent);
        pw.println("      notification=" + n.getNotification());
    }

    private static boolean isSystemNotification(StatusBarNotification sbn) {
        String sbnPackage = sbn.getPackageName();
        return "android".equals(sbnPackage) || "com.android.systemui".equals(sbnPackage);
    }

    /**
     * Provides access to keyguard state and user settings dependent data.
     */
    public interface Environment {
        public boolean isSecurelyLocked(int userId);
        public boolean shouldHideNotifications(int userid);
        public boolean shouldHideNotifications(String key);
        public boolean isDeviceProvisioned();
        public boolean isNotificationForCurrentProfiles(StatusBarNotification sbn);
        public String getCurrentMediaNotificationKey();
        public NotificationGroupManager getGroupManager();

        /**
         * @return true iff the device is dozing
         */
        boolean isDozing();
    }
}
