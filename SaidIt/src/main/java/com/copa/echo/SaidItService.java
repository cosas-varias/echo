package com.copa.echo;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import simplesound.pcm.WavAudioFormat;
import simplesound.pcm.WavFileWriter;
import static com.copa.echo.SaidIt.*;

public class SaidItService extends Service {
    static final String TAG = SaidItService.class.getSimpleName();
    private static final int FOREGROUND_NOTIFICATION_ID = 458;
    private static final String NOTIFICATION_CHANNEL_ID = "SaidItServiceChannel";

    /** Pass as memorySeconds to save the whole audio memory. */
    public static final float ALL_MEMORY = -1f;

    /** Audio memory is dumped early once it is this full, so nothing gets overwritten unsaved. */
    private static final float MEMORY_FULL_FRACTION = 0.9f;

    /** How many of the most recent events the diagnostics screen keeps. */
    private static final int EVENT_LOG_SIZE = 40;

    /**
     * Longest the capture loop may sleep between reads. AudioRecord's buffer is sized in bytes,
     * so at 8 kHz it holds two minutes: sleeping that long would leave the automatic save with an
     * empty memory and no slack at all before the buffer overruns.
     */
    private static final long MAX_READ_INTERVAL_MILLIS = 30000;

    /**
     * Longest the capture loop may sleep while a keyword is being listened for. Audio only reaches
     * the detector when it is read, so the wait between reads is also how late an alert can be: at
     * the normal interval a word could be answered most of a buffer later, which is no alarm at
     * all.
     *
     * Reading this often is not what a listening Echo spends its battery on. Capture holds the
     * audio path awake either way, an ordinary recorder reads its microphone every few tens of
     * milliseconds, and each read here is one memcpy. What costs is recognising the audio, which
     * is the same work however it is sliced, and which {@link KeywordDetector} skips outright when
     * a stretch is near silent.
     */
    private static final long KEYWORD_READ_INTERVAL_MILLIS = 2000;

    /** How often the microphone is checked for being closed, stalled or handed back. */
    private static final long CAPTURE_WATCHDOG_MILLIS = 15000;
    private static final long CAPTURE_WATCHDOG_MILLIS_LOW_POWER = 30000;
    /** Longest wait between attempts to open a microphone another app is holding. */
    private static final long MAX_MIC_RETRY_MILLIS = 15000;
    /** Consecutive failed reads before the AudioRecord is thrown away and opened again. */
    private static final int READ_ERRORS_BEFORE_RESTART = 5;

    volatile int SAMPLE_RATE;
    volatile int FILL_RATE;

    AudioRecord audioRecord; // used only in the audio thread
    final AudioMemory audioMemory = new AudioMemory(); // used only in the audio thread
    /**
     * Session id of the AudioRecord above, or 0 when there is none. The recording callback runs
     * on the main thread and this is the only way for it to recognise our own capture.
     */
    private volatile int audioSessionId = 0;

    HandlerThread audioThread;
    Handler audioHandler; // used to post messages to audio thread
    Handler mainHandler; // used to post messages back to the UI thread

    /** elapsedRealtime the next automatic save is due at, or -1 when automatic saving is off. */
    private volatile long autoSaveDeadline = -1;
    /** Wall clock time of the last save, or 0 if nothing was saved yet. */
    private volatile long lastSaveMillis = 0;
    private volatile String lastSaveName = null;
    private volatile int autoSaveCount = 0;
    private volatile int memoryFullSaveCount = 0;

    /** elapsedRealtime capture was (re)started at, to tell a slow start from a dead microphone. */
    private volatile long captureStartedElapsed = 0;
    /** elapsedRealtime of the last read that actually returned audio, or 0 if there was none. */
    private volatile long lastReadElapsed = 0;
    private volatile long bytesCaptured = 0;
    private volatile int readErrorCount = 0;
    /** How long a gap between reads is still normal, derived from the AudioRecord buffer. */
    private volatile long readGapToleranceMillis = 30000;

    private volatile String lastError = null;
    private volatile long lastErrorMillis = 0;

    /** True while the microphone cannot be opened at all, so reopening is being retried. */
    private volatile boolean micBlocked = false;
    /** Failed attempts to open the microphone since the last successful one; drives the backoff. */
    private volatile int micRetries = 0;
    /** True while Android is feeding us silence because another app has priority on the input. */
    private volatile boolean micSilenced = false;
    /** How many times the microphone has been taken away during this capture. */
    private volatile int micTakeoverCount = 0;
    /** Failed reads in a row, reset by any read that returns audio. Audio thread only. */
    private int consecutiveReadErrors = 0;

    /** Buffered location fixes, written as a GPX track beside the audio they belong to. */
    final GpxTrack track = new GpxTrack();
    private LocationManager locationManager;
    private AudioManager audioManager;
    private volatile boolean gpsEnabled = false;
    /** True while location updates are actually registered, so they are never asked for twice. */
    private volatile boolean gpsRequested = false;
    /** The update interval currently registered with the system, or 0 when none is. */
    private volatile long gpsIntervalMillis = 0;
    private volatile long lastFixMillis = 0;
    private volatile float lastFixAccuracy = -1;
    private volatile int fixCount = 0;
    private volatile int trackSaveCount = 0;
    private volatile String lastTrackName = null;

    /** Resolved off the main thread because it probes the filesystem. */
    private volatile File storageDir = null;

    // Cached preferences. Written only by the setters below, read from both threads.
    private volatile boolean listeningWanted = true;
    private volatile boolean autoSaveEnabled = true;
    private volatile int autoSaveIntervalMinutes = AUTO_SAVE_INTERVAL_DEFAULT;
    private volatile boolean lowPower = false;
    private volatile long memorySizePref = 0;
    private volatile boolean cameraEnabled = false;
    private volatile int shakeLevel = SHAKE_LEVEL_DEFAULT;
    private volatile boolean frontCameraEnabled = true;
    private volatile int clipSeconds = CLIP_SECONDS_DEFAULT;
    private volatile int cameraMinBackSeconds = CAMERA_MIN_BACK_DEFAULT;
    private volatile int cameraMinFrontSeconds = CAMERA_MIN_FRONT_DEFAULT;
    private volatile int screenshotMinSeconds = SCREENSHOT_MIN_DEFAULT;
    private volatile boolean keywordEnabled = false;
    private volatile String keywordWords = KEYWORD_WORDS_DEFAULT;
    private volatile boolean uploadEnabled = false;
    private volatile String uploadUrl = "";
    /**
     * Screenshots are never remembered across a restart: the MediaProjection token cannot outlive
     * the process, so this is in-memory only and starts off every launch.
     */
    private volatile boolean screenshotEnabled = false;

    /** Optional shake-triggered camera capture, GPS-style: off unless the user turns it on. */
    private ShakeCameraCapturer cameraCapturer;
    /** Optional MediaProjection screenshots, alive only while the user keeps them on. */
    private ScreenCapturer screenCapturer;
    /** Sends saved traces to a server and deletes them once accepted. */
    private TraceUploader uploader;
    /** Optional spoken keyword detection, fed from the audio capture below. */
    private KeywordDetector keywordDetector;
    /** The alert a heard keyword sounds. Built on first use, main thread only. */
    private android.media.Ringtone alertRingtone;

    /**
     * Both visual capturers want the same two things: where to write, and a nudge to the uploader
     * once a file lands. tracesDir() probes the disk, so it is only ever called from their own
     * background threads.
     */
    private final CaptureListener captureListener = new CaptureListener();

    private final class CaptureListener implements ShakeCameraCapturer.Listener, ScreenCapturer.Listener {
        @Override
        public File tracesDir() {
            return getTracesDir();
        }

        @Override
        public void onCaptured(File file) {
            if(uploader != null) uploader.kick();
        }
    }

    /** Guards against a save being started from inside another one. Audio thread only. */
    private boolean writing = false;
    /** Set while a save asked for from the UI is queued, so the timer does not steal its audio. */
    private volatile boolean manualSavePending = false;

    private final LinkedList<String> events = new LinkedList<String>();

    volatile int state;

    static final int STATE_READY = 0;
    static final int STATE_LISTENING = 1;

