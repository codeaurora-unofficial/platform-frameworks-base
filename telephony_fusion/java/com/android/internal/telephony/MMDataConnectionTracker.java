/*
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony;

import java.util.ArrayList;
import java.util.List;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface.RadioTechnology;
import com.android.internal.telephony.DataProfile.DataProfileType;

/*
 * Definitions:
 * - DataProfile(dp) :Information required to setup a connection (ex. ApnSetting)
 * - DataService(ds) : A particular feature requested by connectivity service, (MMS, GPS) etc.
 *                     also called APN Type
 * - DataConnection(dc) : Underlying PDP connection, associated with a dp.
 * - DataProfileTracker(dpt): Keeps track of services enabled, active, and dc that supports them
 * - DataServiceStateTracker(dsst) : Keeps track of network registration states, radio access tech
 *                                   roaming indications etc.
 *
 * What we know:
 * - A set of service types that needs to be enabled
 * - Data profiles needed to establish the service type
 * - Each Data profile will also tell us whether IPv4/IPv6 is possible with that data profile
 * - Priorities of services. (this can be used if MPDP is not supported, or limited # of pdp)
 * - DataServiceStateTracker will tell us the network state and preferred data radio technology
 * - dsst also keeps track of sim/ruim loaded status
 * - Desired power state
 * - For each service type, it is possible that same APN can handle ipv4 and ipv6. It is
 *   also possible that there are different APNs. This is handled.
 *
 * What we don't know:
 * - We don't know if the underlying network will support IPV6 or not.
 * - We don't know if the underlying network will support MPDP or not (even in 3GPP)
 * - If nw does support mpdp, we dont know how many pdp sessions it can handle
 * - We don't know how many PDP sessions/interfaces modem can handle
 * - We don't know if modem can disconnect existing calls in favor of new ones
 *   based on some profile priority.
 * - We don't know if IP continuity is possible or not possible across technologies.
 *
 * What we assume:
 * - Modem will not tear down the data call if IP continuity is possible.
 * - A separate dataConnection exists for IPV4 and IPV6, even
 *   though it is possible that both use the same underlying rmnet
 *   interface and pdp connection as is the case in dual stack v4/v6.
 * - If modem is aware of service priority, then these priorities are in sync
 *   with what is mentioned here, or we might end up in an infinite setup/disconnect
 *   cycle!
 *
 *
 * State Handling:
 * - states are associated with <service type, ip version> tuple.
 * - this is to handle scenario such as follows,
 *   default data might be connected on ipv4,  but we might be scanning different
 *   apns for default data on ipv6
 */

public class MMDataConnectionTracker extends DataConnectionTracker {

    private static final String LOG_TAG = "DATA";

    private static final int DATA_CONNECTION_POOL_SIZE = 8;

    private static final String INTENT_RECONNECT_ALARM = "com.android.internal.telephony.gprs-reconnect";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_REASON = "reason";

    /**
     * Constants for the data connection activity:
     * physical link down/up
     */
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE = 0;
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_DOWN = 1;
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_UP = 2;

    // ServiceStateTracker to keep track of network service state
    DataServiceStateTracker mDsst;

    boolean isDctActive = true;

    // keeps track of data statistics activity
    private DataNetStatistics mPollNetStat;

    // keeps track of wifi status - TODO: WHY?
    private boolean mIsWifiConnected = false;

    // Intent sent when the reconnect alarm fires.
    private PendingIntent mReconnectIntent = null;

    //following flags are used in isReadyForData()
    private boolean mNoAutoAttach = false;
    private boolean mIsPsRestricted = false;
    private boolean mDesiredPowerState = true;

    Message mPendingPowerOffCompleteMsg;

    //TODO: Read this from properties
    private static final boolean SUPPORT_IPV4 = true;
    private static final boolean SUPPORT_IPV6 = false;
    boolean mIsEhrpdCapable = false;

    /*
     * warning: if this flag is set then all connections are disconnected when
     * updatedata connections is called
     */
    private boolean mDisconnectAllDataCalls = false;

