package com.copa.echo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.copa.echo.android.StringFormat;
import com.copa.echo.android.TimeFormat;

import java.io.File;
import java.util.Locale;

/**
 * Every setting Echo has, laid out on the main screen underneath what it is doing right now.
 *
 * They are grouped into folding sections rather than given a screen of their own, and each
 * section header carries a one-line summary of how that group currently stands: on or off, at
 * what interval, listening for which word. The whole configuration can therefore be read at a
 * glance while only the group being changed is unfolded.
 *
 * This is the settings half of {@link SaidItFragment}, which keeps the live half — what is in
 * memory, whether capture is healthy — and forwards the two answers that arrive as results
 * (a permission, and consent to capture the screen).
 */
final class SettingsPanel {

    static final int LOCATION_PERMISSION_REQUEST_CODE = 5466;
    static final int CAMERA_PERMISSION_REQUEST_CODE = 5467;
    static final int PROJECTION_REQUEST_CODE = 5468;

    /** Selectable automatic save intervals, in minutes, paired with the buttons below. */
    private static final int[] INTERVAL_MINUTES = { 1, 5, 15, 30, 60 };
    private static final int[] INTERVAL_BUTTONS = {
            R.id.interval_1, R.id.interval_5, R.id.interval_15, R.id.interval_30, R.id.interval_60 };

    /** How hard the shake has to be, in the order the capturer's own levels are numbered. */
    private static final int[] SHAKE_BUTTONS = {
            R.id.shake_gentle, R.id.shake_normal, R.id.shake_hard };
    private static final int[] SHAKE_LABELS = {
            R.string.shake_level_gentle, R.string.shake_level_normal, R.string.shake_level_hard };

    /** header, body, title of each folding section, in the order they appear. */
    private static final int[][] SECTION_IDS = {
            { R.id.section_memory_header, R.id.section_memory_body, R.id.section_memory_title,
                    R.id.section_memory_state },
            { R.id.section_saving_header, R.id.section_saving_body, R.id.section_saving_title,
                    R.id.section_saving_state },
            { R.id.section_gps_header, R.id.section_gps_body, R.id.section_gps_title,
                    R.id.section_gps_state },
            { R.id.section_camera_header, R.id.section_camera_body, R.id.section_camera_title,
                    R.id.section_camera_state },
            { R.id.section_screenshot_header, R.id.section_screenshot_body,
                    R.id.section_screenshot_title, R.id.section_screenshot_state },
            { R.id.section_keyword_header, R.id.section_keyword_body, R.id.section_keyword_title,
                    R.id.section_keyword_state },
            { R.id.section_upload_header, R.id.section_upload_body, R.id.section_upload_title,
                    R.id.section_upload_state },
    };

    private static final String FOLDED = "▸";
    private static final String UNFOLDED = "▾";
    private static final String SEPARATOR = " · ";

    private final SaidItFragment fragment;
    private final ViewGroup root;
    private final Section[] sections;
    private final TimeFormat.Result timeFormatResult = new TimeFormat.Result();
    private final WorkingDialog dialog = new WorkingDialog();

    /** One folding group: its header, the body it hides, and the summary in between. */
    private final class Section {
        final View body;
        final TextView title;
        final TextView state;
        /** The header's own words, kept because the title also carries the fold marker. */
        final String label;