    @Override
    public void onCreate() {

        Log.d(TAG, "Reading native sample rate");

        mainHandler = new Handler(Looper.getMainLooper());

        final SharedPreferences preferences = prefs();
        listeningWanted = preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true);
        autoSaveEnabled = preferences.getBoolean(AUTO_SAVE_ENABLED_KEY, true);
        autoSaveIntervalMinutes = preferences.getInt(AUTO_SAVE_INTERVAL_KEY, AUTO_SAVE_INTERVAL_DEFAULT);
        lowPower = preferences.getBoolean(LOW_POWER_KEY, false);
        gpsEnabled = preferences.getBoolean(GPS_ENABLED_KEY, false);
        cameraEnabled = preferences.getBoolean(CAMERA_ENABLED_KEY, false);
        shakeLevel = preferences.getInt(SHAKE_LEVEL_KEY, SHAKE_LEVEL_DEFAULT);
        frontCameraEnabled = preferences.getBoolean(CAMERA_FRONT_ENABLED_KEY, true);
        clipSeconds = preferences.getInt(CLIP_SECONDS_KEY, CLIP_SECONDS_DEFAULT);
        cameraMinBackSeconds = preferences.getInt(CAMERA_MIN_BACK_KEY, CAMERA_MIN_BACK_DEFAULT);
        cameraMinFrontSeconds = preferences.getInt(CAMERA_MIN_FRONT_KEY, CAMERA_MIN_FRONT_DEFAULT);
        screenshotMinSeconds = preferences.getInt(SCREENSHOT_MIN_KEY, SCREENSHOT_MIN_DEFAULT);
        keywordEnabled = preferences.getBoolean(KEYWORD_ENABLED_KEY, false);
        keywordWords = preferences.getString(KEYWORD_WORDS_KEY, KEYWORD_WORDS_DEFAULT);
        uploadEnabled = preferences.getBoolean(UPLOAD_ENABLED_KEY, false);
        uploadUrl = preferences.getString(UPLOAD_URL_KEY, "");
        memorySizePref = preferences.getLong(AUDIO_MEMORY_SIZE_KEY, Runtime.getRuntime().maxMemory() / 4);

