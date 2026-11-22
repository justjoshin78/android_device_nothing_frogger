/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.Arrays;

/**
 * Decides what the Glyph LEDs show and animates it on a background thread.
 *
 * Several sources can want the LEDs at once; the active one ranked highest in the user's
 * priority order wins (default: calls > notifications > progress > levels). A progress bar's
 * completion blink shares the progress rank, ahead of the bar itself. A Settings preview always
 * plays on top.
 */
final class GlyphEngine {
    private static final long FRAME_MS = 33;

    // Effect slots
    private static final int PRIO_PROGRESS = 0;
    private static final int PRIO_COMPLETE = 1;
    private static final int PRIO_FLASH = 2;
    private static final int PRIO_LEVEL = 3;
    private static final int PRIO_CALL = 4;
    private static final int PRIO_PREVIEW = 5;
    private static final int PRIO_COUNT = 6;

    /** One thing the LEDs can show. */
    private abstract static class Effect {
        final long start = SystemClock.uptimeMillis();

        /** Fills out (hardware order, 0..1). Returns false once the effect has finished. */
        abstract boolean render(long elapsed, float[] out);

        /** Whether the frame changes over time (needs ticking). */
        boolean animated() {
            return true;
        }
    }

    private static GlyphEngine sInstance;

    private final Context mContext;
    private final Handler mHandler;
    private final GlyphLeds mLeds = new GlyphLeds();
    private final Effect[] mEffects = new Effect[PRIO_COUNT];
    private final float[] mFrame = new float[GlyphLeds.COUNT];
    private final Runnable mRender = this::render;

    static synchronized GlyphEngine get(Context context) {
        if (sInstance == null) {
            sInstance = new GlyphEngine(context.getApplicationContext());
        }
        return sInstance;
    }

