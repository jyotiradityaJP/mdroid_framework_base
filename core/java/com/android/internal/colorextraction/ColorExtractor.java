/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.colorextraction;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.types.ExtractionType;
import com.android.internal.colorextraction.types.Tonal;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Class to process wallpaper colors and generate a tonal palette based on them.
 */
public class ColorExtractor implements WallpaperManager.OnColorsChangedListener {

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_DARK = 1;
    public static final int TYPE_EXTRA_DARK = 2;

    //Special tint flags for the no tint colors
    public static final int FLAG_LOCKTINT = 3;
    public static final int FLAG_RECENTSTINT = 4;
    public static final int FLAG_STATUSBARTINT = 5;
    public static final int FLAG_POWERMENUTINT = 6;
    private static final int[] sGradientTypes = new int[]{TYPE_NORMAL, TYPE_DARK, TYPE_EXTRA_DARK};

    private static final String TAG = "ColorExtractor";
    private static final boolean DEBUG = false;

    protected final SparseArray<GradientColors[]> mGradientColors;
    private final ArrayList<WeakReference<OnColorsChangedListener>> mOnColorsChangedListeners;
    private final Context mContext;
    private final ExtractionType mExtractionType;
    protected WallpaperColors mSystemColors;
    protected WallpaperColors mLockColors;
    protected WallpaperColors mLockTintColors  = new WallpaperColors(Color.valueOf(com.android.internal.R.color.lockscreen_wallpaper_tint_off_color), Color.valueOf(com.android.internal.R.color.lockscreen_wallpaper_tint_off_color), Color.valueOf(com.android.internal.R.color.lockscreen_wallpaper_tint_off_color),0);
    protected WallpaperColors mRecentsTintColors  = new WallpaperColors(Color.valueOf(com.android.internal.R.color.recents_wallpaper_tint_off_color), Color.valueOf(com.android.internal.R.color.recents_wallpaper_tint_off_color), Color.valueOf(com.android.internal.R.color.recents_wallpaper_tint_off_color),0);
    protected WallpaperColors mStatusbarTintColors  = new WallpaperColors(Color.valueOf(com.android.internal.R.color.status_bar_wallpaper_tint_off_color), Color.valueOf(com.android.internal.R.color.status_bar_wallpaper_tint_off_color), Color.valueOf(com.android.internal.R.color.status_bar_wallpaper_tint_off_color),0);
    protected WallpaperColors mPowermenuTintColors  = new WallpaperColors(Color.valueOf(com.android.internal.R.color.power_menu_wallpaper_tint_off_color), Color.valueOf(com.android.internal.R.color.power_menu_wallpaper_tint_off_color), Color.valueOf(com.android.internal.R.color.power_menu_wallpaper_tint_off_color),0);

    public ColorExtractor(Context context) {
        this(context, new Tonal(context));
    }

    @VisibleForTesting
    public ColorExtractor(Context context, ExtractionType extractionType) {
        mContext = context;
        mExtractionType = extractionType;

        mGradientColors = new SparseArray<>();
        for (int which : new int[] { WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM, FLAG_LOCKTINT, FLAG_RECENTSTINT, FLAG_STATUSBARTINT, FLAG_POWERMENUTINT}) {
            GradientColors[] colors = new GradientColors[sGradientTypes.length];
            mGradientColors.append(which, colors);
            for (int type : sGradientTypes) {
                colors[type] = new GradientColors();
            }
        }

        mOnColorsChangedListeners = new ArrayList<>();
        GradientColors[] systemColors = mGradientColors.get(WallpaperManager.FLAG_SYSTEM);
        GradientColors[] lockColors = mGradientColors.get(WallpaperManager.FLAG_LOCK);
        GradientColors[] lockTintColors = mGradientColors.get(FLAG_LOCKTINT);
        GradientColors[] recentsTintColors = mGradientColors.get(FLAG_RECENTSTINT);
        GradientColors[] statusbarTintColors = mGradientColors.get(FLAG_STATUSBARTINT);
        GradientColors[] powermenuTintColors = mGradientColors.get(FLAG_POWERMENUTINT);

        WallpaperManager wallpaperManager = mContext.getSystemService(WallpaperManager.class);
        if (wallpaperManager == null) {
            Log.w(TAG, "Can't listen to color changes!");
        } else {
            wallpaperManager.addOnColorsChangedListener(this, null /* handler */);

            // Initialize all gradients with the current colors
            Trace.beginSection("ColorExtractor#getWallpaperColors");
            mSystemColors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            mLockColors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_LOCK);
            Trace.endSection();
        }

