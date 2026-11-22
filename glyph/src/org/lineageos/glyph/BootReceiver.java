/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Grants the Glyph listener notification access on first boot and turns the LEDs off. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GlyphBoot";
    private static final String KEY_ACCESS_GRANTED = "listener_access_granted_once";

    @Override
    public void onReceive(Context context, Intent intent) {
        ensureListenerAccess(context);
        GlyphEngine.get(context).refresh();
    }

    /**
     * Grants notification access the first time only, so a user who later revokes it in
     * Special app access keeps that choice.
     */
    static void ensureListenerAccess(Context context) {
        if (GlyphSettings.prefs(context).getBoolean(KEY_ACCESS_GRANTED, false)) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        ComponentName listener = new ComponentName(context, GlyphNotificationListener.class);
        try {
            if (!nm.isNotificationListenerAccessGranted(listener)) {
                nm.setNotificationListenerAccessGranted(listener, true);
            }
            GlyphSettings.prefs(context).edit().putBoolean(KEY_ACCESS_GRANTED, true).apply();
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot grant notification access", e);
        }
    }
}
