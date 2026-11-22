/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Feeds the Glyph engine: notification flashes and progress bars from any app, plus charging,
 * volume and incoming-call events. Runs as long as notification access is granted.
 */
public class GlyphNotificationListener extends NotificationListenerService {
    private static final String TAG = "GlyphListener";

    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_PREV_VOLUME_VALUE =
            "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";

    private static final long CHARGING_MS = 4000;
    private static final long VOLUME_MS = 1500;
    /** A progress bar removed at or above this is treated as finished. */
    private static final float COMPLETE_THRESHOLD = 0.9f;

    private static final class Progress {
        String pkg;
        float fraction;
        boolean indeterminate;
        long updated;
    }

    private final Map<String, Progress> mProgress = new HashMap<>();
    private final Set<String> mSeen = new HashSet<>();

    private GlyphEngine mEngine;
    private AudioManager mAudio;
    private TelephonyManager mTelephony;
    private boolean mConnected;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                if (!GlyphSettings.isChargingEnabled(context)) return;
                BatteryManager bm = context.getSystemService(BatteryManager.class);
                int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (level >= 0) mEngine.showLevel(level / 100f, CHARGING_MS, true);
            } else if (ACTION_VOLUME_CHANGED.equals(action)) {
                if (!GlyphSettings.isVolumeEnabled(context)) return;
                int value = intent.getIntExtra(EXTRA_VOLUME_VALUE, -1);
                int prev = intent.getIntExtra(EXTRA_PREV_VOLUME_VALUE, -1);
                int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
                if (value < 0 || stream < 0 || value == prev) return;
                int max = mAudio.getStreamMaxVolume(stream);
                if (max > 0) mEngine.showLevel((float) value / max, VOLUME_MS, false);
            }
        }
    };

    private final class CallListener extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            if (state == TelephonyManager.CALL_STATE_RINGING
                    && GlyphSettings.isCallsEnabled(GlyphNotificationListener.this)) {
                mEngine.startCall();
            } else {
                mEngine.stopCall();
            }
        }
    }

    private final CallListener mCallListener = new CallListener();

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener =
            (prefs, key) -> {
                mEngine.refresh();
                updateProgress();
            };

    @Override
    public void onCreate() {
        super.onCreate();
        mEngine = GlyphEngine.get(this);
        mAudio = getSystemService(AudioManager.class);
        mTelephony = getSystemService(TelephonyManager.class);
    }

    @Override
    public void onListenerConnected() {
        mConnected = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(ACTION_VOLUME_CHANGED);
        registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        try {
            mTelephony.registerTelephonyCallback(getMainExecutor(), mCallListener);
        } catch (SecurityException e) {
            Log.w(TAG, "No phone state access; call flashing disabled", e);
        }
        GlyphSettings.prefs(this).registerOnSharedPreferenceChangeListener(mPrefListener);

        // Pick up progress bars that were already showing; don't flash for old notifications.
        mProgress.clear();
        mSeen.clear();
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                mSeen.add(sbn.getKey());
                trackProgress(sbn, false);
            }
        }
        updateProgress();
    }

    @Override
    public void onListenerDisconnected() {
        if (!mConnected) return;
        mConnected = false;
        unregisterReceiver(mReceiver);
        mTelephony.unregisterTelephonyCallback(mCallListener);
        GlyphSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(mPrefListener);
        mProgress.clear();
        mEngine.clearProgress();
        mEngine.stopCall();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (getPackageName().equals(sbn.getPackageName())) return;
        boolean isNew = mSeen.add(sbn.getKey());
        boolean isProgress = trackProgress(sbn, true);
        updateProgress();
        if (isNew && !isProgress && shouldFlash(sbn, rankingMap)) {
            mEngine.flashNotification();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            int reason) {
        mSeen.remove(sbn.getKey());
        Progress p = mProgress.remove(sbn.getKey());
        if (p != null && !p.indeterminate && p.fraction >= COMPLETE_THRESHOLD
                && isProgressShown(p)) {
            mEngine.blinkComplete();
        }
        updateProgress();
    }

    private boolean shouldFlash(StatusBarNotification sbn, RankingMap rankingMap) {
        if (!GlyphSettings.isNotificationsEnabled(this)) return false;
        Notification n = sbn.getNotification();
        if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        Ranking ranking = new Ranking();
        if (rankingMap == null || !rankingMap.getRanking(sbn.getKey(), ranking)) return false;
        // Respect Do Not Disturb and silent channels.
        return ranking.matchesInterruptionFilter()
                && ranking.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT;
    }

    /**
     * Records sbn's progress bar, if it has one. Returns true if sbn is a progress notification.
     * A bar that reaches its maximum is removed and, if wanted, blinks as complete.
     */
    private boolean trackProgress(StatusBarNotification sbn, boolean allowBlink) {
        Bundle extras = sbn.getNotification().extras;
        int max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        boolean indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE);
        if (!extras.containsKey(Notification.EXTRA_PROGRESS) || (max <= 0 && !indeterminate)) {
            Progress old = mProgress.remove(sbn.getKey());
            // Some apps finish by replacing the bar with a plain "done" notification.
            if (allowBlink && old != null && !old.indeterminate
                    && old.fraction >= COMPLETE_THRESHOLD && isProgressShown(old)) {
                mEngine.blinkComplete();
            }
            return false;
        }
        int progress = extras.getInt(Notification.EXTRA_PROGRESS, 0);
        if (!indeterminate && progress >= max) {
            Progress old = mProgress.remove(sbn.getKey());
            if (allowBlink && old != null && isProgressShown(old)) mEngine.blinkComplete();
            return true;
        }
        Progress p = mProgress.get(sbn.getKey());
        if (p == null) {
            p = new Progress();
            mProgress.put(sbn.getKey(), p);
        }
        p.pkg = sbn.getPackageName();
        p.indeterminate = indeterminate;
        p.fraction = indeterminate ? 0f : (float) progress / max;
        p.updated = System.currentTimeMillis();
        return true;
    }

    private boolean isProgressShown(Progress p) {
        return GlyphSettings.isProgressEnabled(this)
                && !GlyphSettings.getProgressExcluded(this).contains(p.pkg);
    }

    /** Shows the most recently updated progress bar from an allowed app. */
    private void updateProgress() {
        Progress best = null;
        for (Progress p : mProgress.values()) {
            if (!isProgressShown(p)) continue;
            if (best == null || p.updated > best.updated) best = p;
        }
        if (best == null) {
            mEngine.clearProgress();
        } else if (best.indeterminate) {
            mEngine.setProgressIndeterminate();
        } else {
            mEngine.setProgress(best.fraction);
        }
    }
}
