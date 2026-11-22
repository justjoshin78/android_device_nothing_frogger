/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Low-level access to the aw20036 Glyph LED driver.
 *
 * The Phone (4a) has 7 Glyph LEDs. A 7-value write to frame_brightness sets them in this order:
 * index 0..5 are the white zones from top to bottom, index 6 is the red LED at the very bottom.
 */
final class GlyphLeds {
    private static final String TAG = "GlyphLeds";

    static final int COUNT = 7;
    static final int RED = 6;

    private static final String BASE = "/sys/class/leds/aw20036_led/";
    private static final String FRAME = BASE + "frame_brightness";
    private static final String MODE = BASE + "operating_mode";

    private static final int MODE_ACTIVE = 1;
    private static final int MODE_STANDBY = 2;

    /** Hardware order (top to bottom), used for "top to bottom" fills. */
    static final int[] TOP_DOWN = {0, 1, 2, 3, 4, 5, 6};
    /** Reverse hardware order, used for "bottom to top" fills. */
    static final int[] BOTTOM_UP = {6, 5, 4, 3, 2, 1, 0};

    private boolean mActive;
    private String mLastFrame;
    private boolean mErrorLogged;

    /** Writes intensities (0..1 per LED, hardware order) scaled to maxBrightness (0..255). */
    void show(float[] levels, int maxBrightness) {
        StringBuilder sb = new StringBuilder(32);
        boolean anyOn = false;
        for (int i = 0; i < COUNT; i++) {
            float l = Math.max(0f, Math.min(1f, levels[i]));
            int v = Math.round(l * maxBrightness);
            if (v > 0) anyOn = true;
            if (i > 0) sb.append(' ');
            sb.append(v);
        }
        String frame = sb.toString();
        if (anyOn && !mActive) {
            write(MODE, Integer.toString(MODE_ACTIVE));
            mActive = true;
            mLastFrame = null;
        }
        if (!frame.equals(mLastFrame)) {
            write(FRAME, frame);
            mLastFrame = frame;
        }
    }

    /** Turns every LED off and puts the chip in standby. */
    void off() {
        if (mLastFrame == null || !mLastFrame.equals("0 0 0 0 0 0 0")) {
            write(FRAME, "0 0 0 0 0 0 0");
            mLastFrame = "0 0 0 0 0 0 0";
        }
        if (mActive) {
            write(MODE, Integer.toString(MODE_STANDBY));
            mActive = false;
        }
    }

    private void write(String path, String value) {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
            mErrorLogged = false;
        } catch (IOException e) {
            if (!mErrorLogged) {
                Log.e(TAG, "Failed to write " + path, e);
                mErrorLogged = true;
            }
        }
    }
}