        Section(int[] ids) {
            final View header = root.findViewById(ids[0]);
            body = root.findViewById(ids[1]);
            title = (TextView) root.findViewById(ids[2]);
            state = (TextView) root.findViewById(ids[3]);
            label = title.getText().toString();
            title.setTypeface(com.copa.echo.android.Fonts.bold(title.getContext()));
            drawTitle();
            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    body.setVisibility(body.getVisibility() == View.VISIBLE
                            ? View.GONE : View.VISIBLE);
                    drawTitle();
                }
            });
        }

        void drawTitle() {
            title.setText((body.getVisibility() == View.VISIBLE ? UNFOLDED : FOLDED) + "  " + label);
        }
    }

    SettingsPanel(SaidItFragment fragment, ViewGroup root) {
        this.fragment = fragment;
        this.root = root;

        sections = new Section[SECTION_IDS.length];
        for (int i = 0; i < SECTION_IDS.length; ++i) sections[i] = new Section(SECTION_IDS[i]);

        dialog.setDescriptionStringId(R.string.work_preparing_memory);

        for (int id : new int[]{ R.id.memory_low, R.id.memory_medium, R.id.memory_high }) {
            root.findViewById(id).setOnClickListener(memoryClickListener);
        }
        initSampleRateButton(R.id.quality_8kHz, 8000, 11025);
        initSampleRateButton(R.id.quality_16kHz, 16000, 22050);
        initSampleRateButton(R.id.quality_48kHz, 48000, 44100);

        root.findViewById(R.id.auto_save_toggle).setOnClickListener(autoSaveToggleClickListener);
        for (int id : INTERVAL_BUTTONS) {
            root.findViewById(id).setOnClickListener(autoSaveIntervalClickListener);
        }
        root.findViewById(R.id.gps_toggle).setOnClickListener(gpsToggleClickListener);
        root.findViewById(R.id.camera_toggle).setOnClickListener(cameraToggleClickListener);
        root.findViewById(R.id.front_camera_toggle).setOnClickListener(frontCameraToggleClickListener);
        for (int id : SHAKE_BUTTONS) {
            root.findViewById(id).setOnClickListener(shakeLevelClickListener);
        }
        root.findViewById(R.id.screenshot_toggle).setOnClickListener(screenshotToggleClickListener);
        root.findViewById(R.id.keyword_toggle).setOnClickListener(keywordToggleClickListener);
        root.findViewById(R.id.upload_toggle).setOnClickListener(uploadToggleClickListener);
    }

    private SaidItService echo() {
        return fragment.service();
    }

    private Activity activity() {
        return fragment.getActivity();
    }

    private Resources resources() {
        final Activity activity = activity();
        return activity == null ? null : activity.getResources();
    }

    private String string(int id) {
        final Activity activity = activity();
        return activity == null ? "" : activity.getString(id);
    }

    private String string(int id, Object... args) {
        final Activity activity = activity();
        return activity == null ? "" : activity.getString(id, args);
    }

    // ------------------------------------------------------------------ drawing

    /** Redraws every widget from the service. On binding, and after anything is changed. */
    void sync() {
        final SaidItService echo = echo();
        if (echo == null || resources() == null) return;

        final long maxMemory = Runtime.getRuntime().maxMemory();
        ((Button) root.findViewById(R.id.memory_low)).setText(
                StringFormat.shortFileSize(maxMemory / 4));
        ((Button) root.findViewById(R.id.memory_medium)).setText(
                StringFormat.shortFileSize(maxMemory / 2));
        ((Button) root.findViewById(R.id.memory_high)).setText(
                StringFormat.shortFileSize((long) (maxMemory * 0.90)));

        TimeFormat.naturalLanguage(resources(),
                echo.getBytesToSeconds() * echo.getMemorySize(), timeFormatResult);
        ((TextView) root.findViewById(R.id.history_limit)).setText(timeFormatResult.text);

        highlightButtons(echo);
        syncAutoSave(echo);
        syncQualityButtons(echo);
        syncGps(echo);
        syncCamera(echo);
        syncScreenshot(echo);
        syncKeyword(echo);
        syncUpload(echo);
        syncSummaries(echo);
    }

    /**
     * The two notes that go stale on their own: whether the GPS provider is switched on, which
     * the user can change from outside Echo, and how far the speech model has got with loading.
     * Cheap enough to redraw on the tick, and touches no field being typed into.
     */
    void refreshNotes() {
        final SaidItService echo = echo();
        if (echo == null || resources() == null) return;
        syncGpsNote(echo);
        syncKeywordNote(echo);
        syncSummaries(echo);
    }

    /**
     * The summaries are redrawn on the tick along with the notes, so they say the truth even when
     * what changed them was not this screen — a revoked permission, GPS switched off, a model that
     * has finished loading. Only a summary that actually reads differently is written back: a
     * TextView lays itself out again on every setText, however identical the words.
     */
    private void syncSummaries(SaidItService echo) {
        setIfChanged(sections[0].state, memorySummary(echo));
        setIfChanged(sections[1].state, savingSummary(echo));
        setIfChanged(sections[2].state, gpsSummary(echo));
        setIfChanged(sections[3].state, cameraSummary(echo));
        setIfChanged(sections[4].state, screenshotSummary(echo));
        setIfChanged(sections[5].state, keywordSummary(echo));
        setIfChanged(sections[6].state, uploadSummary(echo));
    }

    private static void setIfChanged(TextView view, String text) {
        if (!text.equals(view.getText().toString())) view.setText(text);
    }

    private String memorySummary(SaidItService echo) {
        TimeFormat.naturalLanguage(resources(),
                echo.getBytesToSeconds() * echo.getMemorySize(), timeFormatResult);
        final String rate = String.format(Locale.getDefault(), "%d kHz", echo.getSamplingRate() / 1000);
        final String summary = timeFormatResult.text + SEPARATOR + rate;
        return echo.isLowPowerEnabled()
                ? summary + SEPARATOR + string(R.string.state_low_power) : summary;
    }

    private String savingSummary(SaidItService echo) {
        if (!echo.isAutoSaveEnabled()) return string(R.string.state_off);
        final int minutes = echo.getAutoSaveIntervalMinutes();
        return resources().getQuantityString(R.plurals.interval_minutes, minutes, minutes);
    }

    private String gpsSummary(SaidItService echo) {
        if (!echo.isGpsEnabled()) return string(R.string.state_off);
        if (!echo.hasLocationPermission()) {
            return string(R.string.state_on) + SEPARATOR + string(R.string.state_needs_permission);
        }
        if (!echo.isGpsProviderEnabled()) {
            return string(R.string.state_on) + SEPARATOR + string(R.string.state_provider_off);
        }
        return string(R.string.state_on);
    }

    private String cameraSummary(SaidItService echo) {
        if (!echo.isCameraEnabled()) return string(R.string.state_off);
        if (!echo.hasCameraPermission()) {
            return string(R.string.state_on) + SEPARATOR + string(R.string.state_needs_permission);
        }
        return string(SHAKE_LABELS[Math.max(0, Math.min(2, echo.getShakeLevel()))])
                + SEPARATOR + string(R.string.state_seconds, echo.getClipSeconds())
                + SEPARATOR + string(echo.isFrontCameraEnabled()
                        ? R.string.state_both_cameras : R.string.state_back_camera);
    }

    private String screenshotSummary(SaidItService echo) {
        if (!echo.isScreenshotEnabled()) return string(R.string.state_off);
        return string(R.string.state_on)
                + SEPARATOR + string(R.string.state_every_seconds, echo.getScreenshotMinSeconds());
    }

    private String keywordSummary(SaidItService echo) {
        if (!echo.hasKeywordModel()) return string(R.string.state_no_model);
        if (!echo.isKeywordEnabled()) return string(R.string.state_off);
        final String words = echo.getKeywordWords();
        if (echo.getKeywordStatus() == KeywordDetector.Status.LOADING) {
            return words + SEPARATOR + string(R.string.state_loading);
        }
        return words;
    }

    private String uploadSummary(SaidItService echo) {
        return string(echo.isUploadEnabled() ? R.string.state_on : R.string.state_off);
    }

    private void highlightButtons(SaidItService echo) {
        final long maxMemory = Runtime.getRuntime().maxMemory();
        int button = Math.round(echo.getMemorySizePreference() / (float) (maxMemory / 4));
        highlightOneOf(R.id.memory_low, R.id.memory_medium, R.id.memory_high, button);

        final int samplingRate = echo.getSamplingRate();
        if (samplingRate >= 44100) button = 3;
        else if (samplingRate >= 16000) button = 2;
        else button = 1;
        highlightOneOf(R.id.quality_8kHz, R.id.quality_16kHz, R.id.quality_48kHz, button);
    }

    private void highlightOneOf(int first, int second, int third, int chosen) {
        root.findViewById(first).setBackgroundResource(
                1 == chosen ? R.drawable.green_button : R.drawable.gray_button);
        root.findViewById(second).setBackgroundResource(
                2 == chosen ? R.drawable.green_button : R.drawable.gray_button);
        root.findViewById(third).setBackgroundResource(
                3 == chosen ? R.drawable.green_button : R.drawable.gray_button);
    }

    /** Low power mode drives the sample rate, so the quality buttons would only lie. */
    private void syncQualityButtons(SaidItService echo) {
        final boolean lowPower = echo.isLowPowerEnabled();
        for (int id : new int[]{ R.id.quality_8kHz, R.id.quality_16kHz, R.id.quality_48kHz }) {
            final View button = root.findViewById(id);
            button.setEnabled(!lowPower);
            button.setAlpha(lowPower ? 0.4f : 1f);
        }
    }

    private void syncAutoSave(SaidItService echo) {
        final boolean enabled = echo.isAutoSaveEnabled();
        final Button toggle = (Button) root.findViewById(R.id.auto_save_toggle);
        toggle.setText(enabled ? R.string.auto_save_enabled : R.string.auto_save_disabled);
        toggle.setBackgroundResource(enabled ? R.drawable.green_button : R.drawable.gray_button);

        final int current = echo.getAutoSaveIntervalMinutes();
        for (int i = 0; i < INTERVAL_BUTTONS.length; ++i) {
            final Button button = (Button) root.findViewById(INTERVAL_BUTTONS[i]);
            button.setText(resources().getQuantityString(R.plurals.interval_minutes,
                    INTERVAL_MINUTES[i], INTERVAL_MINUTES[i]));
            final boolean selected = enabled && INTERVAL_MINUTES[i] == current;
            button.setBackgroundResource(selected ? R.drawable.green_button : R.drawable.gray_button);
            button.setEnabled(enabled);
        }

        // The cached directory, never getTracesDir(): resolving it creates and deletes a probe
        // file, and this runs on the main thread.
        final File dir = echo.getResolvedDir();
        ((TextView) root.findViewById(R.id.storage_path)).setText(
                dir == null ? string(R.string.diagnostics_checking) : dir.getAbsolutePath());
    }

    private void syncGps(SaidItService echo) {
        final boolean enabled = echo.isGpsEnabled();
        final Button toggle = (Button) root.findViewById(R.id.gps_toggle);
        toggle.setText(enabled ? R.string.gps_on : R.string.gps_off);
        toggle.setBackgroundResource(enabled ? R.drawable.green_button : R.drawable.gray_button);
        syncGpsNote(echo);
    }

    /** Logging can be on and still produce nothing, so say which of the two is in the way. */
    private void syncGpsNote(SaidItService echo) {
        final TextView note = (TextView) root.findViewById(R.id.gps_note);
        String message = null;
        if (echo.isGpsEnabled() && !echo.hasLocationPermission()) {
            message = string(R.string.gps_permission_needed);
        } else if (echo.isGpsEnabled() && !echo.isGpsProviderEnabled()) {
            message = string(R.string.gps_provider_off);
        }
        showNote(note, message);
    }

    private void syncCamera(SaidItService echo) {
        final boolean enabled = echo.isCameraEnabled();
        final Button toggle = (Button) root.findViewById(R.id.camera_toggle);
        toggle.setText(enabled ? R.string.camera_on : R.string.camera_off);
        toggle.setBackgroundResource(enabled ? R.drawable.green_button : R.drawable.gray_button);

        final int current = echo.getShakeLevel();
        for (int i = 0; i < SHAKE_BUTTONS.length; ++i) {
            final Button button = (Button) root.findViewById(SHAKE_BUTTONS[i]);
            button.setText(SHAKE_LABELS[i]);
            final boolean selected = enabled && i == current;
            button.setBackgroundResource(selected ? R.drawable.green_button : R.drawable.gray_button);
            button.setEnabled(enabled);
        }

        showNote((TextView) root.findViewById(R.id.camera_note),
                enabled && !echo.hasCameraPermission()
                        ? string(R.string.camera_permission_needed) : null);

        final boolean front = echo.isFrontCameraEnabled();
        final Button frontToggle = (Button) root.findViewById(R.id.front_camera_toggle);
        frontToggle.setText(front ? R.string.front_camera_on : R.string.front_camera_off);
        frontToggle.setBackgroundResource(front ? R.drawable.green_button : R.drawable.gray_button);
        frontToggle.setEnabled(enabled);

        // The front camera's own minimum means nothing while the front camera is left out.
        root.findViewById(R.id.camera_min_front).setEnabled(enabled && front);

        setNumberField(R.id.clip_seconds, echo.getClipSeconds());
        setNumberField(R.id.camera_min_back, echo.getCameraMinBackSeconds());
        setNumberField(R.id.camera_min_front, echo.getCameraMinFrontSeconds());
    }

    private void syncScreenshot(SaidItService echo) {
        final boolean on = echo.isScreenshotEnabled();
        final Button toggle = (Button) root.findViewById(R.id.screenshot_toggle);
        toggle.setText(on ? R.string.screenshot_on : R.string.screenshot_off);
        toggle.setBackgroundResource(on ? R.drawable.green_button : R.drawable.gray_button);
        setNumberField(R.id.screenshot_min, echo.getScreenshotMinSeconds());
    }

    private void syncKeyword(SaidItService echo) {
        final boolean on = echo.isKeywordEnabled();
        final Button toggle = (Button) root.findViewById(R.id.keyword_toggle);
        toggle.setText(on ? R.string.keyword_on : R.string.keyword_off);
        toggle.setBackgroundResource(on ? R.drawable.green_button : R.drawable.gray_button);

        final EditText words = (EditText) root.findViewById(R.id.keyword_words);
        if (!words.getText().toString().equals(echo.getKeywordWords())) {
            words.setText(echo.getKeywordWords());
        }
        syncKeywordNote(echo);
    }

    /**
     * Keyword detection reports one of four things: off, still loading the model, listening, or
     * unable to listen at all. Only the ones that need doing something about get a note.
     */
    private void syncKeywordNote(SaidItService echo) {
        String message = null;
        if (!echo.hasKeywordModel()) {
            message = string(R.string.keyword_no_model);
        } else if (echo.isKeywordEnabled()) {
            switch (echo.getKeywordStatus()) {
                case LOADING: message = string(R.string.keyword_loading); break;
                case ERROR: message = string(R.string.keyword_error); break;
                case OFF: message = string(R.string.keyword_needs_listening); break;
                default: break;
            }
        }
        showNote((TextView) root.findViewById(R.id.keyword_note), message);
    }

    private void syncUpload(SaidItService echo) {
        final boolean on = echo.isUploadEnabled();
        final Button toggle = (Button) root.findViewById(R.id.upload_toggle);
        toggle.setText(on ? R.string.upload_on : R.string.upload_off);
        toggle.setBackgroundResource(on ? R.drawable.green_button : R.drawable.gray_button);

        final EditText url = (EditText) root.findViewById(R.id.upload_url);
        // Only overwrite what the user is typing when it is genuinely different, so the cursor
        // does not jump while a sync runs.
        if (!url.getText().toString().equals(echo.getUploadUrl())) {
            url.setText(echo.getUploadUrl());
        }
    }

    private static void showNote(TextView note, String message) {
        if (message == null) {
            note.setVisibility(View.GONE);
            return;
        }
        note.setText(message);
        note.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ typed-in fields

    /** Fills a number field, leaving it alone while its value already matches what is typed. */
    private void setNumberField(int id, int value) {
        final EditText field = (EditText) root.findViewById(id);
        if (!field.getText().toString().equals(Integer.toString(value))) {
            field.setText(Integer.toString(value));
        }
    }

    private int readNumberField(int id, int fallback) {
        final EditText field = (EditText) root.findViewById(id);
        if (field == null) return fallback;
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Persists everything that is typed rather than tapped, so it survives leaving the screen. */
    void save() {
        final SaidItService echo = echo();
        if (echo == null) return;
        echo.setCameraMinIntervals(
                readNumberField(R.id.camera_min_back, echo.getCameraMinBackSeconds()),
                readNumberField(R.id.camera_min_front, echo.getCameraMinFrontSeconds()));
        echo.setClipSeconds(readNumberField(R.id.clip_seconds, echo.getClipSeconds()));
        echo.setScreenshotMinSeconds(
                readNumberField(R.id.screenshot_min, echo.getScreenshotMinSeconds()));
        saveKeywordWords(echo);
        saveUploadUrl(echo);
    }

    private void saveKeywordWords(SaidItService echo) {
        final EditText words = (EditText) root.findViewById(R.id.keyword_words);
        if (words != null) echo.setKeywordWords(words.getText().toString());
    }

    private void saveUploadUrl(SaidItService echo) {
        final EditText url = (EditText) root.findViewById(R.id.upload_url);
        if (url != null) echo.setUploadUrl(url.getText().toString());
    }

    // ------------------------------------------------------------------ answers from the system

    void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        final SaidItService echo = echo();
        if (echo == null) return;
        final boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            // Turning logging on without the permission would only write empty tracks, so the
            // answer decides whether it goes on at all. The note explains a refusal.
            if (granted) echo.setGpsEnabled(true);
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (granted) echo.setCameraEnabled(true);
        } else {
            return;
        }
        sync();
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PROJECTION_REQUEST_CODE) return;
        final SaidItService echo = echo();
        final Activity activity = activity();
        if (echo == null || activity == null) return;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (!echo.startScreenCapture(resultCode, data)) {
                Toast.makeText(activity, R.string.screenshot_denied, Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(activity, R.string.screenshot_denied, Toast.LENGTH_LONG).show();
        }
        sync();
    }

    // ------------------------------------------------------------------ what the buttons do

    private void initSampleRateButton(int buttonId, int primarySampleRate, int secondarySampleRate) {
        final Button button = (Button) root.findViewById(buttonId);
        button.setOnClickListener(qualityClickListener);
        if (sampleRateWorks(primarySampleRate)) {
            button.setText(String.format(Locale.getDefault(), "%d kHz", primarySampleRate / 1000));
            button.setTag(primarySampleRate);
        } else if (sampleRateWorks(secondarySampleRate)) {
            button.setText(String.format(Locale.getDefault(), "%d kHz", secondarySampleRate / 1000));
            button.setTag(secondarySampleRate);
        } else {
            button.setVisibility(View.GONE);
        }
    }

    private static boolean sampleRateWorks(int sampleRate) {
        final int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return bufferSize > 0;
    }

    /**
     * Reallocating audio memory takes long enough to be worth a dialog, and it changes what the
     * live half of the screen says, so the fragment redraws with us when it is done.
     */
    private void reallocate(final Runnable change) {
        final Activity activity = activity();
        if (activity == null) return;
        dialog.show(fragment.getFragmentManager(), "Preparing memory");
        new Handler().post(new Runnable() {
            @Override
            public void run() {
                change.run();
                final SaidItService echo = echo();
                if (echo == null) return;
                echo.getState(new SaidItService.StateCallback() {
                    @Override
                    public void state(SaidItService.State state) {
                        sync();
                        fragment.redraw(state);
                        if (dialog.isVisible()) dialog.dismiss();
                    }
                });
            }
        });
    }

    private final View.OnClickListener memoryClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            final int id = v.getId();
            final int multiplier = (id == R.id.memory_high) ? 3
                    : (id == R.id.memory_medium) ? 2 : (id == R.id.memory_low) ? 1 : 0;
            final long memory = multiplier * Runtime.getRuntime().maxMemory() / 4;
            reallocate(new Runnable() {
                @Override
                public void run() {
                    echo.setMemorySize(memory);
                }
            });
        }
    };

    private final View.OnClickListener qualityClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            final Object tag = v.getTag();
            final int sampleRate = (tag instanceof Integer) ? (Integer) tag : 8000;
            reallocate(new Runnable() {
                @Override
                public void run() {
                    echo.setSampleRate(sampleRate);
                }
            });
        }
    };

    private final View.OnClickListener autoSaveToggleClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            echo.setAutoSaveEnabled(!echo.isAutoSaveEnabled());
            sync();
        }
    };

    private final View.OnClickListener autoSaveIntervalClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            for (int i = 0; i < INTERVAL_BUTTONS.length; ++i) {
                if (INTERVAL_BUTTONS[i] == v.getId()) {
                    echo.setAutoSaveIntervalMinutes(INTERVAL_MINUTES[i]);
                    break;
                }
            }
            sync();
        }
    };

    /**
     * Location is one of the two permissions Echo asks for out here rather than at startup: it is
     * only needed by a feature that is off by default, so nobody who does not want it is asked.
     */
    private final View.OnClickListener gpsToggleClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            if (echo.isGpsEnabled()) {
                echo.setGpsEnabled(false);
                sync();
                return;
            }
            if (!echo.hasLocationPermission()) {
                fragment.requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION },
                        LOCATION_PERMISSION_REQUEST_CODE);
                return;
            }
            echo.setGpsEnabled(true);
            sync();
        }
    };

    /** The camera, like location, is asked for only when the feature is switched on. */
    private final View.OnClickListener cameraToggleClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            save();
            if (echo.isCameraEnabled()) {
                echo.setCameraEnabled(false);
                sync();
                return;
            }
            if (!echo.hasCameraPermission()) {
                fragment.requestPermissions(new String[]{ Manifest.permission.CAMERA },
                        CAMERA_PERMISSION_REQUEST_CODE);
                return;
            }
            echo.setCameraEnabled(true);
            sync();
        }
    };

    /** The front camera can be left out of a trigger without giving up shake capture itself. */
    private final View.OnClickListener frontCameraToggleClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            echo.setFrontCameraEnabled(!echo.isFrontCameraEnabled());
            sync();
        }
    };

    private final View.OnClickListener shakeLevelClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            for (int i = 0; i < SHAKE_BUTTONS.length; ++i) {
                if (SHAKE_BUTTONS[i] == v.getId()) {
                    echo.setShakeLevel(i);
                    break;
                }
            }
            sync();
        }
    };

    /**
     * Screenshots need a MediaProjection consent, which is an activity result. Turning them on
     * launches the system dialog; the answer comes back to {@link #onActivityResult}.
     */
    private final View.OnClickListener screenshotToggleClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            final Activity activity = activity();
            if (echo == null || activity == null) return;
            save();
            if (echo.isScreenshotEnabled()) {
                echo.stopScreenCapture();
                sync();
                return;
            }
            if (!echo.isListeningForScreenshots()) {
                Toast.makeText(activity, R.string.screenshot_needs_listening,
                        Toast.LENGTH_LONG).show();
                return;
            }
            final MediaProjectionManager manager =
                    (MediaProjectionManager) activity.getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE);
            if (manager != null) {
                fragment.startActivityForResult(manager.createScreenCaptureIntent(),
                        PROJECTION_REQUEST_CODE);
            }
        }
    };

    /**
     * Keyword detection listens to the audio already being captured, so it needs no permission of
     * its own; what it does need is the words to listen for, saved before it starts.
     */
    private final View.OnClickListener keywordToggleClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            saveKeywordWords(echo);
            echo.setKeywordEnabled(!echo.isKeywordEnabled());
            sync();
        }
    };

    private final View.OnClickListener uploadToggleClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final SaidItService echo = echo();
            if (echo == null) return;
            saveUploadUrl(echo);
            echo.setUploadEnabled(!echo.isUploadEnabled());
            sync();
        }
    };
}
