/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Preference keys and defaults. Keys must match res/xml/glyph_settings.xml. */
final class GlyphSettings {
    static final String KEY_ENABLED = "glyph_enabled";
    static final String KEY_BRIGHTNESS = "glyph_brightness";
    static final String KEY_LEVEL_DIRECTION = "glyph_level_direction";
    static final String KEY_PROGRESS_DIRECTION = "glyph_progress_direction";
    static final String KEY_PROGRESS = "glyph_progress";
    static final String KEY_PROGRESS_EXCLUDED = "glyph_progress_excluded";
    static final String KEY_NOTIFICATIONS = "glyph_notifications";
    static final String KEY_CHARGING = "glyph_charging";
    static final String KEY_VOLUME = "glyph_volume";
    static final String KEY_CALLS = "glyph_calls";

    static final String BOTTOM_UP = "bottom_up";
    static final String TOP_DOWN = "top_down";

    /** Display sources that compete for the LEDs, ranked by the user. */
    static final String SOURCE_CALLS = "calls";
    static final String SOURCE_NOTIFICATIONS = "notifications";
    static final String SOURCE_PROGRESS = "progress";
    static final String SOURCE_LEVELS = "levels";

    /** Default priority, highest first. */
    static final String[] DEFAULT_PRIORITY = {
            SOURCE_CALLS, SOURCE_NOTIFICATIONS, SOURCE_PROGRESS, SOURCE_LEVELS};

    /** Preference keys for priority slots 1 (highest) to 4 (lowest). */
    static final String[] KEY_PRIORITY = {
            "glyph_priority_1", "glyph_priority_2", "glyph_priority_3", "glyph_priority_4"};

    private static final int DEFAULT_BRIGHTNESS_PERCENT = 50;

    private GlyphSettings() {}

    static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Peak LED value (0..255) from the brightness percentage. */
    static int getMaxBrightness(Context context) {
        int percent = prefs(context).getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS_PERCENT);
        return Math.round(255f * Math.max(1, Math.min(100, percent)) / 100f);
    }

    /** Volume, battery and charging meters. Default: bottom to top, starting at the red LED. */
    static boolean isLevelBottomUp(Context context) {
        return BOTTOM_UP.equals(prefs(context).getString(KEY_LEVEL_DIRECTION, BOTTOM_UP));
    }

    /** Notification progress bars. Default: top to bottom, finishing at the red LED. */
    static boolean isProgressBottomUp(Context context) {
        return BOTTOM_UP.equals(prefs(context).getString(KEY_PROGRESS_DIRECTION, TOP_DOWN));
    }

    /**
     * The four sources from highest to lowest priority. Duplicate or unknown slot values are
     * skipped and any missing source is appended in default order, so the result is always a
     * complete ranking.
     */
    static List<String> getPriorityOrder(Context context) {
        SharedPreferences prefs = prefs(context);
        List<String> valid = Arrays.asList(DEFAULT_PRIORITY);
        List<String> order = new ArrayList<>(DEFAULT_PRIORITY.length);
        for (int i = 0; i < KEY_PRIORITY.length; i++) {
            String source = prefs.getString(KEY_PRIORITY[i], DEFAULT_PRIORITY[i]);
            if (valid.contains(source) && !order.contains(source)) order.add(source);
        }
        for (String source : DEFAULT_PRIORITY) {
            if (!order.contains(source)) order.add(source);
        }
        return order;
    }

    static boolean isProgressEnabled(Context context) {
        return isEnabled(context) && prefs(context).getBoolean(KEY_PROGRESS, true);
    }

    static Set<String> getProgressExcluded(Context context) {
        return prefs(context).getStringSet(KEY_PROGRESS_EXCLUDED, Collections.emptySet());
    }

    static boolean isNotificationsEnabled(Context context) {
        return isEnabled(context) && prefs(context).getBoolean(KEY_NOTIFICATIONS, true);
    }

    static boolean isChargingEnabled(Context context) {
        return isEnabled(context) && prefs(context).getBoolean(KEY_CHARGING, true);
    }

    static boolean isVolumeEnabled(Context context) {
        return isEnabled(context) && prefs(context).getBoolean(KEY_VOLUME, true);
    }

    static boolean isCallsEnabled(Context context) {
        return isEnabled(context) && prefs(context).getBoolean(KEY_CALLS, true);
    }
}