        // Initialize all gradients with the current colors
        extractInto(mSystemColors,
                systemColors[TYPE_NORMAL],
                systemColors[TYPE_DARK],
                systemColors[TYPE_EXTRA_DARK]);
        extractInto(mLockColors,
                lockColors[TYPE_NORMAL],
                lockColors[TYPE_DARK],
                lockColors[TYPE_EXTRA_DARK]);
        extractInto(mLockTintColors,
                lockTintColors[TYPE_NORMAL],
                lockTintColors[TYPE_DARK],
                lockTintColors[TYPE_EXTRA_DARK]);
        extractInto(mRecentsTintColors,
                recentsTintColors[TYPE_NORMAL],
                recentsTintColors[TYPE_DARK],
                recentsTintColors[TYPE_EXTRA_DARK]);
        extractInto(mStatusbarTintColors,
                statusbarTintColors[TYPE_NORMAL],
                statusbarTintColors[TYPE_DARK],
                statusbarTintColors[TYPE_EXTRA_DARK]);
        extractInto(mPowermenuTintColors,
                powermenuTintColors[TYPE_NORMAL],
                powermenuTintColors[TYPE_DARK],
                powermenuTintColors[TYPE_EXTRA_DARK]);
    }

    /**
     * Retrieve gradient colors for a specific wallpaper.
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @return colors
     */
    @NonNull
    public GradientColors getColors(int which) {
        return getColors(which, TYPE_DARK);
    }

    /**
     * Get current gradient colors for one of the possible gradient types
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @param type TYPE_NORMAL, TYPE_DARK or TYPE_EXTRA_DARK
     * @return colors
     */
    @NonNull
    public GradientColors getColors(int which, int type) {
        if (type != TYPE_NORMAL && type != TYPE_DARK && type != TYPE_EXTRA_DARK) {
            throw new IllegalArgumentException(
                    "type should be TYPE_NORMAL, TYPE_DARK or TYPE_EXTRA_DARK");
        }
        if (which != WallpaperManager.FLAG_LOCK && which != WallpaperManager.FLAG_SYSTEM && which != FLAG_LOCKTINT
            && which != FLAG_RECENTSTINT && which != FLAG_STATUSBARTINT && which != FLAG_POWERMENUTINT) {
            throw new IllegalArgumentException("which should be FLAG_SYSTEM, FLAG_NORMAL or any of the tint flags (FLAG_LOCKTINT, FLAG_RECENTSTINT, FLAG_STATUSBARTINT or FLAG_POWERMENUTINT)");
        }
        return mGradientColors.get(which)[type];
    }

    /**
     * Get the last available WallpaperColors without forcing new extraction.
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @return Last cached colors
     */
    @Nullable
    public WallpaperColors getWallpaperColors(int which) {
        if (which == WallpaperManager.FLAG_LOCK) {
            return mLockColors;
        } else if (which == WallpaperManager.FLAG_SYSTEM) {
            return mSystemColors;
        } else if (which == FLAG_LOCKTINT) {
            return mLockTintColors;
        } else if (which == FLAG_RECENTSTINT) {
            return mRecentsTintColors;
        } else if (which == FLAG_STATUSBARTINT) {
            return mStatusbarTintColors;
        } else if (which == FLAG_POWERMENUTINT) {
            return mPowermenuTintColors;
        } else {
            throw new IllegalArgumentException("Invalid value for which: " + which);
        }
    }

    @Override
    public void onColorsChanged(WallpaperColors colors, int which) {
        if (DEBUG) {
            Log.d(TAG, "New wallpaper colors for " + which + ": " + colors);
        }
        boolean changed = false;
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            mLockColors = colors;
            GradientColors[] lockColors = mGradientColors.get(WallpaperManager.FLAG_LOCK);
            extractInto(colors, lockColors[TYPE_NORMAL], lockColors[TYPE_DARK],
                    lockColors[TYPE_EXTRA_DARK]);
            changed = true;
        }
        if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
            mSystemColors = colors;
            GradientColors[] systemColors = mGradientColors.get(WallpaperManager.FLAG_SYSTEM);
            extractInto(colors, systemColors[TYPE_NORMAL], systemColors[TYPE_DARK],
                    systemColors[TYPE_EXTRA_DARK]);
            changed = true;
        }

        if ((which & FLAG_LOCKTINT) != 0) {
            mLockTintColors  = colors;
            GradientColors[] lockTintColors = mGradientColors.get(FLAG_LOCKTINT);
            extractInto(colors, lockTintColors[TYPE_NORMAL], lockTintColors[TYPE_DARK],
                    lockTintColors[TYPE_EXTRA_DARK]);
            changed = true;
        }

        if ((which & FLAG_RECENTSTINT) != 0) {
            mRecentsTintColors  = colors;
            GradientColors[] recentsTintColors = mGradientColors.get(FLAG_RECENTSTINT);
            extractInto(colors, recentsTintColors[TYPE_NORMAL], recentsTintColors[TYPE_DARK],
                    recentsTintColors[TYPE_EXTRA_DARK]);
            changed = true;
        }

        if ((which & FLAG_STATUSBARTINT) != 0) {
            mStatusbarTintColors  = colors;
            GradientColors[] statusbarTintColors = mGradientColors.get(FLAG_STATUSBARTINT);
            extractInto(colors, statusbarTintColors[TYPE_NORMAL], statusbarTintColors[TYPE_DARK],
                    statusbarTintColors[TYPE_EXTRA_DARK]);
            changed = true;
        }

        if ((which & FLAG_POWERMENUTINT) != 0) {
            mPowermenuTintColors  = colors;
            GradientColors[] powermenuTintColors = mGradientColors.get(FLAG_POWERMENUTINT);
            extractInto(colors, powermenuTintColors[TYPE_NORMAL], powermenuTintColors[TYPE_DARK],
                    powermenuTintColors[TYPE_EXTRA_DARK]);
            changed = true;
        }

        if (changed) {
            triggerColorsChanged(which);
        }
    }

    protected void triggerColorsChanged(int which) {
        ArrayList<WeakReference<OnColorsChangedListener>> references =
                new ArrayList<>(mOnColorsChangedListeners);
        final int size = references.size();
        for (int i = 0; i < size; i++) {
            final WeakReference<OnColorsChangedListener> weakReference = references.get(i);
            final OnColorsChangedListener listener = weakReference.get();
            if (listener == null) {
                mOnColorsChangedListeners.remove(weakReference);
            } else {
                listener.onColorsChanged(this, which);
            }
        }
    }

    private void extractInto(WallpaperColors inWallpaperColors,
            GradientColors outGradientColorsNormal, GradientColors outGradientColorsDark,
            GradientColors outGradientColorsExtraDark) {
        mExtractionType.extractInto(inWallpaperColors, outGradientColorsNormal,
                outGradientColorsDark, outGradientColorsExtraDark);
    }

    public void destroy() {
        WallpaperManager wallpaperManager = mContext.getSystemService(WallpaperManager.class);
        if (wallpaperManager != null) {
            wallpaperManager.removeOnColorsChangedListener(this);
        }
    }

    public void addOnColorsChangedListener(@NonNull OnColorsChangedListener listener) {
        mOnColorsChangedListeners.add(new WeakReference<>(listener));
    }

    public void removeOnColorsChangedListener(@NonNull OnColorsChangedListener listener) {
        ArrayList<WeakReference<OnColorsChangedListener>> references =
                new ArrayList<>(mOnColorsChangedListeners);
        final int size = references.size();
        for (int i = 0; i < size; i++) {
            final WeakReference<OnColorsChangedListener> weakReference = references.get(i);
            if (weakReference.get() == listener) {
                mOnColorsChangedListeners.remove(weakReference);
                break;
            }
        }
    }

    public static class GradientColors {
        private int mMainColor;
        private int mSecondaryColor;
        private boolean mSupportsDarkText;

        public void setMainColor(int mainColor) {
            mMainColor = mainColor;
        }

        public void setSecondaryColor(int secondaryColor) {
            mSecondaryColor = secondaryColor;
        }

        public void setSupportsDarkText(boolean supportsDarkText) {
            mSupportsDarkText = supportsDarkText;
        }

        public void set(GradientColors other) {
            mMainColor = other.mMainColor;
            mSecondaryColor = other.mSecondaryColor;
            mSupportsDarkText = other.mSupportsDarkText;
        }

        public int getMainColor() {
            return mMainColor;
        }

        public int getSecondaryColor() {
            return mSecondaryColor;
        }

        public boolean supportsDarkText() {
            return mSupportsDarkText;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || o.getClass() != getClass()) {
                return false;
            }
            GradientColors other = (GradientColors) o;
            return other.mMainColor == mMainColor &&
                    other.mSecondaryColor == mSecondaryColor &&
                    other.mSupportsDarkText == mSupportsDarkText;
        }

        @Override
        public int hashCode() {
            int code = mMainColor;
            code = 31 * code + mSecondaryColor;
            code = 31 * code + (mSupportsDarkText ? 0 : 1);
            return code;
        }

        @Override
        public String toString() {
            return "GradientColors(" + Integer.toHexString(mMainColor) + ", "
                    + Integer.toHexString(mSecondaryColor) + ")";
        }
    }

    public interface OnColorsChangedListener {
        void onColorsChanged(ColorExtractor colorExtractor, int which);
    }
}