    /**
     * mDataCallList holds all the Data connection,
     */
    private ArrayList<DataConnection> mDataConnectionList;

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mPollNetStat.notifyScreenState(true);
                stopNetStatPoll();
                startNetStatPoll();

            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mPollNetStat.notifyScreenState(false);
                stopNetStatPoll();
                startNetStatPoll();

            } else if (action.equals((INTENT_RECONNECT_ALARM))) {
                String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
                sendMessage(obtainMessage(EVENT_UPDATE_DATA_CONNECTIONS, reason));

            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final android.net.NetworkInfo networkInfo = (NetworkInfo) intent
                        .getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                mIsWifiConnected = (networkInfo != null && networkInfo.isConnected());

            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;
                if (!enabled) {
                    // when WIFI got disabled, the NETWORK_STATE_CHANGED_ACTION
                    // quit and wont report disconnected till next enabling.
                    mIsWifiConnected = false;
                }

            } else if (action.equals(TelephonyIntents.ACTION_VOICE_CALL_STARTED)) {
                sendMessage(obtainMessage(EVENT_VOICE_CALL_STARTED));

            } else if (action.equals(TelephonyIntents.ACTION_VOICE_CALL_ENDED)) {
                sendMessage(obtainMessage(EVENT_VOICE_CALL_ENDED));
            }
        }
    };

    protected MMDataConnectionTracker(Context context, PhoneNotifier notifier, CommandsInterface ci) {
        super(context, notifier, ci);

        mDsst = new DataServiceStateTracker(context, ci);
        mPollNetStat = new DataNetStatistics(this);

        // register for events.
        mCm.registerForOn(this, EVENT_RADIO_ON, null);
        mCm.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCm.registerForDataStateChanged(this, EVENT_DATA_CALL_LIST_CHANGED, null);

        mDsst.registerForDataConnectionAttached(this, EVENT_DATA_CONNECTION_ATTACHED, null);
        mDsst.registerForDataConnectionDetached(this, EVENT_DATA_CONNECTION_DETACHED, null);

        mDsst.registerForDataRoamingOn(this, EVENT_ROAMING_ON, null);
        mDsst.registerForDataRoamingOff(this, EVENT_ROAMING_OFF, null);

        /* CDMA only */
        mCm.registerForCdmaOtaProvision(this, EVENT_CDMA_OTA_PROVISION, null);

        /* GSM only */
        mDsst.registerForPsRestrictedEnabled(this, EVENT_PS_RESTRICT_ENABLED, null);
        mDsst.registerForPsRestrictedDisabled(this, EVENT_PS_RESTRICT_DISABLED, null);

        /*
         * We let DSST worry about SIM/RUIM records to make life a little
         * simpler for us
         */
        mDsst.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);

        mDpt.registerForDataProfileDbChanged(this, EVENT_DATA_PROFILE_DB_CHANGED, null);

        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_RECONNECT_ALARM);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(TelephonyIntents.ACTION_VOICE_CALL_STARTED);
        filter.addAction(TelephonyIntents.ACTION_VOICE_CALL_ENDED);

        mContext.registerReceiver(mIntentReceiver, filter, null, this);

        createDataCallList();

        // This preference tells us 1) initial condition for "dataEnabled",
        // and 2) whether the RIL will setup the baseband to auto-PS
        // attach.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean dataDisabledOnBoot = sp.getBoolean(DataPhone.DATA_DISABLED_ON_BOOT_KEY, false);
        mDpt.setServiceTypeEnabled(DataServiceType.SERVICE_TYPE_DEFAULT, !dataDisabledOnBoot);
        mNoAutoAttach = dataDisabledOnBoot;

        mIsEhrpdCapable = SystemProperties.getBoolean("ro.config.ehrpd", false);
    }

    public void dispose() {

        // mark DCT as disposed
        isDctActive = false;

        mCm.unregisterForAvailable(this);
        mCm.unregisterForOn(this);
        mCm.unregisterForOffOrNotAvailable(this);
        mCm.unregisterForDataStateChanged(this);

        mCm.unregisterForCdmaOtaProvision(this);

        mDsst.unregisterForDataConnectionAttached(this);
        mDsst.unregisterForDataConnectionDetached(this);

        mDsst.unregisterForRecordsLoaded(this);

        mDsst.unregisterForDataRoamingOn(this);
        mDsst.unregisterForDataRoamingOff(this);
        mDsst.unregisterForPsRestrictedEnabled(this);
        mDsst.unregisterForPsRestrictedDisabled(this);

        mDpt.unregisterForDataProfileDbChanged(this);

        mDsst.dispose();
        mDsst = null;

        destroyDataCallList();

        mContext.unregisterReceiver(this.mIntentReceiver);

        super.dispose();
    }

    public void handleMessage(Message msg) {

        if (isDctActive == false) {
            logw("Ignoring handler messages, DCT marked as disposed.");
            return;
        }

        switch (msg.what) {
            case EVENT_UPDATE_DATA_CONNECTIONS:
                onUpdateDataConnections(((String)msg.obj));
                break;

            case EVENT_RECORDS_LOADED:
                onRecordsLoaded();
                break;

            case EVENT_DATA_CONNECTION_ATTACHED:
                onDataConnectionAttached();
                break;

            case EVENT_DATA_CONNECTION_DETACHED:
                onDataConnectionDetached();
                break;

            case EVENT_DATA_CALL_LIST_CHANGED:
                // unsolicited
                onDataCallListChanged((AsyncResult) msg.obj);
                break;

            case EVENT_DATA_PROFILE_DB_CHANGED:
                onDataProfileDbChanged();
                break;

            case EVENT_CDMA_OTA_PROVISION:
                onCdmaOtaProvision((AsyncResult) msg.obj);
                break;

            case EVENT_PS_RESTRICT_ENABLED:
                logi("PS restrict enabled.");
                /**
                 * We don't need to explicitly to tear down the PDP context when
                 * PS restricted is enabled. The base band will deactive PDP
                 * context and notify us with PDP_CONTEXT_CHANGED. But we should
                 * stop the network polling and prevent reset PDP.
                 */
                stopNetStatPoll();
                mIsPsRestricted = true;
                break;

            case EVENT_PS_RESTRICT_DISABLED:
                logi("PS restrict disable.");
                /**
                 * When PS restrict is removed, we need setup PDP connection if
                 * PDP connection is down.
                 */
                mIsPsRestricted = false;
                updateDataConnections(REASON_PS_RESTRICT_DISABLED);
                break;

            default:
                super.handleMessage(msg);
                break;
        }
    }

    protected void updateDataConnections(String reason) {
        Message msg = obtainMessage(EVENT_UPDATE_DATA_CONNECTIONS, reason);
        sendMessage(msg);
    }

    private void onCdmaOtaProvision(AsyncResult ar) {
        if (ar.exception != null) {
            int[] otaPrivision = (int[]) ar.result;
            if ((otaPrivision != null) && (otaPrivision.length > 1)) {
                switch (otaPrivision[0]) {
                    case Phone.CDMA_OTA_PROVISION_STATUS_COMMITTED:
                    case Phone.CDMA_OTA_PROVISION_STATUS_OTAPA_STOPPED:
                        mDpt.resetAllProfilesAsWorking();
                        mDpt.resetAllServiceStates();
                        updateDataConnections(REASON_CDMA_OTA_PROVISION);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void onDataProfileDbChanged() {

        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();

        /* ideally data profile tracker should send this event only if any of
         * the data profiles in use have changed. or we end up disconnecting
         * all the time. */
        disconnectAllConnections(REASON_DATA_PROFILE_DB_CHANGED);
    }

    protected void onRecordsLoaded() {
        /*
         * TODO: if both simRecords and ruimRecords are available,
         * we might need to look at the radio technology from data service
         * state tracker to determine, whether to use operator numeric from
         * sim records or ruim records.
         */
        if (mDsst.mSimRecords != null) {
            mDpt.setOperatorNumeric(mDsst.mSimRecords.getSIMOperatorNumeric());
        } else if (mDsst.mRuimRecords != null) {
            mDpt.setOperatorNumeric(mDsst.mRuimRecords.getRUIMOperatorNumeric());
        } else {
            loge("records are loaded, but both mSimrecords & mRuimRecords are null.");
        }
        // updateDataConnections() will be called if profile DB was changed as a
        // result of setting the operator numeric.
    }

    protected void onDataConnectionAttached() {
        // reset all profiles as working so as to give
        // all data profiles fair chance again.
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        updateDataConnections(REASON_DATA_NETWORK_ATTACH);
    }

    protected void onDataConnectionDetached() {
        /*
         * nothing needs to be done, data connections will disconnected one by
         * one, and update data connections will be done then.
         */
    }

    @Override
    protected void onRadioOn() {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        updateDataConnections(REASON_RADIO_ON);
    }

    @Override
    protected void onRadioOff() {
        //cleanup for next time, probably not required.
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
    }

    @Override
    protected void onRoamingOff() {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        updateDataConnections(REASON_ROAMING_OFF);
    }

    @Override
    protected void onRoamingOn() {
        if (getDataOnRoamingEnabled() == false) {
            disconnectAllConnections(REASON_ROAMING_ON);
        }
        updateDataConnections(REASON_ROAMING_ON);
    }

    @Override
    protected void onVoiceCallEnded() {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        updateDataConnections(REASON_VOICE_CALL_ENDED);
    }

    @Override
    protected void onVoiceCallStarted() {
        updateDataConnections(REASON_VOICE_CALL_STARTED);
    }

    @Override
    protected void onMasterDataDisabled() {
        mDisconnectAllDataCalls = true;
        updateDataConnections(REASON_MASTER_DATA_DISABLED);
    }

    @Override
    protected void onMasterDataEnabled() {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        updateDataConnections(REASON_MASTER_DATA_ENABLED);
    }

    @Override
    protected void onServiceTypeDisabled(AsyncResult obj) {
        updateDataConnections(REASON_SERVICE_TYPE_DISABLED);
    }

    @Override
    protected void onServiceTypeEnabled(AsyncResult ar) {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetServiceState((DataServiceType)ar.result);
        updateDataConnections(REASON_SERVICE_TYPE_ENABLED);
    }

    /**
     * @param explicitPoll if true, indicates that *we* polled for this update
     *            while state == CONNECTED rather than having it delivered via
     *            an unsolicited response (which could have happened at any
     *            previous state
     */
    @SuppressWarnings("unchecked")
    protected void onDataCallListChanged(AsyncResult ar) {

        ArrayList<DataCallState> dcStates;
        dcStates = (ArrayList<DataCallState>) (ar.result);

        if (ar.exception != null) {
            // This is probably "radio not available" or something
            // of that sort. If so, the whole connection is going
            // to come down soon anyway
            return;
        }

        boolean needDataConnectionUpdate = false;
        String dataConnectionUpdateReason = null;
        boolean isDataDormant = true; // will be set to false, if atleast one
                                      // data connection is not dormant.

        for (DataConnection dc: mDataConnectionList) {

            if (dc.getState() != DataConnection.State.ACTIVE) {
                continue;
            }

            DataCallState activeDC = getDataCallStateByCid(dcStates, dc.cid);
            if (activeDC == null) {
                logi("DC has disappeared from list : dc = " + dc);
                dc.clearSettings();
                // services will be marked as inactive, on data connection
                // update
                needDataConnectionUpdate = true;
                if (dataConnectionUpdateReason == null) {
                    dataConnectionUpdateReason = REASON_NETWORK_DISCONNECT;
                }
            } else if (activeDC.active == DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE) {
                DataConnectionFailCause failCause = DataConnectionFailCause
                        .getDataConnectionDisconnectCause(activeDC.inactiveReason);

                logi("DC is inactive : dc = " + dc);
                logi("   inactive cause = " + failCause);

                dc.clearSettings();
                needDataConnectionUpdate = true;
                if (dataConnectionUpdateReason == null) {
                    dataConnectionUpdateReason = REASON_NETWORK_DISCONNECT;
                }
            } else {
                switch (activeDC.active) {
                    /*
                     * TODO: fusion - the following code will show dormancy
                     * indications for both cdma/gsm. Is this a good thing?
                     */
                    case DATA_CONNECTION_ACTIVE_PH_LINK_UP:
                        isDataDormant = false;
                        break;

                    case DATA_CONNECTION_ACTIVE_PH_LINK_DOWN:
                        // do nothing
                        break;

                    default:
                        loge("dc.cid = " + dc.cid + ", unexpected DataCallState.active="
                                + activeDC.active);
                }
            }
        }

        if (needDataConnectionUpdate) {
            updateDataConnections(dataConnectionUpdateReason);
        }

        if (isDataDormant) {
            mPollNetStat.setActivity(Activity.DORMANT);
            stopNetStatPoll();
        } else {
            mPollNetStat.setActivity(Activity.NONE);
            startNetStatPoll();
        }
        notifyDataActivity();
    }

    void updateStateAndNotify(State state, DataServiceType ds, IPVersion ipv, String reason) {
        mDpt.setState(state, ds, ipv);
        //TODO: check if we might generate a lot of unnecessary notifications.
        notifyDataConnection(ds, ipv, reason);
    }

    private DataCallState getDataCallStateByCid(ArrayList<DataCallState> states, int cid) {
        for (int i = 0, s = states.size(); i < s; i++) {
            if (states.get(i).cid == cid)
                return states.get(i);
        }
        return null;
    }

    @Override
    protected void onConnectDone(AsyncResult ar) {

        CallbackData c = (CallbackData) ar.userObj;

        /*
         * If setup is successful,  ar.result will contain the MMDataConnection instance
         * if setup failure, ar.result will contain the failure reason.
         */
        if (ar.exception == null) { /* connection is up!! */
            MMDataConnection dc = (MMDataConnection) ar.result;
            logi("--------------------------");
            logi("Data call setup : SUCCESS");
            logi("  service type  : " + c.ds);
            logi("  data profile  : " + c.dp.toShortString());
            logi("  data call id  :" + dc.cid);
            logi("  ip version    : " + c.ipv);
            logi("--------------------------");

            //mark requested service type as active through this dc, or else
            //updateDataConnections() will tear it down.
            mDpt.setServiceTypeAsActive(c.ds, dc, c.ipv);

            if (c.ds == DataServiceType.SERVICE_TYPE_DEFAULT) {
                SystemProperties.set("gsm.defaultpdpcontext.active", "true");
            }

            //we might have other things to do, so call update updateDataConnections() again.
            updateDataConnections(c.reason);
            return; //done.
        }

        //ASSERT: Data call setup has failed.

        DataConnectionFailCause cause = (DataConnectionFailCause) (ar.result);

        logi("--------------------------");
        logi("Data call setup : FAILED");
        logi("  service type  : " + c.ds);
        logi("  data profile  : " + c.dp.toShortString());
        logi("  ip version    : " + c.ipv);
        logi("  fail cause    : " + cause);
        logi("--------------------------");

        boolean needDataConnectionUpdate = true;

        /*
         * look at the error code and determine what is the best thing to do :
         * there is no guarantee that modem/network is capable of reporting the
         * correct failure reason, so we do our best to get all requested
         * services up, but somehow making sure we don't retry endlessly.
         */

        if (cause == DataConnectionFailCause.IP_VERSION_NOT_SUPPORTED) {
            /*
             * it might not be possible for us to know if its the network that
             * doesn't support IPV6 in general, or if its the profile we tried
             * that doesn't support IPV6!
             */
            c.dp.setWorking(false, c.ipv);
            // set state to scanning because we are now trying for other data
            // profiles that might work with this ipv.
            updateStateAndNotify(State.SCANNING, c.ds, c.ipv, c.reason);
        } else if (cause.isDataProfileFailure()) {
            /*
             * this profile doesn't work, mark it as not working, so that we
             * have other profiles to try with. It is possible that
             * modem/network didn't report IP_VERSION_NOT_SUPPORTED, but profile
             * might still work with other IPV.
             */
            c.dp.setWorking(false, c.ipv);
            updateStateAndNotify(State.SCANNING, c.ds, c.ipv, c.reason);
        } else if (cause.isPdpAvailabilityFailure()) {
            /*
             * not every modem, or network might be able to report this but if
             * we know this is the failure reason, we know exactly what to do!
             * check if low priority services are active, if yes tear it down!
             */
            if (disconnectOneLowPriorityDataCall(c.ds, c.reason)) {
                needDataConnectionUpdate = false;
                // will be called, when disconnect is complete.
            }
        } else if (disconnectOneLowPriorityDataCall(c.ds, c.reason)) {
            /*
             * We do this because there is no way to know if the failure was caused
             * because of network resources not being available!
             * */
            needDataConnectionUpdate = false;
        } else if (cause.isPermanentFail()) {
            /*
             * even though modem reports permanent failure, it is not clear
             * if failure is related to data profile, ip version, mpdp etc.
             * its safer to try and exhaust all data profiles.
             */
            c.dp.setWorking(false, c.ipv);
            updateStateAndNotify(State.SCANNING, c.ds, c.ipv, c.reason);
        } else {
            /*
             * If we reach here, then it is a temporary failure and we are trying
             * to setup data call on the highest priority service that is enabled.
             * 1. Retry if possible
             * 2. If no more retries possible, disable the data profile.
             * 3. If no more valid data profiles, mark service as disabled and set state
             *    to failed, notify.
             * 4. if default is the highest priority service left enabled,
             *    it will be retried forever!
             */

            RetryManager retryManager = mDpt.getRetryManager(c.ds);

            boolean scheduleAlarm = false;
            int nextReconnectDelay = 0; /* if scheduleAlarm == true */

            if (retryManager.isRetryNeeded()) {
                /* 1 : we have retries left */
                scheduleAlarm  = true;
                nextReconnectDelay = retryManager.getRetryTimer();
                retryManager.increaseRetryCount();
            } else {
                /* 2 : enough of retries. disable the data profile */
                c.dp.setWorking(false, c.ipv);
                if (mDpt.getNextWorkingDataProfile(c.ds,
                        getDataProfileTypeToUse(), c.ipv) != null) {
                    updateStateAndNotify(State.SCANNING, c.ds, c.ipv, c.reason);
                } else {
                    if (c.ds != DataServiceType.SERVICE_TYPE_DEFAULT) {
                        /* 3 */
                        // but make sure service is not active on different IPV!
                        updateStateAndNotify(State.FAILED, c.ds, c.ipv, c.reason);
                        if (mDpt.isServiceTypeActive(c.ds) == false) {
                            mDpt.setServiceTypeEnabled(c.ds, false);
                        }
                    } else {
                        /* 4 */
                        /* we don't have any higher priority services
                         * enabled and we ran out of other profiles to try.
                         * So retry forever with the last profile we have.
                         */
                        c.dp.setWorking(true, c.ipv);
                        updateStateAndNotify(State.FAILED, c.ds, c.ipv, c.reason);
                        notifyDataConnectionFail(c.reason);

                        retryManager.retryForeverUsingLastTimeout();
                        scheduleAlarm = true;
                        nextReconnectDelay = retryManager.getRetryTimer();
                        retryManager.increaseRetryCount();
                    }
                }
            }

            if (scheduleAlarm) {
                logd("Scheduling next attempt on " + c.ds +
                        " for " + (nextReconnectDelay / 1000) + "s");

                AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

                Intent intent = new Intent(INTENT_RECONNECT_ALARM);
                intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, c.reason);

                mReconnectIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
                // cancel any pending wakeup - TODO: does this work?
                am.cancel(mReconnectIntent);
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime()
                        + nextReconnectDelay, mReconnectIntent);
                needDataConnectionUpdate = false;
            }
        }

        if (needDataConnectionUpdate) {
            updateDataConnections(c.reason);
        }

        logDataConnectionFailure(c.ds, c.dp, c.ipv, cause);
    }

    private void logDataConnectionFailure(DataServiceType ds, DataProfile dp, IPVersion ipv,
            DataConnectionFailCause cause) {
        if (cause.isEventLoggable()) {
            CellLocation loc = TelephonyManager.getDefault().getCellLocation();
            int id = -1;
            if (loc != null) {
                if (loc instanceof GsmCellLocation)
                    id = ((GsmCellLocation) loc).getCid();
                else
                    id = ((CdmaCellLocation) loc).getBaseStationId();
            }
            EventLog.List val = new EventLog.List(cause.ordinal(), id, TelephonyManager
                    .getDefault().getNetworkType());
            if (getRadioTechnology().isGsm()
                    || getRadioTechnology() == RadioTechnology.RADIO_TECH_EHRPD) {
                EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_RADIO_PDP_SETUP_FAIL, val);
            } else {
                EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_CDMA_DATA_SETUP_FAILED, val);
            }
        }
    }

    /* disconnect exactly one data call whos priority is lower than serviceType */
    private boolean disconnectOneLowPriorityDataCall(DataServiceType serviceType, String reason) {
        for (DataServiceType ds : DataServiceType.values()) {
            if (ds.isLowerPriorityThan(serviceType) && mDpt.isServiceTypeEnabled(ds)
                    && mDpt.isServiceTypeActive(ds)) {
                // we are clueless as to whether IPV4/IPV6 are on same network PDP or
                // different, so disconnect both.
                boolean disconnectDone = false;
                DataConnection dc;
                dc = mDpt.getActiveDataConnection(ds, IPVersion.IPV4);
                if (dc != null) {
                    tryDisconnectDataCall(dc, reason);
                    disconnectDone = true;
                }
                dc = mDpt.getActiveDataConnection(ds, IPVersion.IPV6);
                if (dc != null) {
                    tryDisconnectDataCall(dc, reason);
                    disconnectDone = true;
                }
                if (disconnectDone) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void onDisconnectDone(AsyncResult ar) {
        logv("onDisconnectDone: reason=" + (String) ar.userObj);
        updateDataConnections((String) ar.userObj);
    }

    public void disconnectAllConnections(String reason) {
        mDisconnectAllDataCalls = true;
        updateDataConnections(reason);
    }

    /*
     * (non-Javadoc)
     * @seecom.android.internal.telephony.data.DataConnectionTracker#
     * onServiceTypeEnableDisable() This function does the following:
     */
    synchronized protected void onUpdateDataConnections(String reason) {
        logv("onUpdateDataConnections: reason=" + reason);
        /*
         * Phase 1:
         * - Free up any data calls that we don't need.
         * - Some data calls, may have got inactive, without the data profile tracker
         *   knowing about it, so update it.
         */

        boolean wasDcDisconnected = false;
        boolean isAnyDcActive = false;

        for (DataConnection dc : mDataConnectionList) {
            if (dc.getState() == DataConnection.State.INACTIVE) {
                /*
                 * 1a. Check and fix any services that think they are active
                 * through this inactive DC.
                 */
                for (DataServiceType ds : DataServiceType.values()) {
                    if (mDpt.getActiveDataConnection(ds, IPVersion.IPV4) == dc) {
                        mDpt.setServiceTypeAsInactive(ds, IPVersion.IPV4);
                    }
                    if (mDpt.getActiveDataConnection(ds, IPVersion.IPV6) == dc) {
                        mDpt.setServiceTypeAsInactive(ds, IPVersion.IPV6);
                    }
                }
            } else if (dc.getState() == DataConnection.State.ACTIVE) {
                /*
                 * 1b. If this active data call is not in use by any enabled
                 * service, bring it down.
                 */
                isAnyDcActive = true;
                boolean needsTearDown = true;
                for (DataServiceType ds : DataServiceType.values()) {
                    if (mDpt.isServiceTypeEnabled(ds)
                            && mDpt.getActiveDataConnection(ds, dc.getIpVersion()) == dc) {
                        needsTearDown = false;
                        break;
                    }
                }
                if (needsTearDown || mDisconnectAllDataCalls == true) {
                    wasDcDisconnected = wasDcDisconnected | tryDisconnectDataCall(dc, reason);
                }
            }
        }

        if (wasDcDisconnected == true) {
            /*
             * if something was disconnected, then wait for at least one
             * disconnect to complete, before setting up data calls again.
             * this will ensure that all possible data connections are freed up
             * before setting up new ones.
             */
            return;
        }

        if (mDisconnectAllDataCalls) {
            /*
             * Someone had requested that all calls be torn down.
             * Either there is no calls to disconnect, or we have already asked
             * for all data calls to be disconnected, so reset the flag.
             */
            mDisconnectAllDataCalls = false;
            //check for pending power off message
            if (mPendingPowerOffCompleteMsg != null) {
                mPendingPowerOffCompleteMsg.sendToTarget();
                mPendingPowerOffCompleteMsg = null;
            }
        }

        // Check for data readiness!
        boolean isReadyForData = isReadyForData()
                                    && getDesiredPowerState()
                                    && mCm.getRadioState().isOn();

        if (isReadyForData == false) {
            logi("Not Ready for Data :");
            logi("   " + "getDesiredPowerState() = " + getDesiredPowerState());
            logi("   " + "mCm.getRadioState() = " + mCm.getRadioState());
            logi("   " + dumpDataReadinessinfo());
            return; // we will be called, when some event, triggers us back into readiness.
        }

        /*
         * Phase 2: Ensure that all requested services are active. Do setup data
         * call as required in order of decreasing service priority - highest priority
         * service gets data call setup first!
         */
        boolean isAnyServiceEnabled = false;
        for (DataServiceType ds : DataServiceType.getPrioritySortedValues()) {
            /*
             * 2a : Poll all data calls and update the latest info.
             */
            for (DataConnection dc : mDataConnectionList) {
                if (dc.getState() == DataConnection.State.ACTIVE
                        && dc.getDataProfile().canHandleServiceType(ds)) {
                    IPVersion ipv = dc.getIpVersion();
                    if (mDpt.getActiveDataConnection(ds, ipv) == null) {
                        mDpt.setServiceTypeAsActive(ds, dc, ipv);
                    }
                }
            }
            /*
             * 2b : Bring up data calls as required.
             */
            if (mDpt.isServiceTypeEnabled(ds) == true) {
                isAnyServiceEnabled = true;
                //IPV4
                if (SUPPORT_IPV4 && mDpt.isServiceTypeActive(ds, IPVersion.IPV4) == false) {
                    boolean setupDone = trySetupDataCall(ds, IPVersion.IPV4, reason);
                    if (setupDone)
                        return; //one at a time, in order of priority
                }
                // IPV6
                if (SUPPORT_IPV6 && mDpt.isServiceTypeActive(ds, IPVersion.IPV6) == false) {
                    boolean setupDone = trySetupDataCall(ds, IPVersion.IPV6, reason);
                    if (setupDone)
                        return; //one at a time, in order of priority
                }
            }
        }

        if (isAnyDcActive == false && isAnyServiceEnabled == true) {
            /*
             * We have services enabled, but we don't have any dc active. if we
             * reach here, it means that we ran out of data profiles to try. So
             * notify fail.
             */
            notifyDataConnectionFail(reason);
        }
    }

    private boolean getDesiredPowerState() {
        return mDesiredPowerState;
    }

    @Override
    public synchronized void setDataConnectionAsDesired(boolean desiredPowerState,
            Message onCompleteMsg) {

        mDesiredPowerState = desiredPowerState;
        mPendingPowerOffCompleteMsg = null;

        /*
         * TODO: fix this workaround. For 1x, we should not disconnect data call
         * before powering off.
         */

        if (mDesiredPowerState == false && getRadioTechnology() != RadioTechnology.RADIO_TECH_1xRTT) {
            mPendingPowerOffCompleteMsg = onCompleteMsg;
            disconnectAllConnections(Phone.REASON_RADIO_TURNED_OFF);
            return;
        }

        if (onCompleteMsg != null) {
            onCompleteMsg.sendToTarget();
        }
    }

    private boolean isReadyForData() {

        //TODO: Check voice call state, emergency call back info
        boolean isDataEnabled = isDataConnectivityEnabled();

        boolean roaming = mDsst.getServiceState().getRoaming();
        isDataEnabled = isDataEnabled && (!roaming || getDataOnRoamingEnabled());

        int dataRegState = this.mDsst.getServiceState().getState();
        isDataEnabled = isDataEnabled
                        && (dataRegState == ServiceState.STATE_IN_SERVICE || mNoAutoAttach);

        RadioTechnology r = getRadioTechnology();

        //TODO: EHRPD requires that both SIM records and RUIM records are loaded?
        if (r.isGsm()
                || r == RadioTechnology.RADIO_TECH_EHRPD
                || (r.isUnknown() && mNoAutoAttach)) {
            isDataEnabled = isDataEnabled && mDsst.mSimRecords != null
                    && mDsst.mSimRecords.getRecordsLoaded() && !mIsPsRestricted;
        }

        if (r.isCdma()) {
            isDataEnabled = isDataEnabled
                    && (mDsst.mCdmaSubscriptionSource == Phone.CDMA_SUBSCRIPTION_NV
                            || (mDsst.mRuimRecords != null
                                    && mDsst.mRuimRecords.getRecordsLoaded()));
        }

        return isDataEnabled;
    }

    /**
     * The only circumstances under which we report that data connectivity is not
     * possible are
     * <ul>
     * <li>Data roaming is disallowed and we are roaming.</li>
     * <li>The current data state is {@code DISCONNECTED} for a reason other than
     * having explicitly disabled connectivity. In other words, data is not available
     * because the phone is out of coverage or some like reason.</li>
     * </ul>
     * @return {@code true} if data connectivity is possible, {@code false} otherwise.
     */
    public boolean isDataConnectivityPossible() {
        //TODO: is there any difference from isReadyForData()?
        return isReadyForData();
    }

    private RadioTechnology getRadioTechnology() {
        return RadioTechnology.getRadioTechFromInt(mDsst.getServiceState()
                .getRadioTechnology());
    }

    public String dumpDataReadinessinfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("[DataRadioTech = ").append(getRadioTechnology());
        sb.append(", data network state = ").append(mDsst.getServiceState().getState());
        sb.append(", mMasterDataEnabled = ").append(mMasterDataEnabled);
        sb.append(", is Roaming = ").append(mDsst.getServiceState().getRoaming());
        sb.append(", dataOnRoamingEnable = ").append(getDataOnRoamingEnabled());
        sb.append(", isPsRestricted = ").append(mIsPsRestricted);
        sb.append(", desiredPowerState  = ").append(getDesiredPowerState());
        sb.append(", mSIMRecords = ");
        if (mDsst.mSimRecords != null)
            sb.append(mDsst.mSimRecords.getRecordsLoaded());
        sb.append(", mRuimRecords = ");
        if (mDsst.mRuimRecords != null)
            sb.append(mDsst.mRuimRecords.getRecordsLoaded());
        sb.append("]");
        return sb.toString();
    }

    private boolean tryDisconnectDataCall(DataConnection dc, String reason) {
        logv("trySetupDataCall : dc=" + dc + ", reason=" + reason);
        dc.disconnect(obtainMessage(EVENT_DISCONNECT_DONE, reason));
        return true;
    }

    class CallbackData {
        DataProfile dp;
        IPVersion ipv;
        String reason;
        DataServiceType ds;
    }

    private boolean trySetupDataCall(DataServiceType ds, IPVersion ipv, String reason) {
        logv("trySetupDataCall : ds=" + ds + ", ipv=" + ipv + ", reason=" + reason);
        DataProfile dp = mDpt.getNextWorkingDataProfile(ds, getDataProfileTypeToUse(), ipv);
        /*
         * It might not possible to distinguish an EVDO only network from
         * one that supports EHRPD, until the data call is actually made! So
         * if THIS is an EHRPD capable device, then we have to make
         * sure that we send APN if available. But, if we do not have APNs
         * configured then we should fall back to NAI!
         */
        if (dp == null && mIsEhrpdCapable && getRadioTechnology().isEvdo()) {
            dp = mDpt.getNextWorkingDataProfile(ds, DataProfileType.PROFILE_TYPE_3GPP2_NAI, ipv);
        }
        if (dp == null) {
            logw("no working data profile available to establish service type " + ds + "on " + ipv);
            updateStateAndNotify(State.FAILED, ds, ipv, reason);
            return false;
        }
        DataConnection dc = findFreeDataCall();
        if (dc == null) {
            // if this happens, it probably means that our data call list is not
            // big enough!
            boolean ret = disconnectOneLowPriorityDataCall(ds, reason);
            // irrespective of ret, we should return true here
            // - if a call was indeed disconnected, then updateDataConnections()
            //   will take care of setting up call again
            // - if no calls were disconnected, then updateDataConnections will fail for every
            //   service type anyway.
            return true;
        }

        updateStateAndNotify(State.CONNECTING, ds, ipv, reason);

        //Assertion: dc!=null && dp!=null
        CallbackData c = new CallbackData();
        c.dp = dp;
        c.ds = ds;
        c.ipv = ipv;
        c.reason = reason;
        dc.connect(dp, obtainMessage(EVENT_CONNECT_DONE, c), ipv);
        return true;
    }

    private DataProfileType getDataProfileTypeToUse() {
        DataProfileType type = null;
        RadioTechnology r = getRadioTechnology();
        if (r == RadioTechnology.RADIO_TECH_UNKNOWN || r == null) {
            type = null;
        } else if (r == RadioTechnology.RADIO_TECH_EHRPD
                || ( mIsEhrpdCapable && r.isEvdo())) {
            /*
             * It might not possible to distinguish an EVDO only network from
             * one that supports EHRPD, until the data call is actually made! So
             * if THIS is an EHRPD capable device, then we have to make
             * sure that we send APN if its available!
             */
            type = DataProfileType.PROFILE_TYPE_3GPP_APN;
        } else if (r.isGsm()) {
            type = DataProfileType.PROFILE_TYPE_3GPP_APN;
        } else {
            type = DataProfileType.PROFILE_TYPE_3GPP2_NAI;
        }
        return type;
    }

    private void createDataCallList() {
        mDataConnectionList = new ArrayList<DataConnection>();
        DataConnection dc;

        for (int i = 0; i < DATA_CONNECTION_POOL_SIZE; i++) {
            dc = new MMDataConnection(mContext, mCm);
            mDataConnectionList.add(dc);
        }
    }

    private void destroyDataCallList() {
        if (mDataConnectionList != null) {
            mDataConnectionList.removeAll(mDataConnectionList);
        }
    }

    private MMDataConnection findFreeDataCall() {
        for (DataConnection conn : mDataConnectionList) {
            MMDataConnection dc = (MMDataConnection) conn;
            if (dc.getState() == DataConnection.State.INACTIVE) {
                return dc;
            }
        }
        return null;
    }

    protected void startNetStatPoll() {
        if (mPollNetStat.isEnablePoll() == false) {
            mPollNetStat.resetPollStats();
            mPollNetStat.setEnablePoll(true);
            mPollNetStat.run();
        }
    }

    protected void stopNetStatPoll() {
        mPollNetStat.setEnablePoll(false);
        removeCallbacks(mPollNetStat);
    }

    public boolean getSocketDataCallEnabled() {
        try {
            return Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.SOCKET_DATA_CALL_ENABLE) > 0;
        } catch (SettingNotFoundException e) {
            // Data connection should be enabled by default.
            // So return true here.
            return true;
        }
    }

    // Retrieve the data roaming setting from the shared preferences.
    public boolean getDataOnRoamingEnabled() {
        try {
            return Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.DATA_ROAMING) > 0;
        } catch (SettingNotFoundException snfe) {
            return false;
        }
    }

    public ServiceState getDataServiceState() {
        return mDsst.getServiceState();
    }

    @Override
    protected boolean isConcurrentVoiceAndData() {
        return mDsst.isConcurrentVoiceAndData();
    }

    public DataActivityState getDataActivityState() {
        DataActivityState ret = DataActivityState.NONE;
        if (getDataServiceState().getState() == ServiceState.STATE_IN_SERVICE) {
            switch (mPollNetStat.getActivity()) {
                case DATAIN:
                    ret = DataActivityState.DATAIN;
                    break;
                case DATAOUT:
                    ret = DataActivityState.DATAOUT;
                    break;
                case DATAINANDOUT:
                    ret = DataActivityState.DATAINANDOUT;
                    break;
            }
        }
        return ret;
    }

    public void notifyDataActivity() {
        mNotifier.notifyDataActivity(this);
    }

    @SuppressWarnings("unchecked")
    public List<DataConnection> getCurrentDataConnectionList() {
        ArrayList<DataConnection> dcs = (ArrayList<DataConnection>) mDataConnectionList.clone();
        return dcs;
    }

    void loge(String string) {
        Log.e(LOG_TAG, "[DCT] " + string);
    }

    void logw(String string) {
        Log.w(LOG_TAG, "[DCT] " + string);
    }

    void logd(String string) {
        Log.d(LOG_TAG, "[DCT] " + string);
    }

    void logv(String string) {
        Log.v(LOG_TAG, "[DCT] " + string);
    }

    void logi(String string) {
        Log.i(LOG_TAG, "[DCT] " + string);
    }
}