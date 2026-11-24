/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GlyphSettingsFragment extends SettingsBasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.glyph_settings, rootKey);

        GlyphEngine engine = GlyphEngine.get(requireContext());

        ListPreference level = findPreference(GlyphSettings.KEY_LEVEL_DIRECTION);
        ListPreference progress = findPreference(GlyphSettings.KEY_PROGRESS_DIRECTION);
        SeekBarPreference brightness = findPreference(GlyphSettings.KEY_BRIGHTNESS);

        // Play the chosen fill once so the user sees what it means. Prefs are saved after this
        // callback returns, so delay the preview until the new value is in place.
        level.setOnPreferenceChangeListener((p, v) -> {
            getListView().post(() -> engine.preview(true));
            return true;
        });
        progress.setOnPreferenceChangeListener((p, v) -> {
            getListView().post(() -> engine.preview(false));
            return true;
        });
        brightness.setOnPreferenceChangeListener((p, v) -> {
            getListView().post(() -> engine.preview(true));
            return true;
        });

        Preference test = findPreference("glyph_test");
        test.setOnPreferenceClickListener(p -> {
            engine.preview(false);
            return true;
        });

        populateApps(findPreference(GlyphSettings.KEY_PROGRESS_EXCLUDED));
    }

    /** Lists launchable apps, sorted by name, for the progress-bar exclusion list. */
    private void populateApps(MultiSelectListPreference pref) {
        PackageManager pm = requireContext().getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
        Map<String, String> apps = new LinkedHashMap<>();
        for (ResolveInfo ri : infos) {
            String pkg = ri.activityInfo.packageName;
            if (!apps.containsKey(pkg)) {
                apps.put(pkg, ri.activityInfo.applicationInfo.loadLabel(pm).toString());
            }
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(apps.entrySet());
        Collator collator = Collator.getInstance();
        sorted.sort((a, b) -> collator.compare(a.getValue(), b.getValue()));

        CharSequence[] labels = new CharSequence[sorted.size()];
        CharSequence[] packages = new CharSequence[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            labels[i] = sorted.get(i).getValue();
            packages[i] = sorted.get(i).getKey();
        }
        pref.setEntries(labels);
        pref.setEntryValues(packages);
    }
}
