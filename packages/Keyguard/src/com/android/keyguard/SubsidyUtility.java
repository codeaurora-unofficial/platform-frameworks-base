/*
 * Copyright (c) 2016-2017, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *      * Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *      * Redistributions in binary form must reproduce the above
 *        copyright notice, this list of conditions and the following
 *        disclaimer in the documentation and/or other materials provided
 *        with the distribution.
 *      * Neither the name of The Linux Foundation nor the names of its
 *        contributors may be used to endorse or promote products derived
 *        from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.keyguard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.android.keyguard.KeyguardConstants;

import java.util.regex.Pattern;

public class SubsidyUtility {

    public static final boolean DEBUG = false || KeyguardConstants.DEBUG;

    private static final String TAG = "SubsidyUtility";
    public static final String SUBSIDY_STATUS_SETTING = "subsidy_status";
    public static final String SUBSIDY_SWITCH_SIM_SETTING = "subsidy_switch_sim";

    public static final String ACTION_SUBSIDY_LOCK_CLIENT =
        "org.codeaurora.intent.action.ACTION_LOCKSCREEN";

    public static final String ACTION_SUBSIDY_LOCK_INTERNAL =
        "org.codeaurora.intent.action.ACTION_LOCKSCREEN_INTERNAL";

    public static final String ACTION_PRIMARY_CARD_CONFIG_CHANGED =
        "org.codeaurora.intent.action.PRIMARY_CARD_CONFIG_CHANGED";

    public static final String ACTION_PRIMARY_CARD_CONFIG_FAILED =
        "org.codeaurora.intent.action.PRIMARY_CARD_CONFIG_FAILED";

    public static final String BROADCAST_PERMISSION =
        "com.codeaurora.permission.SUBSIDYLOCK";

    // Intent and extras sent from Subsidy Lock Client to lockscreen
    // Show Lock Screen [TYPE: BOOLEAN]
    public static final String EXTRA_INTENT_KEY_LOCK_SCREEN =
        "INTENT_KEY_LOCK_SCREEN";
    // Show Activation Screen [TYPE: BOOLEAN]
    public static final String EXTRA_INTENT_KEY_ACTIVATION_SCREEN =
        "INTENT_KEY_ACTIVATION_SCREEN";
    // Show Switch Sim Screen [TYPE: BOOLEAN]
    public static final String EXTRA_INTENT_KEY_SWITCH_SIM_SCREEN =
        "INTENT_KEY_SWITCH_SIM_SCREEN";
    // Show "Enter Code" Screen [TYPE: BOOLEAN]
    public static final String EXTRA_INTENT_KEY_ENTER_CODE_SCREEN =
        "INTENT_KEY_ENTER_CODE_SCREEN";
    // Extra to show/hide PIN view in "Enter Code" [TYPE: BOOLEAN]
    public static final String EXTRA_INTENT_KEY_SHOW_PIN =
        "INTENT_KEY_SHOW_PIN";
    // Extra received when device is Subsidy Unlocked
    public static final String EXTRA_INTENT_KEY_UNLOCK_SCREEN =
        "INTENT_KEY_UNLOCK_SCREEN";
    // Extra to be received when Device is Unlocked remotely
    public static final String EXTRA_INTENT_KEY_UNLOCK_PERMANENT =
        "INTENT_KEY_UNLOCK_PERMANENTLY";

    // Intent and extras sent from lockscreen to Subsidy lock client
    public static final String ACTION_USER_REQUEST =
        "org.codeaurora.intent.action.ACTION_USER_REQUEST";
    // User clicked Unlock button [TYPE: BOOLEAN]
    public static final String EXTRA_INTENT_KEY_UNLOCK = "INTENT_KEY_UNLOCK";
    // PIN verified [TYPE: BOOLEAN]
    public static final String EXTRA_INTENT_KEY_PIN_VERIFIED =
        "INTENT_KEY_PIN_VERIFIED";
    // Activation is done (call to 1977 finished) [TYPE: BOOLEAN]
    public static final String EXTRA_INTENT_KEY_ACTIVATION_DONE =
        "INTENT_KEY_ACTIVATION_DONE";

    // Action used to invoke wifi setup screen over keyguard
    public static final String WIFI_SETUP_SCREEN_INTENT =
            "codeaurora.intent.action.ACTION_SUBSIDY_WIFI_NETWORK";

    public static final class SubsidyLockState {
        // This is the state received on First boot or on FDR
        public static final int SUBSIDY_STATUS_UNKNOWN = -1;
        //This indicates modem is unlocked
        public static final int DEVICE_UNLOCKED = 100;
        //This indicates modem is locked and user has to enter pin to unlock
        public static final int DEVICE_LOCKED = 101;
        //This indicates modem is unlocked, but locked in app layer
        public static final int AP_LOCKED = 102;
        //This indicates device is usable only for the particular sim
        public static final int AP_UNLOCKED = 103;
    };

    public static boolean isSubsidyLockFeatureEnabled(Context context) {
        return SystemProperties.getInt("ro.radio.subsidylock", 0) == 1;
    }

    public static void writeSubsidyLockStatus(Context context, int state) {
        try {
            Settings.Secure.putInt(context.getContentResolver(),
                    SubsidyUtility.SUBSIDY_STATUS_SETTING, state);
        } catch (Exception e) {
            Log.e(TAG, "Exception while writing subsidy lock state " + e);
        }
    }

    public static int getSubsidyLockStatus(Context context) {
        int state = SubsidyLockState.SUBSIDY_STATUS_UNKNOWN;
        try {
            state =
                Settings.Secure.getInt(context.getContentResolver(),
                        SubsidyUtility.SUBSIDY_STATUS_SETTING,
                        SubsidyLockState.SUBSIDY_STATUS_UNKNOWN);
        } catch (Exception e) {
            Log.e(TAG, "Exception while writing subsidy lock state " + e);
        }
        return state;
    }

    public static boolean shouldShowSubsidyLock(Context context) {
        int subsidyLockStatus = SubsidyUtility.getSubsidyLockStatus(context);
        return SubsidyUtility.isSubsidyLockFeatureEnabled(context)
            && (subsidyLockStatus
                    == SubsidyUtility.SubsidyLockState.SUBSIDY_STATUS_UNKNOWN
                    || subsidyLockStatus
                    == SubsidyUtility.SubsidyLockState.AP_LOCKED
                    || subsidyLockStatus
                    == SubsidyUtility.SubsidyLockState.DEVICE_LOCKED);
    }

    public static boolean isDataConnectionActive(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null
                && activeNetwork.isConnected();
    }

    public static boolean isSubsidyRestricted(Context context, int subId) {
        int subsidyStatus  = getSubsidyLockStatus(context);
        boolean subsidyLocked = (subsidyStatus == SubsidyUtility.SubsidyLockState.AP_LOCKED)
                || (subsidyStatus == SubsidyUtility.SubsidyLockState.AP_UNLOCKED);
        return isSubsidyLockFeatureEnabled(null) && subsidyLocked && !isValidSim(context, subId);
    }

    public static boolean isValidSim(Context context, int subId) {
        SubscriptionInfo sir = SubscriptionManager.from(context)
                .getActiveSubscriptionInfo(subId);
        if (sir == null || (sir.getMcc() == 0 || sir.getMnc() == 0)) {
            return false;
        }
        return isWhiteListed(context,
                String.valueOf(sir.getMcc()), String.valueOf(sir.getMnc()));
    }

    private static boolean isWhiteListed(Context context, String mcc, String mnc) {
        boolean mccAllowed = false;
        boolean mncAllowed = false;
        String[] mccWhiteList = context.getResources()
                .getStringArray(R.array.mccs_white_listed);
        String[] mncsWhiteList = context.getResources()
                .getStringArray(R.array.mncs_white_listed);
        for (String mccRegEx : mccWhiteList) {
            mccAllowed |= Pattern.compile(mccRegEx).matcher(mcc).matches();
            if (mccAllowed) {
                break;
            }
        }
        for (String mncRegEx : mncsWhiteList) {
            mncAllowed |= Pattern.compile(mncRegEx).matcher(mnc).matches();
            if (mncAllowed) {
                break;
            }
        }
        return mccAllowed && mncAllowed;
    }

    public static boolean isAirplaneMode(Context context) {
        return (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
    }

    public static void writeSubsidySwitchSimSlot(Context context, int slot) {
        try {
            Settings.Secure.putInt(context.getContentResolver(),
                    SubsidyUtility.SUBSIDY_SWITCH_SIM_SETTING, slot);
        } catch (Exception e) {
            Log.e(TAG, "Exception while writing subsidy sim slot " + e);
        }
    }

    public static int getSubsidySwitchSimSlot(Context context) {
        int slotId = SubscriptionManager.INVALID_SIM_SLOT_INDEX;
        try {
            slotId = Settings.Secure.getInt(context.getContentResolver(),
                         SubsidyUtility.SUBSIDY_SWITCH_SIM_SETTING,
                         SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        } catch (Exception e) {
            Log.e(TAG, "Exception while read subsidy sim slot " + e);
        }
        return slotId;
    }
}