    private GlyphEngine(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread("GlyphEngine");
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    // ---- Public API (any thread) ----

    /** Shows determinate progress (0..1) until cleared. */
    void setProgress(float fraction) {
        final float p = clamp(fraction);
        post(() -> mEffects[PRIO_PROGRESS] = new Effect() {
            @Override
            boolean render(long elapsed, float[] out) {
                fill(p, progressOrder(), out);
                return true;
            }

            @Override
            boolean animated() {
                return false;
            }
        });
    }

    /** Shows an "in progress, unknown amount" animation until cleared. */
    void setProgressIndeterminate() {
        post(() -> {
            if (mEffects[PRIO_PROGRESS] instanceof Comet) return;
            mEffects[PRIO_PROGRESS] = new Comet(true, 140, 0);
        });
    }

    void clearProgress() {
        post(() -> mEffects[PRIO_PROGRESS] = null);
    }

    /** Two quick blinks of every LED when a progress bar finishes. */
    void blinkComplete() {
        post(() -> mEffects[PRIO_COMPLETE] = new Effect() {
            @Override
            boolean render(long elapsed, float[] out) {
                if (elapsed >= 800) return false;
                float v = (elapsed % 400) < 200 ? 1f : 0f;
                Arrays.fill(out, v);
                return true;
            }
        });
    }

    /** Short double pulse of the white zones for a new notification. */
    void flashNotification() {
        post(() -> mEffects[PRIO_FLASH] = new Effect() {
            @Override
            boolean render(long elapsed, float[] out) {
                if (elapsed >= 900) return false;
                float phase = (elapsed % 450) / 450f;
                float v = (float) Math.sin(Math.PI * phase);
                Arrays.fill(out, v);
                out[GlyphLeds.RED] = 0f;
                return true;
            }
        });
    }

    /** Shows a level (0..1) as a meter for durationMs, filling up over the first 400 ms. */
    void showLevel(float level, long durationMs, boolean animateFill) {
        final float l = clamp(level);
        post(() -> {
            // Volume steps arrive in quick succession: keep the meter up and skip the fill animation.
            boolean wasShowing = mEffects[PRIO_LEVEL] != null;
            final boolean animate = animateFill && !wasShowing;
            mEffects[PRIO_LEVEL] = new Effect() {
                @Override
                boolean render(long elapsed, float[] out) {
                    if (elapsed >= durationMs) return false;
                    float shown = animate ? l * Math.min(1f, elapsed / 400f) : l;
                    fill(shown, levelOrder(), out);
                    return true;
                }
            };
        });
    }

    /** Repeating sweep while the phone rings. */
    void startCall() {
        post(() -> mEffects[PRIO_CALL] = new Comet(false, 90, 500));
    }

    void stopCall() {
        post(() -> mEffects[PRIO_CALL] = null);
    }

    /** Re-reads settings: turns everything off if Glyph was disabled, and redraws. */
    void refresh() {
        post(() -> {
            if (!GlyphSettings.isEnabled(mContext)) {
                Arrays.fill(mEffects, null);
            }
        });
    }

    /** Plays a fill along the chosen direction, used as a preview in Settings. */
    void preview(boolean levelDirection) {
        post(() -> mEffects[PRIO_PREVIEW] = new Effect() {
            @Override
            boolean render(long elapsed, float[] out) {
                if (elapsed >= 2200) return false;
                float p = Math.min(1f, elapsed / 1400f);
                fill(p, levelDirection ? levelOrder() : progressOrder(), out);
                return true;
            }
        });
    }

    // ---- Rendering (engine thread) ----

    private void post(Runnable r) {
        mHandler.post(() -> {
            r.run();
            mHandler.removeCallbacks(mRender);
            render();
        });
    }

    private void render() {
        mHandler.removeCallbacks(mRender);
        if (!GlyphSettings.isEnabled(mContext)) {
            Arrays.fill(mEffects, null);
            mLeds.off();
            return;
        }
        for (int prio : renderOrder()) {
            Effect e = mEffects[prio];
            if (e == null) continue;
            Arrays.fill(mFrame, 0f);
            if (!e.render(SystemClock.uptimeMillis() - e.start, mFrame)) {
                mEffects[prio] = null;
                continue;
            }
            mLeds.show(mFrame, GlyphSettings.getMaxBrightness(mContext));
            if (e.animated()) {
                mHandler.postDelayed(mRender, FRAME_MS);
            }
            return;
        }
        mLeds.off();
    }

    /** Effect slots from highest to lowest priority, following the user's order. */
    private int[] renderOrder() {
        int[] order = new int[PRIO_COUNT];
        int n = 0;
        order[n++] = PRIO_PREVIEW;
        for (String source : GlyphSettings.getPriorityOrder(mContext)) {
            switch (source) {
                case GlyphSettings.SOURCE_CALLS:
                    order[n++] = PRIO_CALL;
                    break;
                case GlyphSettings.SOURCE_NOTIFICATIONS:
                    order[n++] = PRIO_FLASH;
                    break;
                case GlyphSettings.SOURCE_PROGRESS:
                    order[n++] = PRIO_COMPLETE;
                    order[n++] = PRIO_PROGRESS;
                    break;
                case GlyphSettings.SOURCE_LEVELS:
                    order[n++] = PRIO_LEVEL;
                    break;
            }
        }
        return order;
    }

    private int[] levelOrder() {
        return GlyphSettings.isLevelBottomUp(mContext) ? GlyphLeds.BOTTOM_UP : GlyphLeds.TOP_DOWN;
    }

    private int[] progressOrder() {
        return GlyphSettings.isProgressBottomUp(mContext)
                ? GlyphLeds.BOTTOM_UP : GlyphLeds.TOP_DOWN;
    }

    /**
     * Lights LEDs along order in proportion to p. Each LED is one 1/7 step; the leading LED is
     * partly dimmed to show progress between steps.
     */
    static void fill(float p, int[] order, float[] out) {
        float steps = clamp(p) * order.length;
        for (int k = 0; k < order.length; k++) {
            out[order[k]] = clamp(steps - k);
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** A bright dot with a fading tail running along the LEDs, then an optional pause. */
    private final class Comet extends Effect {
        private final boolean mProgressDir;
        private final long mStepMs;
        private final long mPauseMs;

        Comet(boolean progressDirection, long stepMs, long pauseMs) {
            mProgressDir = progressDirection;
            mStepMs = stepMs;
            mPauseMs = pauseMs;
        }

        @Override
        boolean render(long elapsed, float[] out) {
            int[] order = mProgressDir ? progressOrder() : GlyphLeds.TOP_DOWN;
            int span = order.length + 2; // let the tail run off the end
            long period = span * mStepMs + mPauseMs;
            long t = elapsed % period;
            if (t >= span * mStepMs) return true; // pause: all off
            int head = (int) (t / mStepMs);
            for (int k = 0; k < order.length; k++) {
                int d = head - k;
                if (d == 0) out[order[k]] = 1f;
                else if (d == 1) out[order[k]] = 0.35f;
                else if (d == 2) out[order[k]] = 0.1f;
            }
            return true;
        }
    }
}