        SAMPLE_RATE = lowPower
                ? LOW_POWER_SAMPLE_RATE
                : preferences.getInt(SAMPLE_RATE_KEY, AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC));
        Log.d(TAG, "Sample rate: " + SAMPLE_RATE);
        FILL_RATE = 2 * SAMPLE_RATE;

        audioThread = new HandlerThread("audioThread", Thread.MAX_PRIORITY);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        createNotificationChannel();
        registerRecordingCallback();
        logEvent(getString(R.string.event_service_started));

        uploader = new TraceUploader(this);
        uploader.configure(uploadEnabled, uploadUrl);
        cameraCapturer = new ShakeCameraCapturer(this, captureListener);
        screenCapturer = new ScreenCapturer(this, captureListener);
        keywordDetector = new KeywordDetector(this, keywordListener);

        // Probing the filesystem is disk work, so keep it off the main thread.
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                getTracesDir();
            }
        });

        if(listeningWanted) {
            innerStartListening();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        logEvent(getString(R.string.event_service_stopped));
        unregisterRecordingCallback();
        audioHandler.removeCallbacks(autoSaveWatchdog);
        autoSaveDeadline = -1;
        innerStopListening(); // queues a last save of whatever is still in memory
        if(cameraCapturer != null) cameraCapturer.stop();
        if(screenCapturer != null) screenCapturer.stop();
        if(keywordDetector != null) keywordDetector.stop();
        if(uploader != null) uploader.shutdown();
        stopForeground(true);
        // Lets the queued save run and only then ends the thread, instead of leaking one
        // HandlerThread per service lifetime.
        audioThread.quitSafely();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new BackgroundRecorderBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }

    public void enableListening() {
        listeningWanted = true;
        prefs().edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, true).apply();

        innerStartListening();
        updateNotification();
    }

    public void disableListening() {
        listeningWanted = false;
        prefs().edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, false).apply();

        innerStopListening();
    }

    private void innerStartListening() {
        if(state == STATE_LISTENING) return;
        state = STATE_LISTENING;

        Log.d(TAG, "Queueing: START LISTENING");

        // Counters describe the current capture, so they start over with it.
        captureStartedElapsed = SystemClock.elapsedRealtime();
        lastReadElapsed = 0;
        bytesCaptured = 0;
        readErrorCount = 0;
        micTakeoverCount = 0;
        micRetries = 0;
        micSilenced = false;
        micBlocked = false;

        try {
            startService(new Intent(this, this.getClass()));
        } catch (Exception e) {
            // Android refuses to start a service from the background, which is exactly where we
            // are when the system brings the service back on its own after killing it. Capture
            // still works while the process lives, so carry on rather than taking it down.
            Log.e(TAG, "Can't start the service in the foreground", e);
            recordError(getString(R.string.error_cant_go_foreground));
        }

        final long memorySize = memorySizePref;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: START LISTENING");
                audioMemory.allocate(memorySize);
                openAudioRecord();
            }
        });

        armAutoSave();
        armCaptureWatchdog();
        startLocationUpdates();
        if(cameraEnabled) {
            configureCameraCapturer();
            cameraCapturer.start();
        }
        startKeywordDetection();
        logEvent(getString(R.string.event_listening_started, SAMPLE_RATE / 1000f));
    }

    private void innerStopListening() {
        if(state != STATE_LISTENING) return;

        // Everything in memory is audio the user asked us to keep, and the buffers are about to be
        // handed back. Queued before the release below, so it still sees the audio and the rate.
        queueSave(ALL_MEMORY, null, null, true);

        state = STATE_READY;
        autoSaveDeadline = -1;
        micSilenced = false;
        micBlocked = false;
        audioHandler.removeCallbacks(autoSaveWatchdog);
        // Location stops with the audio it annotates; the save queued above takes the fixes with it.
        stopLocationUpdates();
        // The visual capturers only make sense while the service is foreground, which ends here.
        cameraCapturer.stop();
        screenCapturer.stop();
        screenshotEnabled = false;
        // Keyword detection listens to the capture, so it ends with it.
        keywordDetector.stop();
        Log.d(TAG, "Queueing: STOP LISTENING");
        logEvent(getString(R.string.event_listening_stopped));

        stopForeground(true);
        stopService(new Intent(this, this.getClass()));

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: STOP LISTENING");
                releaseAudioRecord();
                audioHandler.removeCallbacks(audioReader);
                audioHandler.removeCallbacks(audioOpener);
                audioHandler.removeCallbacks(autoSaveWatchdog);
                audioHandler.removeCallbacks(captureWatchdog);
                audioMemory.allocate(0);
            }
        });

    }

    // ------------------------------------------------------------------ the microphone

    /**
     * Opens the microphone and starts the capture loop. Audio thread only.
     *
     * Failing to open is not the end of the capture: the usual reason is another app holding the
     * input, and that app will hand it back. So a failure schedules another attempt instead of
     * dropping to STATE_READY, which used to leave the service alive but permanently deaf.
     */
    @SuppressLint("MissingPermission")
    private void openAudioRecord() {
        assert audioHandler.getLooper() == Looper.myLooper();
        if(state != STATE_LISTENING) return;
        if(audioRecord != null) return;

        Log.d(TAG, "Audio: INITIALIZING AUDIO_RECORD");
        AudioRecord record = null;
        try {
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(AudioMemory.CHUNK_SIZE)
                    // Says our capture does not need the input to itself, which is what lets
                    // another app record at the same time where the platform allows it at all.
                    .setPrivacySensitive(false)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "Audio: can't build an AudioRecord", e);
        }

        if(record != null && record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
        }
        if(record != null) {
            try {
                record.startRecording();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Audio: startRecording refused", e);
            }
            if(record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                // startRecording also fails quietly when the input is already spoken for.
                Log.e(TAG, "Audio: startRecording did not take");
                record.release();
                record = null;
            }
        }
        if(record == null) {
            retryOpeningTheMicrophone();
            return;
        }

        // The reader has to come back before AudioRecord's own buffer overflows; remember
        // how long that is so a stalled capture can be told apart from a slow one.
        final float bufferSeconds = record.getBufferSizeInFrames() / (float) SAMPLE_RATE;
        readGapToleranceMillis = Math.max(30000, Math.min((long) (bufferSeconds * 3000),
                MAX_READ_INTERVAL_MILLIS * 3));
        Log.d(TAG, "Audio: STARTED AudioRecord, buffer " + bufferSeconds + " s");

        audioRecord = record;
        audioSessionId = record.getAudioSessionId();
        captureStartedElapsed = SystemClock.elapsedRealtime();
        // Nothing has been read from *this* AudioRecord yet, and leaving the previous one's time
        // in place would have the watchdog judge a brand new capture by how long the old one was
        // silent for, and restart it on the spot.
        lastReadElapsed = 0;
        consecutiveReadErrors = 0;

        if(micBlocked) {
            micBlocked = false;
            clearLastError();
            logEvent(getString(R.string.event_mic_regained));
            mainHandler.post(notificationUpdater);
        }
        micRetries = 0;

        audioHandler.removeCallbacks(audioReader);
        audioHandler.post(audioReader);
    }

    /** Backs off between attempts, so a microphone held for an hour costs almost nothing. */
    private void retryOpeningTheMicrophone() {
        ++micRetries;
        if(!micBlocked) {
            micBlocked = true;
            recordError(getString(R.string.error_mic_unavailable));
            mainHandler.post(notificationUpdater);
        }
        final long delay = Math.min(2000L * micRetries, MAX_MIC_RETRY_MILLIS);
        Log.w(TAG, "Audio: microphone unavailable, retrying in " + delay + " ms");
        audioHandler.removeCallbacks(audioOpener);
        audioHandler.postDelayed(audioOpener, delay);
    }

    private final Runnable audioOpener = new Runnable() {
        @Override
        public void run() {
            openAudioRecord();
        }
    };

    /** Hands the microphone back. Audio thread only. */
    private void releaseAudioRecord() {
        if(audioRecord == null) return;
        try {
            audioRecord.stop();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Audio: stop refused, releasing anyway", e);
        }
        audioRecord.release();
        audioRecord = null;
        audioSessionId = 0;
    }

    /**
     * Throws the current AudioRecord away and opens a fresh one, keeping audio memory: the
     * format does not change, so what was already captured stays valid and the recording simply
     * continues. Whatever the old one still held is drained first.
     */
    private void restartAudioRecord(final String reason) {
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                if(state != STATE_LISTENING) return;
                Log.d(TAG, "Audio: restarting capture, " + reason);
                flushAudioRecord();
                audioHandler.removeCallbacks(audioReader);
                releaseAudioRecord();
                openAudioRecord();
            }
        });
    }

    private long maxReadIntervalMillis() {
        return keywordEnabled ? KEYWORD_READ_INTERVAL_MILLIS : MAX_READ_INTERVAL_MILLIS;
    }

    private long captureWatchdogPeriodMillis() {
        return isLowPowerEnabled() ? CAPTURE_WATCHDOG_MILLIS_LOW_POWER : CAPTURE_WATCHDOG_MILLIS;
    }

    private void armCaptureWatchdog() {
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                audioHandler.removeCallbacks(captureWatchdog);
                if(state != STATE_LISTENING) return;
                audioHandler.postDelayed(captureWatchdog, captureWatchdogPeriodMillis());
            }
        });
    }

    /**
     * The last line of defence against a capture that stopped without saying so. Everything else
     * here reacts to something Android told us; this one only looks at whether audio is actually
     * arriving, so it also covers the failures nobody reports.
     */
    private final Runnable captureWatchdog = new Runnable() {
        @Override
        public void run() {
            if(state != STATE_LISTENING) return;

            if(audioRecord == null) {
                // Either the open failed and the retry is still pending, or something released it
                // behind our back. Either way, asking again is the whole fix.
                openAudioRecord();
            } else if(!micSilenced) {
                // While silenced the reads keep succeeding with zeros, so this check would never
                // fire anyway; the recording callback owns that case.
                final long last = lastReadElapsed;
                final long since = SystemClock.elapsedRealtime()
                        - (last == 0 ? captureStartedElapsed : last);
                if(since > readGapToleranceMillis) {
                    logEvent(getString(R.string.event_capture_stalled, since / 1000));
                    restartAudioRecord("no audio for " + (since / 1000) + " s");
                }
            }

            audioHandler.postDelayed(captureWatchdog, captureWatchdogPeriodMillis());
        }
    };

    // ---------------------------------------------------------- sharing the microphone

    /**
     * Android does not let two apps have the same microphone: when a call or another recorder
     * outranks us, our capture is not stopped, it is fed silence. Without this we would happily
     * write minutes of digital zeros and call it a recording.
     */
    private final AudioManager.AudioRecordingCallback recordingCallback =
            new AudioManager.AudioRecordingCallback() {
        @Override
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
            final int session = audioSessionId;
            if(session == 0 || configurations == null) return;

            boolean silenced = false;
            boolean found = false;
            for (AudioRecordingConfiguration configuration : configurations) {
                if(configuration.getClientAudioSessionId() != session) continue;
                found = true;
                silenced = configuration.isClientSilenced();
            }
            if(!found) return; // not about our capture

            if(silenced == micSilenced) return;
            micSilenced = silenced;
            if(silenced) {
                ++micTakeoverCount;
                logEvent(getString(R.string.event_mic_taken));
            } else {
                logEvent(getString(R.string.event_mic_returned));
                // The input is ours again. A fresh AudioRecord is cheap and, unlike the silenced
                // one, is guaranteed to be delivering real audio from its first sample.
                restartAudioRecord("the microphone was handed back");
            }
            updateNotification();
        }
    };

    private AudioManager audioManager() {
        if(audioManager == null) audioManager = getSystemService(AudioManager.class);
        return audioManager;
    }

    private void registerRecordingCallback() {
        final AudioManager manager = audioManager();
        if(manager == null) return;
        manager.registerAudioRecordingCallback(recordingCallback, mainHandler);
    }

    private void unregisterRecordingCallback() {
        final AudioManager manager = audioManager();
        if(manager == null) return;
        manager.unregisterAudioRecordingCallback(recordingCallback);
    }

    /** True while the phone is in a call, which is the one case no app can record around. */
    private boolean inCall() {
        final AudioManager manager = audioManager();
        if(manager == null) return false;
        final int mode = manager.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    // ------------------------------------------------------------------ storage

    /**
     * Directory traces are written to. Resolved on first use by actually probing the
     * filesystem, see {@link Storage}.
     */
    public File getTracesDir() {
        File dir = storageDir;
        if(dir == null) {
            dir = Storage.resolve(this);
            storageDir = dir;
            if(!Storage.isPublic(dir)) {
                logEvent(getString(R.string.event_storage_fallback, dir.getAbsolutePath()));
            }
        }
        return dir;
    }

    /** Forgets the resolved directory, so the next save probes the filesystem again. */
    public void forgetStorageDir() {
        storageDir = null;
    }

    /** The resolved directory, or null while it is still being probed. Never touches the disk. */
    public File getResolvedDir() {
        return storageDir;
    }

    /** Traces are named after the wall clock time of their first sample, see {@link Traces}. */
    static String timestampName(long millis) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(millis));
    }

    static File uniqueFile(File dir, String baseName, String extension) {
        File file = new File(dir, baseName + extension);
        for(int i = 2; file.exists(); ++i) {
            file = new File(dir, baseName + "_" + i + extension);
        }
        return file;
    }

    /** A file name without its extension, which is the name its companion track shares. */
    private static String baseNameOf(File file) {
        final String name = file.getName();
        final int dot = name.lastIndexOf('.');
        return (dot < 0) ? name : name.substring(0, dot);
    }

    // ------------------------------------------------------------------ saving

    /**
     * Saves the whole audio memory into a new file, named after the time of its first sample.
     * Memory is emptied afterwards, so consecutive saves never overlap.
     */
    public void saveEverything(final WavFileReceiver wavFileReceiver) {
        saveMemory(ALL_MEMORY, null, wavFileReceiver);
    }

    /**
     * Saves the most recent memorySeconds of audio memory into a new file, emptying the memory.
     * Pass {@link #ALL_MEMORY} to save everything. A null baseName means "name it after the
     * time of its first sample".
     */
    public void saveMemory(final float memorySeconds, final String baseName, final WavFileReceiver wavFileReceiver) {
        queueSave(memorySeconds, baseName, wavFileReceiver, false);
    }

    private void queueSave(final float memorySeconds, final String baseName,
                           final WavFileReceiver wavFileReceiver, final boolean silent) {
        if(state == STATE_READY) {
            if(!silent) showToast(getString(R.string.nothing_to_save));
            return;
        }
        // Whatever is in memory was captured at today's rate; remember it, because the caller may
        // be about to change the rate (that is exactly what applySampleRate does).
        final int rate = SAMPLE_RATE;
        final int fillRate = FILL_RATE;
        manualSavePending = true;
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    flushAudioRecord();
                    writeMemoryToFile(memorySeconds, baseName, wavFileReceiver, silent, rate, fillRate);
                } finally {
                    manualSavePending = false;
                }
            }
        });
        armAutoSave();
    }

    /**
     * Writes audio memory into a fresh wav file and empties the memory.
     * Only allowed on the audio thread, right after {@link #flushAudioRecord()}.
     * Returns true when a file was actually written.
     */
    private boolean writeMemoryToFile(float memorySeconds, String baseName,
                                      final WavFileReceiver wavFileReceiver, boolean silent,
                                      int sampleRate, int fillRate) {
        assert audioHandler.getLooper() == Looper.myLooper();
        if(writing) return false; // never nest saves
        writing = true;
        try {
            return doWriteMemoryToFile(memorySeconds, baseName, wavFileReceiver, silent, sampleRate, fillRate);
        } finally {
            writing = false;
        }
    }

    private boolean doWriteMemoryToFile(float memorySeconds, String baseName,
                                        final WavFileReceiver wavFileReceiver, boolean silent,
                                        int sampleRate, int fillRate) {
        final int bytesAvailable = audioMemory.countFilled();
        final boolean haveAudio = bytesAvailable > 0;
        // Fixes recorded while the microphone was busy are still worth keeping, so an empty audio
        // memory is only nothing to save when the track is empty too.
        if(!haveAudio && track.size() == 0) {
            Log.d(TAG, "Nothing to save, audio memory is empty");
            if(!silent) showToast(getString(R.string.nothing_to_save));
            return false;
        }

        int skipBytes = 0;
        if(memorySeconds >= 0) {
            final long keepBytes = (long)(memorySeconds * fillRate);
            skipBytes = (int) Math.max(0, bytesAvailable - keepBytes);
        }

        final File dir = getTracesDir();
        if(!Storage.canWrite(dir)) {
            // Whatever we resolved to is not usable any more; probe again next time.
            forgetStorageDir();
            recordError(getString(R.string.error_cant_write_dir, dir.getAbsolutePath()));
            if(!silent) showToast(getString(R.string.error_cant_write_dir, dir.getAbsolutePath()));
            return false;
        }

        if(!haveAudio) {
            // Only location to keep. It gets named after its own first fix, since there is no
            // recording for it to take its name from.
            writeTrack(dir, null);
            if(!silent) showToast(getString(R.string.nothing_to_save));
            return false;
        }

        final int useBytes = bytesAvailable - skipBytes;
        final long startMillis = System.currentTimeMillis() - 1000L * useBytes / fillRate;
        final File file = uniqueFile(dir,
                (baseName == null || baseName.isEmpty()) ? timestampName(startMillis) : baseName, ".wav");
        final WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(sampleRate).build();

        int written = 0;
        // Only a file whose riff header was rewritten with its real size is playable, so closing
        // has to succeed too before the memory it came from can be dropped.
        boolean complete = false;
        try {
            final WavFileWriter writer = new WavFileWriter(format, file);
            try {
                audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                    @Override
                    public int consume(byte[] array, int offset, int count) throws IOException {
                        writer.write(array, offset, count);
                        return 0;
                    }
                });
            } finally {
                written = writer.getTotalSampleBytesWritten();
                writer.close(); // rewrites the riff header with the final size
            }
            complete = true;
        } catch (IOException e) {
            Log.e(TAG, "Error while writing audio history into " + file.getAbsolutePath(), e);
            recordError(getString(R.string.error_during_writing_history_into) + file.getName());
            if(!silent) showToast(getString(R.string.error_during_writing_history_into) + file.getAbsolutePath());
        }

        if(written <= 0 || !complete) {
            // Nothing usable landed on disk, so keep the memory around for the next attempt.
            file.delete();
            return false;
        }
        audioMemory.reset();
        // Same base name as the recording, so a file and its track are obviously a pair.
        writeTrack(dir, baseNameOf(file));
        if(uploader != null) uploader.kick();

        Log.d(TAG, "Saved " + written + " B into " + file.getAbsolutePath());
        // Audio is being kept again, so stop showing whatever failed earlier.
        lastError = null;
        lastErrorMillis = 0;
        lastSaveMillis = System.currentTimeMillis();
        lastSaveName = file.getName();
        final float runtime = written / (float) fillRate;
        logEvent(getString(R.string.event_saved, file.getName(),
                TracesActivity.longDuration((long) (runtime * 1000))));
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                updateNotification();
                if(wavFileReceiver != null) wavFileReceiver.fileReady(file, runtime);
            }
        });
        return true;
    }

    private void showToast(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(SaidItService.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    public long getMemorySize() {
        return audioMemory.getAllocatedMemorySize();
    }

    /** The size that was asked for, which is what Settings should highlight. */
    public long getMemorySizePreference() {
        return memorySizePref;
    }

    public void setMemorySize(final long memorySize) {
        memorySizePref = memorySize;
        prefs().edit().putLong(AUDIO_MEMORY_SIZE_KEY, memorySize).apply();

        if(listeningWanted) {
            audioHandler.post(new Runnable() {
                @Override
                public void run() {
                    audioMemory.allocate(memorySize);
                }
            });
        }
    }

    public int getSamplingRate() {
        return SAMPLE_RATE;
    }

    public void setSampleRate(int sampleRate) {
        // Low power mode owns the sample rate while it is on.
        if(isLowPowerEnabled()) return;
        applySampleRate(sampleRate);
    }

    /**
     * Persists the sample rate and, when capturing, restarts AudioRecord with it.
     * Audio memory cannot survive a format change, so whatever is in it is saved first.
     */
    private void applySampleRate(int sampleRate) {
        if(sampleRate == SAMPLE_RATE && state != STATE_READY) return;

        // The 8 kHz low power mode drops to is not the user's quality choice, so it must not
        // overwrite it: PRE_LOW_POWER_SAMPLE_RATE_KEY is what brings the real one back.
        if(!isLowPowerEnabled()) prefs().edit().putInt(SAMPLE_RATE_KEY, sampleRate).apply();

        if(state == STATE_LISTENING) {
            // innerStopListening saves what is in memory, queued before the restart below and
            // carrying the old rate with it: audio memory cannot survive a format change.
            innerStopListening();
            SAMPLE_RATE = sampleRate;
            FILL_RATE = 2 * sampleRate;
            innerStartListening();
        } else {
            SAMPLE_RATE = sampleRate;
            FILL_RATE = 2 * sampleRate;
        }
    }

    public interface WavFileReceiver {
        public void fileReady(File file, float runtime);
    }

    private void flushAudioRecord() {
        // Only allowed on the audio thread
        assert audioHandler.getLooper() == Looper.myLooper();
        if(audioRecord == null) return; // nothing is capturing, so there is nothing to drain
        audioHandler.removeCallbacks(audioReader); // remove any delayed callbacks
        audioReader.run();
    }

    // ------------------------------------------------------------------ location

    /**
     * Location fixes are logged for exactly as long as audio is being captured, and written out
     * by the same saves, so every recording has a track of where it was made under the same name
     * with a .gpx extension.
     */
    public boolean isGpsEnabled() {
        return gpsEnabled;
    }

    public void setGpsEnabled(boolean enabled) {
        if(enabled == gpsEnabled) return;
        gpsEnabled = enabled;
        prefs().edit().putBoolean(GPS_ENABLED_KEY, enabled).apply();

        if(enabled) {
            logEvent(getString(R.string.event_gps_on));
            // The foreground service has to declare that it uses location before it may, and it
            // was started without that type back when logging was off.
            refreshForegroundType();
            startLocationUpdates();
        } else {
            stopLocationUpdates();
            logEvent(getString(R.string.event_gps_off));
            // Fixes already taken belong to audio still in memory, so they go out with it rather
            // than being thrown away here.
            refreshForegroundType();
        }
        updateNotification();
    }

    public boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ shake camera capture

    public boolean isCameraEnabled() {
        return cameraEnabled;
    }

    public boolean hasCameraPermission() {
        return checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    public int getShakeLevel() {
        return shakeLevel;
    }

    /** How hard the phone has to be shaken: 0 gentle, 1 normal, 2 vigorous. */
    public void setShakeLevel(int level) {
        shakeLevel = Math.max(0, Math.min(2, level));
        prefs().edit().putInt(SHAKE_LEVEL_KEY, shakeLevel).apply();
        configureCameraCapturer();
    }

    public int getCameraMinBackSeconds() {
        return cameraMinBackSeconds;
    }

    public int getCameraMinFrontSeconds() {
        return cameraMinFrontSeconds;
    }

    /** Sets the shortest gap between clips of each camera, applied live. */
    public void setCameraMinIntervals(int backSeconds, int frontSeconds) {
        cameraMinBackSeconds = Math.max(0, backSeconds);
        cameraMinFrontSeconds = Math.max(0, frontSeconds);
        prefs().edit()
                .putInt(CAMERA_MIN_BACK_KEY, cameraMinBackSeconds)
                .putInt(CAMERA_MIN_FRONT_KEY, cameraMinFrontSeconds)
                .apply();
        configureCameraCapturer();
    }

    public boolean isFrontCameraEnabled() {
        return frontCameraEnabled;
    }

    /** Leaves the front camera out of every trigger, so only the back one records. */
    public void setFrontCameraEnabled(boolean enabled) {
        if(enabled == frontCameraEnabled) return;
        frontCameraEnabled = enabled;
        prefs().edit().putBoolean(CAMERA_FRONT_ENABLED_KEY, enabled).apply();
        configureCameraCapturer();
        logEvent(getString(enabled ? R.string.event_front_camera_on : R.string.event_front_camera_off));
    }

    public int getClipSeconds() {
        return clipSeconds;
    }

    public void setClipSeconds(int seconds) {
        clipSeconds = Math.max(1, Math.min(60, seconds));
        prefs().edit().putInt(CLIP_SECONDS_KEY, clipSeconds).apply();
        configureCameraCapturer();
    }

    /** Hands the capturer every setting that shapes a trigger, whether it is running or not. */
    private void configureCameraCapturer() {
        if(cameraCapturer == null) return;
        cameraCapturer.configure(shakeLevel, cameraMinBackSeconds * 1000L,
                cameraMinFrontSeconds * 1000L, frontCameraEnabled, clipSeconds);
    }

    /**
     * Turns shake-triggered capture on or off. Like GPS it declares a foreground service type, so
     * turning it on re-declares the service before the camera is ever touched.
     */
    public void setCameraEnabled(boolean enabled) {
        if(enabled == cameraEnabled) return;
        cameraEnabled = enabled;
        prefs().edit().putBoolean(CAMERA_ENABLED_KEY, enabled).apply();
        if(enabled) {
            refreshForegroundType();
            if(state == STATE_LISTENING) {
                configureCameraCapturer();
                cameraCapturer.start();
            }
            logEvent(getString(R.string.event_camera_on));
        } else {
            cameraCapturer.stop();
            refreshForegroundType();
            logEvent(getString(R.string.event_camera_off));
        }
        updateNotification();
    }

    // ------------------------------------------------------------------ screenshots

    public boolean isScreenshotEnabled() {
        return screenshotEnabled && screenCapturer != null && screenCapturer.isRunning();
    }

    /** True while Echo is foreground and recording, which is when screenshots may run. */
    public boolean isListeningForScreenshots() {
        return state == STATE_LISTENING;
    }

    public int getScreenshotMinSeconds() {
        return screenshotMinSeconds;
    }

    public void setScreenshotMinSeconds(int seconds) {
        screenshotMinSeconds = Math.max(1, seconds);
        prefs().edit().putInt(SCREENSHOT_MIN_KEY, screenshotMinSeconds).apply();
        if(screenCapturer != null) screenCapturer.setMinIntervalMillis(screenshotMinSeconds * 1000L);
    }

    /**
     * Starts MediaProjection screenshots with a consent the activity has just been granted. The
     * service must be foreground first, so the media-projection type is declared before the
     * projection is created. Only works while listening, since that is when Echo is foreground.
     */
    public boolean startScreenCapture(int resultCode, Intent data) {
        if(state != STATE_LISTENING) return false;
        screenshotEnabled = true;
        refreshForegroundType();
        screenCapturer.setMinIntervalMillis(screenshotMinSeconds * 1000L);
        final boolean started = screenCapturer.start(resultCode, data);
        if(started) {
            logEvent(getString(R.string.event_screenshot_on));
        } else {
            screenshotEnabled = false;
            refreshForegroundType();
        }
        updateNotification();
        return started;
    }

    public void stopScreenCapture() {
        if(!screenshotEnabled) return;
        screenCapturer.stop();
        screenshotEnabled = false;
        refreshForegroundType();
        logEvent(getString(R.string.event_screenshot_off));
        updateNotification();
    }

    // ------------------------------------------------------------------ spoken keyword

    public boolean isKeywordEnabled() {
        return keywordEnabled;
    }

    public String getKeywordWords() {
        return keywordWords;
    }

    /** What the detector is actually doing, which is what the settings screen reports. */
    public KeywordDetector.Status getKeywordStatus() {
        return keywordDetector == null ? KeywordDetector.Status.OFF : keywordDetector.getStatus();
    }

    /** True when a speech model was shipped with this build; without one nothing is recognised. */
    public boolean hasKeywordModel() {
        return keywordDetector != null && keywordDetector.hasModel();
    }

    public void setKeywordEnabled(boolean enabled) {
        if(enabled == keywordEnabled) return;
        keywordEnabled = enabled;
        prefs().edit().putBoolean(KEYWORD_ENABLED_KEY, enabled).apply();
        if(enabled) {
            startKeywordDetection();
            logEvent(getString(R.string.event_keyword_on));
        } else {
            keywordDetector.stop();
            logEvent(getString(R.string.event_keyword_off));
        }
    }

    /** The words to listen for, separated by commas or spaces. Restarts the detector. */
    public void setKeywordWords(String words) {
        final String cleaned = (words == null || words.trim().isEmpty())
                ? KEYWORD_WORDS_DEFAULT : words.trim();
        if(cleaned.equals(keywordWords)) return;
        keywordWords = cleaned;
        prefs().edit().putString(KEYWORD_WORDS_KEY, keywordWords).apply();
        if(keywordEnabled) {
            keywordDetector.stop();
            startKeywordDetection();
        }
    }

    /**
     * Starts listening for the keyword in the audio being captured. Only worth doing while there
     * is capture to listen to, and the recogniser is told the rate that capture actually runs at.
     */
    private void startKeywordDetection() {
        if(!keywordEnabled || state != STATE_LISTENING) return;
        keywordDetector.start(keywordWords, SAMPLE_RATE);
    }

    private final KeywordDetector.Listener keywordListener = new KeywordDetector.Listener() {
        @Override
        public void onKeyword(final String word) {
            logEvent(getString(R.string.event_keyword_heard, word));
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    soundAlert();
                }
            });
        }
    };

    /** How long the alert a heard keyword sounds for. */
    private static final long ALERT_MILLIS = 4000;

    /**
     * Sounds the alert, on the alarm stream so it is heard through a silenced ringer. Main thread
     * only, and never more than one at a time: a second keyword restarts the same ringtone rather
     * than layering another on top of it.
     */
    private void soundAlert() {
        try {
            if(alertRingtone == null) {
                android.net.Uri uri = android.media.RingtoneManager
                        .getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
                if(uri == null) {
                    uri = android.media.RingtoneManager
                            .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
                }
                if(uri == null) return;
                alertRingtone = android.media.RingtoneManager.getRingtone(this, uri);
                if(alertRingtone == null) return;
                alertRingtone.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
            if(alertRingtone.isPlaying()) alertRingtone.stop();
            alertRingtone.play();
            mainHandler.removeCallbacks(alertStopper);
            mainHandler.postDelayed(alertStopper, ALERT_MILLIS);
        } catch (Exception e) {
            Log.w(TAG, "Can't sound the alert: " + e.getMessage());
        }
    }

    private final Runnable alertStopper = new Runnable() {
        @Override
        public void run() {
            try {
                if(alertRingtone != null && alertRingtone.isPlaying()) alertRingtone.stop();
            } catch (Exception ignore) { }
        }
    };

    // ------------------------------------------------------------------ written traces

    /** Where a written trace ends up, reported back on the main thread. Null means it failed. */
    public interface TextTraceReceiver {
        void traceWritten(File file);
    }

    /**
     * Writes a note beside the recordings, named after the moment it was saved just like every
     * other trace. The disk work goes to the audio thread, which is the one that owns saving.
     */
    public void saveNote(String text, TextTraceReceiver receiver) {
        saveTextTrace(text, "note", ".txt", receiver);
    }

    /** Writes a filled-in survey, as the JSON the survey screen built. */
    public void saveSurvey(String json, TextTraceReceiver receiver) {
        saveTextTrace(json, "survey", ".json", receiver);
    }

    private void saveTextTrace(final String content, final String suffix, final String extension,
                               final TextTraceReceiver receiver) {
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                File written = null;
                final File file = uniqueFile(getTracesDir(),
                        timestampName(System.currentTimeMillis()) + "_" + suffix, extension);
                try {
                    final java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(file), "UTF-8");
                    try {
                        writer.write(content);
                    } finally {
                        writer.close();
                    }
                    written = file;
                    logEvent(getString(R.string.event_saved_text, file.getName()));
                    if(uploader != null) uploader.kick();
                } catch (IOException e) {
                    Log.e(TAG, "Can't write " + file.getAbsolutePath(), e);
                    recordError(getString(R.string.error_cant_write_text, file.getName()));
                    file.delete();
                }
                if(receiver == null) return;
                final File result = written;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        receiver.traceWritten(result);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ uploading

    public boolean isUploadEnabled() {
        return uploadEnabled;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public void setUploadEnabled(boolean enabled) {
        uploadEnabled = enabled;
        prefs().edit().putBoolean(UPLOAD_ENABLED_KEY, enabled).apply();
        if(uploader != null) uploader.configure(uploadEnabled, uploadUrl);
        logEvent(getString(enabled ? R.string.event_upload_on : R.string.event_upload_off));
    }

    public void setUploadUrl(String url) {
        uploadUrl = url == null ? "" : url.trim();
        prefs().edit().putString(UPLOAD_URL_KEY, uploadUrl).apply();
        if(uploader != null) uploader.configure(uploadEnabled, uploadUrl);
    }

    /** True when the GPS provider itself is switched on, which no permission can substitute for. */
    public boolean isGpsProviderEnabled() {
        final LocationManager manager = locationManager();
        try {
            return manager != null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private LocationManager locationManager() {
        if(locationManager == null) locationManager = getSystemService(LocationManager.class);
        return locationManager;
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            track.add(location);
            lastFixMillis = System.currentTimeMillis();
            lastFixAccuracy = location.hasAccuracy() ? location.getAccuracy() : -1;
            ++fixCount;
        }

        @Override
        public void onProviderDisabled(String provider) {
            logEvent(getString(R.string.event_gps_provider_off));
        }

        @Override
        public void onProviderEnabled(String provider) {
            logEvent(getString(R.string.event_gps_provider_on));
        }
    };

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if(!gpsEnabled || state != STATE_LISTENING) return;
        if(gpsRequested) return;
        if(!hasLocationPermission()) {
            recordError(getString(R.string.error_no_location_permission));
            return;
        }
        final LocationManager manager = locationManager();
        if(manager == null) return;

        final long interval = wantedGpsIntervalMillis();
        try {
            // Fixes are delivered on the main thread, which is where the buffer expects them; it
            // is synchronized precisely because saves read it from the audio thread.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f,
                    locationListener, Looper.getMainLooper());
            gpsRequested = true;
            gpsIntervalMillis = interval;
            logEvent(getString(R.string.event_gps_started, interval / 1000f));
        } catch (SecurityException e) {
            recordError(getString(R.string.error_no_location_permission));
        } catch (IllegalArgumentException e) {
            // A device with no GPS hardware at all. Nothing to log, and nothing to fix either.
            recordError(getString(R.string.error_no_gps_provider));
        }
    }

    /** How often a fix is wanted, which low power mode is the only thing that changes. */
    private long wantedGpsIntervalMillis() {
        return isLowPowerEnabled() ? LOW_POWER_GPS_INTERVAL_MILLIS : GPS_INTERVAL_MILLIS;
    }

    private void stopLocationUpdates() {
        if(!gpsRequested) return;
        gpsRequested = false;
        gpsIntervalMillis = 0;
        final LocationManager manager = locationManager();
        if(manager == null) return;
        try {
            manager.removeUpdates(locationListener);
        } catch (Exception e) {
            Log.w(TAG, "Can't stop location updates", e);
        }
        logEvent(getString(R.string.event_gps_stopped));
    }

    /**
     * Writes the buffered fixes into a GPX file and empties the buffer. Audio thread only, from
     * inside a save, so a track never gets split across two recordings.
     */
    private void writeTrack(File dir, String baseName) {
        if(track.size() == 0) return;

        final String name = (baseName == null || baseName.isEmpty())
                ? timestampName(track.firstFixMillis()) : baseName;
        final File file = uniqueFile(dir, name, ".gpx");
        final int dropped = track.droppedCount();

        int points = 0;
        try {
            points = track.writeTo(file, name);
        } catch (IOException e) {
            Log.e(TAG, "Error while writing the track into " + file.getAbsolutePath(), e);
            recordError(getString(R.string.error_cant_write_track, file.getName()));
            return;
        }

        if(points <= 0) {
            file.delete();
            return;
        }
        ++trackSaveCount;
        lastTrackName = file.getName();
        logEvent(getString(R.string.event_saved_track, file.getName(), points));
        if(dropped > 0) logEvent(getString(R.string.event_track_dropped, dropped));
        if(uploader != null) uploader.kick();
    }

    // ------------------------------------------------------------------ capture loop

    final AudioMemory.Consumer filler = new AudioMemory.Consumer() {
        @Override
        public int consume(final byte[] array, final int offset, final int count) throws IOException {
            final int read = audioRecord.read(array, offset, count, AudioRecord.READ_NON_BLOCKING);
            if (read < 0) {
                // Any error at all used to end the capture loop for good, because nothing
                // re-posted the reader: the service stayed "recording" and never saved again.
                // Keep the loop alive so a transient failure can recover.
                readErrorCount++;
                consecutiveReadErrors++;
                recordError(getString(R.string.error_audio_read, read));
                Log.e(TAG, "AUDIO RECORD READ ERROR " + read + ", retrying");
                // A dead object is gone for good, and errors that keep coming are not transient
                // either. Both want a new AudioRecord, not another read of the broken one.
                if (read == AudioRecord.ERROR_DEAD_OBJECT
                        || consecutiveReadErrors >= READ_ERRORS_BEFORE_RESTART) {
                    consecutiveReadErrors = 0;
                    restartAudioRecord("read error " + read);
                } else {
                    audioHandler.postDelayed(audioReader, 1000);
                }
                return 0;
            }
            if (read > 0) {
                lastReadElapsed = SystemClock.elapsedRealtime();
                bytesCaptured += read;
                consecutiveReadErrors = 0;
                // The detector gets the same samples the ring buffer just took, so listening for
                // a word costs no second microphone stream. It copies and returns.
                if (keywordEnabled) keywordDetector.feed(array, offset, read);
            }
            if (read == count) {
                // We've filled the buffer, so let's read again.
                audioHandler.post(audioReader);
            } else {
                // It seems we've read everything!
                //
                // Estimate how long do we have until audioRecord fills up completely and post the callback 1 second before that
                // (but not earlier than half the buffer and no later than 90% of the buffer).
                float bufferSizeInSeconds = audioRecord.getBufferSizeInFrames() / (float)SAMPLE_RATE;
                float delaySeconds = bufferSizeInSeconds - 1;
                delaySeconds = Math.max(delaySeconds, bufferSizeInSeconds * 0.5f);
                delaySeconds = Math.min(delaySeconds, bufferSizeInSeconds * 0.9f);
                audioHandler.postDelayed(audioReader,
                        Math.min((long)(delaySeconds * 1000), maxReadIntervalMillis()));
            }
            return read;
        }
    };

    final Runnable audioReader = new Runnable() {
        @Override
        public void run() {
            try {
                audioMemory.fill(filler);
            } catch (IOException e) {
                recordError(getString(R.string.error_capture_failed));
                Log.e(TAG, "Capture failed", e);
            }
            // Automatic saving rides along with capture: if audio is flowing this runs, and if it
            // is not there is nothing to save anyway.
            maybeAutoSave();
        }
    };

    // ------------------------------------------------------------------ automatic saving

    private long autoSaveIntervalMillis() {
        return getAutoSaveIntervalMinutes() * 60000L;
    }

    /**
     * Sets the next deadline and makes sure exactly one watchdog is pending. Runs on the audio
     * thread, which is also where the watchdog reschedules itself: doing it from the caller's
     * thread raced with a watchdog that was already running and left two of them pending.
     */
    private void armAutoSave() {
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                audioHandler.removeCallbacks(autoSaveWatchdog);
                if(state == STATE_READY || !isAutoSaveEnabled()) {
                    autoSaveDeadline = -1;
                    return;
                }
                autoSaveDeadline = SystemClock.elapsedRealtime() + autoSaveIntervalMillis();
                audioHandler.postDelayed(autoSaveWatchdog, watchdogPeriodMillis());
                Log.d(TAG, "Auto-save due in " + (autoSaveIntervalMillis() / 1000) + " s");
            }
        });
    }

    /**
     * The capture loop can sleep for as long as AudioRecord's buffer lasts, which at 8 kHz is
     * minutes, so a cheap timer checks the deadline in between. It only reads a clock, the real
     * work still happens on the audio thread.
     */
    private long watchdogPeriodMillis() {
        // A tenth of the interval keeps a one minute interval punctual, capped so a long interval
        // does not mean a long blind spot, and floored so we never spin.
        final long base = isLowPowerEnabled() ? 30000 : 15000;
        return Math.max(3000, Math.min(base, autoSaveIntervalMillis() / 10));
    }

    /** True when the interval is up, so the next save is owed to the user right now. */
    private boolean autoSaveDue() {
        final long deadline = autoSaveDeadline;
        return deadline >= 0 && SystemClock.elapsedRealtime() >= deadline;
    }

    private final Runnable autoSaveWatchdog = new Runnable() {
        @Override
        public void run() {
            // The interval is up, so drain AudioRecord first: audio only reaches memory when the
            // capture loop reads, and that loop sleeps for as long as the hardware buffer lasts,
            // which in low power mode is longer than the whole interval. Saving without this
            // wrote a file that stopped at the previous read, or found memory empty and wrote
            // nothing at all while quietly pushing the deadline another interval away.
            if(autoSaveDue()) flushAudioRecord();
            maybeAutoSave();
            if(state != STATE_READY && isAutoSaveEnabled()) {
                audioHandler.postDelayed(autoSaveWatchdog, watchdogPeriodMillis());
            }
        }
    };

    /**
     * Saves audio memory when its time is up, or early when it is about to overflow: memory is a
     * ring buffer, so an interval longer than what memory holds would silently drop audio.
     * Idempotent and safe to call as often as we like; audio thread only.
     */
    private void maybeAutoSave() {
        if(state != STATE_LISTENING) return;
        if(manualSavePending) return; // the queued manual save is about to take this audio
        if(!isAutoSaveEnabled()) {
            autoSaveDeadline = -1;
            return;
        }
        if(autoSaveDeadline < 0) {
            autoSaveDeadline = SystemClock.elapsedRealtime() + autoSaveIntervalMillis();
            return;
        }

        final long allocated = audioMemory.getAllocatedMemorySize();
        final boolean memoryFull = allocated > 0
                && audioMemory.countFilled() >= allocated * MEMORY_FULL_FRACTION;
        final boolean due = SystemClock.elapsedRealtime() >= autoSaveDeadline;
        if(!due && !memoryFull) return;

        if(writeMemoryToFile(ALL_MEMORY, null, null, true, SAMPLE_RATE, FILL_RATE)) {
            autoSaveCount++;
            if(!due) {
                memoryFullSaveCount++;
                logEvent(getString(R.string.event_saved_memory_full));
            }
        }
        autoSaveDeadline = SystemClock.elapsedRealtime() + autoSaveIntervalMillis();
    }

    public boolean isAutoSaveEnabled() {
        return autoSaveEnabled;
    }

    public void setAutoSaveEnabled(boolean enabled) {
        autoSaveEnabled = enabled;
        prefs().edit().putBoolean(AUTO_SAVE_ENABLED_KEY, enabled).apply();
        armAutoSave();
        updateNotification();
    }

    public int getAutoSaveIntervalMinutes() {
        return autoSaveIntervalMinutes;
    }

    public void setAutoSaveIntervalMinutes(int minutes) {
        autoSaveIntervalMinutes = Math.max(1, minutes);
        prefs().edit().putInt(AUTO_SAVE_INTERVAL_KEY, autoSaveIntervalMinutes).apply();
        armAutoSave();
        updateNotification();
    }

    // ------------------------------------------------------------------ low power mode

    public boolean isLowPowerEnabled() {
        return lowPower;
    }

    /**
     * Trades audio quality for battery: capture drops to 8 kHz, which is a sixth of the data of
     * 48 kHz, so AudioRecord wakes the CPU far less often, files are a sixth of the size and the
     * UI slows its refresh down. The previous sample rate comes back when it is switched off.
     */
    public void setLowPowerEnabled(boolean enabled) {
        if(enabled == lowPower) return;
        final SharedPreferences preferences = prefs();

        if(enabled) {
            lowPower = true;
            preferences.edit()
                    .putBoolean(LOW_POWER_KEY, true)
                    .putInt(PRE_LOW_POWER_SAMPLE_RATE_KEY, SAMPLE_RATE)
                    .apply();
            logEvent(getString(R.string.event_low_power_on));
            applySampleRate(LOW_POWER_SAMPLE_RATE);
        } else {
            final int previous = preferences.getInt(PRE_LOW_POWER_SAMPLE_RATE_KEY, SAMPLE_RATE);
            lowPower = false;
            preferences.edit().putBoolean(LOW_POWER_KEY, false).apply();
            logEvent(getString(R.string.event_low_power_off, previous / 1000f));
            applySampleRate(previous);
        }
        // The watchdog and the GPS radio both pace themselves off low power mode. Restarting
        // capture already re-registered location at the new interval, but applySampleRate does
        // nothing when the rate happens to match already, and then this is the only thing that does.
        armCaptureWatchdog();
        if(gpsRequested && gpsIntervalMillis != wantedGpsIntervalMillis()) {
            stopLocationUpdates();
            startLocationUpdates();
        }
        updateNotification();
    }

    // ------------------------------------------------------------------ state and diagnostics

    /** Everything the UI needs to draw the current state of the recorder. */
    public static class State {
        public boolean listeningEnabled;
        /** Seconds of audio currently held in memory. */
        public float memorized;
        /** Seconds of audio memory can hold. */
        public float totalMemory;
        public boolean autoSaveEnabled;
        public int autoSaveIntervalMinutes;
        /** Milliseconds until the next automatic save, or -1 when none is due. */
        public long nextAutoSaveInMillis;
        /** Wall clock time of the last save, or 0 when nothing was saved yet. */
        public long lastSaveMillis;
        public String lastSaveName;
        public int autoSaveCount;
        public int memoryFullSaveCount;

        public boolean lowPower;
        public int sampleRate;

        /** False when audio should be flowing but no sample has arrived in a long time. */
        public boolean capturing;
        public long bytesCaptured;
        public int readErrorCount;
        /** Milliseconds since the last read that returned audio, or -1 if there was none. */
        public long sinceLastReadMillis;

        /** True while another app or a call holds the input and Android is feeding us silence. */
        public boolean micSilenced;
        /** True while the microphone cannot be opened at all and reopening is being retried. */
        public boolean micBlocked;
        public int micTakeoverCount;
        public boolean inCall;

        public boolean gpsEnabled;
        public boolean gpsPermission;
        /** True when the GPS provider is switched on in the system settings. */
        public boolean gpsProviderEnabled;
        /** Fixes buffered and not written to a track file yet. */
        public int trackPoints;
        public int fixCount;
        /** Wall clock time of the last fix, or 0 when there has been none. */
        public long lastFixMillis;
        /** Accuracy of the last fix in metres, or -1 when it carried none. */
        public float lastFixAccuracy;
        public int trackSaveCount;
        public String lastTrackName;

        public String lastError;
        public long lastErrorMillis;

        public String storagePath;
        public boolean storagePublic;
        /** True when the interval is longer than memory holds, so audio would be dropped. */
        public boolean intervalExceedsMemory;

        /** Nothing is wrong and audio is being kept. */
        public boolean isHealthy() {
            if(!listeningEnabled) return true; // stopped on purpose
            if(micSilenced || micBlocked) return false; // the input belongs to somebody else
            return capturing && lastError == null && !intervalExceedsMemory;
        }
    }

    public interface StateCallback {
        public void state(State state);
    }

    public void getState(final StateCallback stateCallback) {
        final State result = new State();
        result.listeningEnabled = listeningWanted;
        result.autoSaveEnabled = isAutoSaveEnabled();
        result.autoSaveIntervalMinutes = getAutoSaveIntervalMinutes();
        final long deadline = autoSaveDeadline;
        result.nextAutoSaveInMillis = (deadline < 0) ? -1 : Math.max(0, deadline - SystemClock.elapsedRealtime());
        result.lastSaveMillis = lastSaveMillis;
        result.lastSaveName = lastSaveName;
        result.autoSaveCount = autoSaveCount;
        result.memoryFullSaveCount = memoryFullSaveCount;
        result.lowPower = isLowPowerEnabled();
        result.sampleRate = SAMPLE_RATE;
        result.bytesCaptured = bytesCaptured;
        result.readErrorCount = readErrorCount;
        result.micSilenced = micSilenced;
        result.micBlocked = micBlocked;
        result.micTakeoverCount = micTakeoverCount;
        result.inCall = inCall();
        result.gpsEnabled = gpsEnabled;
        result.gpsPermission = gpsEnabled && hasLocationPermission();
        result.gpsProviderEnabled = gpsEnabled && isGpsProviderEnabled();
        result.fixCount = fixCount;
        result.lastFixMillis = lastFixMillis;
        result.lastFixAccuracy = lastFixAccuracy;
        result.trackSaveCount = trackSaveCount;
        result.lastTrackName = lastTrackName;
        result.lastError = lastError;
        result.lastErrorMillis = lastErrorMillis;
        // Deliberately the cached value: resolving probes the filesystem and this runs on the
        // main thread once a second.
        final File dir = storageDir;
        result.storagePath = (dir == null) ? null : dir.getAbsolutePath();
        result.storagePublic = dir != null && Storage.isPublic(dir);

        final long lastRead = lastReadElapsed;
        if(state == STATE_READY) {
            result.capturing = false;
            result.sinceLastReadMillis = -1;
        } else if(micSilenced || micBlocked) {
            // Reads may well be succeeding, but what they return is silence, so no.
            result.capturing = false;
            result.sinceLastReadMillis = (lastRead == 0)
                    ? -1 : SystemClock.elapsedRealtime() - lastRead;
        } else if(lastRead == 0) {
            // Nothing has arrived yet. Give AudioRecord its buffer's worth of time before
            // calling it dead, but do call it dead after that: a microphone that never delivers
            // is precisely what happens when the service was started from the background.
            result.sinceLastReadMillis = -1;
            result.capturing = SystemClock.elapsedRealtime() - captureStartedElapsed < readGapToleranceMillis;
        } else {
            result.sinceLastReadMillis = SystemClock.elapsedRealtime() - lastRead;
            result.capturing = result.sinceLastReadMillis < readGapToleranceMillis;
        }

        // Note that we may not run this for quite a while, if audioReader decides to read a lot of audio!
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                result.trackPoints = track.size();
                final AudioMemory.Stats stats = audioMemory.getStats(FILL_RATE);
                final float bytesToSeconds = getBytesToSeconds();
                result.memorized = (stats.overwriting ? stats.total : stats.filled + stats.estimation) * bytesToSeconds;
                result.totalMemory = stats.total * bytesToSeconds;
                result.intervalExceedsMemory = result.autoSaveEnabled && result.totalMemory > 0
                        && result.autoSaveIntervalMinutes * 60f > result.totalMemory;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stateCallback.state(result);
                    }
                });
            }
        });
    }

    public float getBytesToSeconds() {
        return 1f / FILL_RATE;
    }

    private void recordError(String message) {
        lastError = message;
        lastErrorMillis = System.currentTimeMillis();
        logEvent(message);
    }

    /** Clears the sticky error shown in the UI, so the user can see whether it comes back. */
    public void clearLastError() {
        lastError = null;
        lastErrorMillis = 0;
    }

    private void logEvent(String text) {
        synchronized (events) {
            events.addLast(System.currentTimeMillis() + "\t" + text);
            while(events.size() > EVENT_LOG_SIZE) events.removeFirst();
        }
        Log.d(TAG, "EVENT " + text);
    }

    /** The most recent events, oldest first, each as "wallClockMillis\ttext". */
    public List<String> getEvents() {
        synchronized (events) {
            return new ArrayList<String>(events);
        }
    }

    class BackgroundRecorderBinder extends Binder {
        public SaidItService getService() {
            return SaidItService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(), foregroundServiceTypes());
        } catch (Exception e) {
            // Android 14 refuses a microphone foreground service started while the app is not in
            // use, which is exactly what happens on a start from the background, such as at boot.
            Log.e(TAG, "Can't go foreground", e);
            recordError(getString(R.string.error_cant_go_foreground));
        }
        return START_STICKY;
    }

    /**
     * Android 14 checks a foreground service against the permissions its declared types need, and
     * throws if one is missing. So location is only claimed while it is actually being logged and
     * the permission is actually held.
     */
    private int foregroundServiceTypes() {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if(gpsEnabled && hasLocationPermission()) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        // The camera type is only ever claimed while the permission for it is held, or Android 14
        // refuses to go foreground at all, exactly like location above.
        if(cameraEnabled && cameraCapturer != null && cameraCapturer.hasCameraPermission()) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        if(screenshotEnabled) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        }
        return types;
    }

    /** Re-declares the service's types, which is how location access is gained or given up. */
    private void refreshForegroundType() {
        if(state != STATE_LISTENING) return;
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(), foregroundServiceTypes());
        } catch (Exception e) {
            Log.e(TAG, "Can't change the foreground service type", e);
            recordError(getString(R.string.error_cant_go_foreground));
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if(notificationManager != null) notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, SaidItActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        final int title;
        if(state != STATE_LISTENING) {
            title = R.string.notification_idle;
        } else if(micSilenced || micBlocked) {
            title = R.string.notification_mic_busy;
        } else {
            title = R.string.notification_listening;
        }

        String detail;
        if(state == STATE_READY) {
            detail = getString(R.string.notification_idle_detail);
        } else if(micSilenced || micBlocked) {
            detail = getString(R.string.notification_mic_busy_detail);
        } else if(isAutoSaveEnabled()) {
            detail = getResources().getQuantityString(R.plurals.notification_auto_save_on,
                    getAutoSaveIntervalMinutes(), getAutoSaveIntervalMinutes());
        } else {
            detail = getString(R.string.notification_auto_save_off);
        }
        if(isLowPowerEnabled()) {
            detail = detail + " " + getString(R.string.notification_low_power_suffix);
        }
        if(gpsEnabled) {
            detail = detail + " " + getString(R.string.notification_gps_suffix);
        }
        if(cameraEnabled) {
            detail = detail + " " + getString(R.string.notification_camera_suffix);
        }
        if(screenshotEnabled) {
            detail = detail + " " + getString(R.string.notification_screenshot_suffix);
        }
        if(uploadEnabled) {
            detail = detail + " " + getString(R.string.notification_upload_suffix);
        }

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(title))
                .setContentText(detail)
                .setSmallIcon(state == STATE_READY ? R.drawable.ic_stat_notify_recorded : R.drawable.ic_stat_notify_recording)
                .setTicker(getString(title))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private final Runnable notificationUpdater = new Runnable() {
        @Override
        public void run() {
            updateNotification();
        }
    };

    /** Refreshes the ongoing notification so it always shows the real state. */
    private void updateNotification() {
        if(state == STATE_READY) return; // the foreground notification is gone already
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if(notificationManager != null) {
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification());
        }
    }

}
