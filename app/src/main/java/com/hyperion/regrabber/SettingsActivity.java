package com.hyperion.regrabber;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import androidx.appcompat.app.ActionBar;
import android.view.MenuItem;
import android.widget.Toast;
import java.lang.reflect.Field;

import com.hyperion.regrabber.common.util.CaptureResolutionInfo;
import com.hyperion.regrabber.common.util.Preferences;

/**
 * A {@link PreferenceActivity} that presents a set of application settings. On
 * handset devices, settings are presented as a single list. On tablets,
 * settings are split by category, with category headers shown to the left of
 * the list of settings.
 * <p>
 * See <a href="http://developer.android.com/design/patterns/settings.html">
 * Android Design: Settings</a> for design guidelines and the <a
 * href="http://developer.android.com/guide/topics/ui/settings.html">Settings
 * API Guide</a> for more information on developing a Settings UI.
 */
public class SettingsActivity extends AppCompatPreferenceActivity {
    public static final String EXTRA_SHOW_TOAST_KEY = "extra_show_toast_key";
    public static final int EXTRA_SHOW_TOAST_SETUP_REQUIRED_FOR_QUICK_TILE = 1;


    /**
     * A preference value change listener that updates the preference's summary
     * to reflect its new value.
     */
    private static Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = (preference, value) -> {
        final int prefResourceID = getResourceId(preference.getKey(), com.hyperion.regrabber.common.R.string.class);

        // verify we have a valid int value for the following preference keys
        if (prefResourceID == com.hyperion.regrabber.common.R.string.pref_key_port ||
            prefResourceID == com.hyperion.regrabber.common.R.string.pref_key_reconnect_delay ||
            prefResourceID == com.hyperion.regrabber.common.R.string.pref_key_priority ||
            prefResourceID == com.hyperion.regrabber.common.R.string.pref_key_x_led ||
            prefResourceID == com.hyperion.regrabber.common.R.string.pref_key_y_led ||
            prefResourceID == com.hyperion.regrabber.common.R.string.pref_key_framerate) {
            try {
                Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                e.printStackTrace();
                return false;
            }
        }

        String stringValue = value.toString();
        if (prefResourceID == com.hyperion.regrabber.common.R.string.pref_key_reconnect_delay) {
            // Show the value with its unit (e.g. "2 seconds"). Leave the descriptive summary in
            // place until a value actually exists so a fresh install doesn't read " seconds".
            if (!stringValue.isEmpty()) {
                preference.setSummary(stringValue + (stringValue.equals("1") ? " second" : " seconds"));
            }
        } else {
            preference.setSummary(stringValue);
        }

        return true;
    };

    /**
     * Returns the resource ID of the provided string
     */
    public static int getResourceId(String resourceName, Class<?> c) {
        try {
            Field idField = c.getDeclaredField(resourceName);
            return idField.getInt(idField);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    /**
     * Binds a preference's summary to its value. More specifically, when the
     * preference's value is changed, its summary (line of text below the
     * preference title) is updated to reflect the value. The summary is also
     * immediately updated upon calling this method. The exact display format is
     * dependent on the type of preference.
     *
     * @see #sBindPreferenceSummaryToValueListener
     */
    private static void bindPreferenceSummaryToValue(Preference preference) {
        if (preference == null) return;
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);

        // Trigger the listener immediately with the preference's
        // current value.
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
                PreferenceManager
                        .getDefaultSharedPreferences(preference.getContext())
                        .getString(preference.getKey(), ""));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getFragmentManager().beginTransaction().replace(android.R.id.content,
                new GeneralPreferenceFragment()).commit();

        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.containsKey(EXTRA_SHOW_TOAST_KEY)){
            if (extras.getInt(EXTRA_SHOW_TOAST_KEY) == EXTRA_SHOW_TOAST_SETUP_REQUIRED_FOR_QUICK_TILE){
                Toast.makeText(getApplicationContext(), R.string.quick_tile_toast_setup_required, Toast.LENGTH_SHORT).show();
            }
        }

        setupActionBar();
    }

    /**
     * Set up the {@link android.app.ActionBar}, if the API is available.
     */
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            // Show the Up button in the action bar.
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    /**
     * This fragment shows general preferences only. It is used when the
     * activity is showing a two-pane settings UI.
     */
    public static class GeneralPreferenceFragment extends PreferenceFragment
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(com.hyperion.regrabber.common.R.xml.pref_general);
            setHasOptionsMenu(true);

            // Bind the summaries of EditText/List/Dialog/Ringtone preferences
            // to their values. When their values change, their summaries are
            // updated to reflect the new value, per the Android Design
            // guidelines.
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.regrabber.common.R.string.pref_key_host)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.regrabber.common.R.string.pref_key_port)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.regrabber.common.R.string.pref_key_priority)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.regrabber.common.R.string.pref_key_framerate)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.regrabber.common.R.string.pref_key_reconnect_delay)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.regrabber.common.R.string.pref_key_x_led)));
            bindPreferenceSummaryToValue(findPreference(getString(com.hyperion.regrabber.common.R.string.pref_key_y_led)));

            updateResolutionInfo();
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
            // Re-read in case the display (e.g. orientation) changed while away.
            updateResolutionInfo();
        }

        @Override
        public void onPause() {
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
            super.onPause();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            // The LED counts and the capture-detail factor all change the streamed resolution.
            if (key == null) return;
            if (key.equals(getString(com.hyperion.regrabber.common.R.string.pref_key_x_led))
                    || key.equals(getString(com.hyperion.regrabber.common.R.string.pref_key_y_led))
                    || key.equals(getString(com.hyperion.regrabber.common.R.string.pref_key_led_multiplier))) {
                updateResolutionInfo();
            }
        }

        /** Refreshes the read-only "Resolution" summary from the current preferences + display size. */
        private void updateResolutionInfo() {
            final Preference info =
                    findPreference(getString(com.hyperion.regrabber.common.R.string.pref_key_resolution_info));
            if (info != null && getActivity() != null) {
                info.setSummary(CaptureResolutionInfo.describe(getActivity(), new Preferences(getActivity())));
            }
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            int id = item.getItemId();
            if (id == android.R.id.home) {
                startActivity(new Intent(getActivity(), SettingsActivity.class));
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
    }
}
