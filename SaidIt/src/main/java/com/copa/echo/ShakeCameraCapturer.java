package com.copa.echo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Collections;

/**
 * Records a short clip from each camera when the phone is shaken hard and then held still,
 * written beside the audio traces as .mp4 files.
 *
 * The gesture is deliberately a two-part one, because the point of it is to tell "I want this
 * recorded" apart from everything a phone does in a pocket all day. An angle cannot do that: a
 * phone carried upright in a pocket sits past any threshold for hours and films the inside of the
 * pocket. So a capture needs both halves, in order:
 *
 * <ol>
 *   <li>{@link #SHAKE_PEAKS} swings past the shake threshold within {@link #SHAKE_WINDOW_MILLIS}.
 *       Counting oscillations rather than a single peak is what separates shaking from a knock,
 *       a dropped phone or a footstep.</li>
 *   <li>then the phone held still — barely moving at all — for {@link #STILL_MILLIS}, within
 *       {@link #ARMED_TIMEOUT_MILLIS} of the shake. Whatever is worth filming is what the phone
 *       is pointed at once it stops moving, and walking never satisfies this.</li>
 * </ol>
 *
 * The accelerometer is read on a background thread of its own, and the cameras are driven with
 * Camera2 with no preview at all: a foreground service with the {@code camera} type is what lets
 * this happen while the screen is off or another app is in front.
 *
 * The clips carry no audio track: the microphone belongs to the continuous recording, and asking
 * MediaRecorder for it would take it away.
 */
public class ShakeCameraCapturer implements SensorEventListener {

    private static final String TAG = ShakeCameraCapturer.class.getSimpleName();

    /**
     * How far from rest, in m/s², a swing has to reach to count as part of a shake, from the
     * gentlest gesture that still works to one nothing but deliberate shaking reaches. A phone
     * being walked with peaks at a few m/s²; shaking it on purpose passes 20 easily.
     */
    private static final float[] SHAKE_THRESHOLDS = { 10f, 15f, 22f };
    /** Swings needed, so a single knock is not a shake. */
    private static final int SHAKE_PEAKS = 4;
    /** They all have to happen this close together. */
    private static final long SHAKE_WINDOW_MILLIS = 1500;
    /** A shake not followed by stillness this soon is forgotten. */
    private static final long ARMED_TIMEOUT_MILLIS = 10000;
    /** Movement below this, in m/s², counts as the phone being held still. */
    private static final float STILL_DEVIATION = 0.8f;
    /** How long it has to stay that still before the clip is recorded. */
    private static final long STILL_MILLIS = 1000;

    /**
     * How often the accelerometer is sampled. A shake is a handful of swings a second, so sampling
     * it as slowly as the UI rate would miss half of them.
     */
    private static final int SAMPLING_PERIOD_MICROS = 20000;
    /**
     * How long the sensor may hold samples back before delivering them. Fifty samples a second
     * delivered one at a time is fifty wakeups a second; letting the hardware batch them costs a
     * handful. Timing comes off each event's own timestamp, so a batch is read exactly as if it
     * had arrived live — what the batch does delay is the phone's answer to the gesture, and the
     * tap that says the phone was seen going still is no use a second after the fact, so this
     * stays short enough to keep the cues in the order they describe.
     */
    private static final int MAX_REPORT_LATENCY_MICROS = 200000;

    /** Clips are recorded at most this wide, so a video trace stays small. */
    private static final int MAX_VIDEO_WIDTH = 1280;
    private static final int FRAME_RATE = 24;
    /** A camera that neither records nor errors is abandoned this long after the clip should end. */
    private static final long CAMERA_TIMEOUT_MILLIS = 6000;
    /** Shortest clip an encoder can be asked for and still be expected to write a playable file. */
    private static final long MIN_CLIP_MILLIS = 1000;

    /**
     * The three things the phone says with its vibrator, as waveforms: milliseconds off, on, off,
     * on..., with an amplitude of the 255 a motor can be driven at for each. They mark the start
     * and the end of a recording, and are meant to be told apart through a pocket without
     * looking, so they differ in shape and not only in length: two taps for "recording now", one
     * long buzz for "saved", and two long buzzes for "nothing came of it", which is the one that
     * has to be unmistakable.
     */
    private static final long[] START_TIMINGS = { 0, 35, 70, 35 };
    private static final int[] START_AMPLITUDES = { 0, 230, 0, 230 };
    private static final long[] SAVED_TIMINGS = { 0, 180 };
    private static final int[] SAVED_AMPLITUDES = { 0, 255 };
    private static final long[] FAILED_TIMINGS = { 0, 260, 160, 260 };
    private static final int[] FAILED_AMPLITUDES = { 0, 255, 0, 255 };
    /** How long after a buzz ends the accelerometer is believed again. */
    private static final long BUZZ_SETTLE_MILLIS = 250;

    /** What the capturer needs from the service: where to write, and a nudge once a file lands. */
    public interface Listener {
        /** The directory traces are written to. Called off the main thread; may touch the disk. */
        File tracesDir();
        /** A clip has just been written. */
        void onCaptured(File file);
    }

    private final Context context;
    private final Listener listener;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private HandlerThread sensorThread;
    private Handler sensorHandler;

    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    /** Resolved once capture starts; null on a phone with nothing to vibrate. */
    private volatile Vibrator vibrator;
    /**
     * elapsedRealtime the accelerometer is worth reading again from. The motor shakes the very
     * sensor the gesture is read off, so a cue of our own arrives as movement: without this, the
     * buzzes that mark a recording would be counted as swings of the next shake.
     */
    private volatile long ignoreSamplesUntil = 0;

    private volatile boolean running = false;
    /** How hard the phone has to be shaken, as an index into {@link #SHAKE_THRESHOLDS}. */
    private volatile int shakeLevel = SaidIt.SHAKE_LEVEL_DEFAULT;

    /** Shortest time between two clips of the same camera, kept apart back from front. */
    private volatile long backMinMillis = 8000;
    private volatile long frontMinMillis = 8000;
    /** The front camera is the one people want off, so it can be left out of every trigger. */
    private volatile boolean frontEnabled = true;
    /** How long each clip runs for. */
    private volatile long clipMillis = SaidIt.CLIP_SECONDS_DEFAULT * 1000L;
    /** elapsedRealtime of the last clip taken with each camera, so each is rate limited alone. */
    private volatile long lastBackElapsed = 0;
    private volatile long lastFrontElapsed = 0;

    // Gesture state, sensor thread only.
    /** When each of the last few swings happened, oldest at peakIndex. */
    private final long[] peakAt = new long[SHAKE_PEAKS];
    private int peakIndex = 0;
    private int peaksSeen = 0;
    /** True while movement is still above the threshold of the swing being counted. */
    private boolean inPeak = false;
    /** elapsedRealtime of the shake now waiting for stillness, or 0 when none is. */
    private long armedAt = 0;
    /** elapsedRealtime the phone went still at while armed, or 0 while it is still moving. */
    private long stillSince = 0;

    /** Guards against a second capture starting while one is still in flight. */
    private volatile boolean capturing = false;

    public ShakeCameraCapturer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean hasCameraPermission() {
        return context.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Everything the trigger is shaped by, applied live: the settings screen changes these while
     * capture is already running.
     */
    public void configure(int shakeLevel, long backMillis, long frontMillis,
                          boolean frontEnabled, int clipSeconds) {
        this.shakeLevel = Math.max(0, Math.min(SHAKE_THRESHOLDS.length - 1, shakeLevel));
        this.backMinMillis = Math.max(0, backMillis);
        this.frontMinMillis = Math.max(0, frontMillis);
        this.frontEnabled = frontEnabled;
        this.clipMillis = Math.max(MIN_CLIP_MILLIS, clipSeconds * 1000L);
    }

    public synchronized void start() {
        if(running) return;
        if(!hasCameraPermission()) {
            Log.w(TAG, "No camera permission, shake capture stays off");
            return;
        }
        sensorManager = context.getSystemService(SensorManager.class);
        cameraManager = context.getSystemService(CameraManager.class);
        if(sensorManager == null || cameraManager == null) return;

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if(accelerometer == null) {
            Log.w(TAG, "No accelerometer, shake capture unavailable");
            return;
        }

        cameraThread = new HandlerThread("shakeCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        sensorThread = new HandlerThread("shakeSensor");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        vibrator = resolveVibrator();
        ignoreSamplesUntil = 0;
        forget();
        running = true;
        sensorManager.registerListener(this, accelerometer, SAMPLING_PERIOD_MICROS,
                MAX_REPORT_LATENCY_MICROS, sensorHandler);
        Log.d(TAG, "Shake capture on, threshold " + SHAKE_THRESHOLDS[shakeLevel]
                + " m/s2, clips of " + clipMillis + " ms");
    }

    public synchronized void stop() {
        if(!running) return;
        running = false;
        if(sensorManager != null) sensorManager.unregisterListener(this);
        if(sensorThread != null) { sensorThread.quitSafely(); sensorThread = null; }
        if(cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; }
        sensorHandler = null;
        cameraHandler = null;
        Log.d(TAG, "Shake capture off");
    }

    /** Drops any half-finished gesture. Sensor thread, or before it starts. */
    private void forget() {
        peakIndex = 0;
        peaksSeen = 0;
        inPeak = false;
        armedAt = 0;
        stillSince = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(!running) return;
        // Our own vibration reads as movement, and dropping those samples is what keeps a buzz
        // from counting towards the next shake or from disturbing a gesture already under way.
        if(SystemClock.elapsedRealtime() < ignoreSamplesUntil) return;

        final float x = event.values[0];
        final float y = event.values[1];
        final float z = event.values[2];

        // How hard the phone is being moved, gravity taken out: the accelerometer reads one g at
        // rest whichever way up it is, so the distance of the magnitude from one g is the movement
        // and needs no orientation of its own. A phone at rest reads near zero here, one being
        // carried a few m/s², one being shaken tens.
        final double magnitude = Math.sqrt(x * x + y * y + z * z);
        final float deviation = (float) Math.abs(magnitude - SensorManager.STANDARD_GRAVITY);
        // When the sensor batches, every event of a batch arrives at once: the gesture has to be
        // read off when each sample was taken, not off when it turned up.
        final long now = event.timestamp / 1000000L;

        countSwings(deviation, now);
        watchForStillness(deviation, now);
    }

    /**
     * Counts a swing each time the movement rises past the threshold, and arms the trigger once
     * enough of them have happened close together. Requiring the movement to fall well below the
     * threshold in between is what makes one swing one count rather than a dozen.
     */
    private void countSwings(float deviation, long now) {
        final float threshold = SHAKE_THRESHOLDS[shakeLevel];
        if(!inPeak && deviation >= threshold) {
            inPeak = true;
            peakAt[peakIndex] = now;
            peakIndex = (peakIndex + 1) % SHAKE_PEAKS;
            ++peaksSeen;
            // peakAt[peakIndex] is now the oldest of the last SHAKE_PEAKS swings.
            if(peaksSeen >= SHAKE_PEAKS && now - peakAt[peakIndex] <= SHAKE_WINDOW_MILLIS) {
                // Shaking on and on keeps refreshing this, so the wait for stillness starts from
                // the last swing rather than the first.
                armedAt = now;
                stillSince = 0;
            }
        } else if(inPeak && deviation < threshold * 0.5f) {
            inPeak = false;
        }
    }

    /** Fires the capture once an armed phone has been held still long enough. */
    private void watchForStillness(float deviation, long now) {
        if(armedAt == 0) return;
        if(now - armedAt > ARMED_TIMEOUT_MILLIS) {
            // Shaken and then carried around: whatever it was about, it is not this moment.
            forget();
            return;
        }
        if(deviation >= STILL_DEVIATION) {
            stillSince = 0;
            return;
        }
        if(stillSince == 0) {
            stillSince = now;
            return;
        }
        if(now - stillSince < STILL_MILLIS) return;

        forget();
        maybeCapture();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void maybeCapture() {
        if(capturing) return;
        // A clip only fires for a camera whose own minimum has passed; that per-camera gap is the
        // whole rate limit, so if neither is due there is nothing to do.
        final long now = SystemClock.elapsedRealtime();
        if(!backDue(now) && !frontDue(now)) return;
        capturing = true;
        final Handler handler = cameraHandler;
        if(handler == null) { capturing = false; return; }
        handler.post(new Runnable() {
            @Override
            public void run() {
                captureDueCameras();
            }
        });
    }

    private boolean backDue(long now) {
        return now - lastBackElapsed >= backMinMillis;
    }

    private boolean frontDue(long now) {
        return frontEnabled && now - lastFrontElapsed >= frontMinMillis;
    }

    /** Camera thread: records whichever of the two cameras are due, one after another. */
    private void captureDueCameras() {
        final File dir = listener.tracesDir();
        final String base = SaidItService.timestampName(System.currentTimeMillis());
        final long now = SystemClock.elapsedRealtime();
        final boolean backDue = backDue(now);
        final boolean frontDue = frontDue(now);

        final ArrayDeque<String[]> queue = new ArrayDeque<String[]>();
        try {
            String back = null, front = null;
            for(String id : cameraManager.getCameraIdList()) {
                final Integer facing = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if(facing == null) continue;
                if(facing == CameraCharacteristics.LENS_FACING_BACK && back == null) back = id;
                else if(facing == CameraCharacteristics.LENS_FACING_FRONT && front == null) front = id;
            }
            // The clock is stamped the moment a clip is committed to, not when it lands, so a
            // second gesture cannot slip another clip of one camera inside its minimum.
            if(back != null && backDue) { queue.add(new String[]{ back, "back" }); lastBackElapsed = now; }
            if(front != null && frontDue) { queue.add(new String[]{ front, "front" }); lastFrontElapsed = now; }
        } catch (Exception e) {
            Log.w(TAG, "Can't list cameras: " + e.getMessage());
        }

        if(queue.isEmpty()) { capturing = false; return; }
        // The gesture is answered as a whole, not once per camera.
        captureNext(queue, dir, base, new Round());
    }

    /** What one gesture is doing across however many cameras answer it. Camera thread only. */
    private static final class Round {
        boolean started = false;
        int written = 0;
    }

    private void captureNext(final ArrayDeque<String[]> queue, final File dir, final String base,
                             final Round round) {
        final String[] next = queue.poll();
        if(next == null) {
            // Every camera has had its turn, so now there is an answer worth giving: a clip on
            // disk, or nothing at all. Two cameras still say it once.
            if(round.written > 0) {
                buzz(SAVED_TIMINGS, SAVED_AMPLITUDES);
            } else {
                buzz(FAILED_TIMINGS, FAILED_AMPLITUDES);
            }
            capturing = false;
            return;
        }
        final Runnable onDone = new Runnable() {
            @Override
            public void run() {
                captureNext(queue, dir, base, round);
            }
        };
        try {
            captureOne(next[0], next[1], dir, base, round, onDone);
        } catch (Throwable t) {
            Log.w(TAG, "Clip from the " + next[1] + " camera failed: " + t.getMessage());
            onDone.run();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void captureOne(final String cameraId, final String facing, final File dir,
                            final String base, final Round round, final Runnable onDone)
            throws CameraAccessException {
        final CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        final Size size = chooseSize(characteristics);
        final File file = SaidItService.uniqueFile(dir, base + "_" + facing, ".mp4");
        final long clip = clipMillis;
        // Held for as long as this clip lasts: stop() drops the field, and every callback below
        // runs on this thread and would fail on a null handler halfway through a recording.
        final Handler handler = cameraHandler;
        if(handler == null) { capturing = false; return; }

        final MediaRecorder recorder;
        try {
            recorder = buildRecorder(file, size,
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));
        } catch (Exception e) {
            Log.w(TAG, "Can't prepare the recorder for " + file.getName() + ": " + e.getMessage());
            file.delete();
            onDone.run();
            return;
        }
        final Surface surface = recorder.getSurface();

        // Everything below runs on the camera thread. A single latch of "done" makes sure the
        // camera, session and recorder are released exactly once, whether by the clip ending, an
        // error or the timeout below.
        final boolean[] finished = { false };
        final boolean[] recording = { false };
        final CameraDevice[] deviceHolder = { null };
        final CameraCaptureSession[] sessionHolder = { null };

        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                if(finished[0]) return;
                finished[0] = true;
                handler.removeCallbacks(this);
                boolean written = false;
                try {
                    if(sessionHolder[0] != null) sessionHolder[0].stopRepeating();
                } catch (Exception ignore) { }
                try {
                    // stop() throws when the encoder never got a frame, and the file it leaves
                    // behind then has no moov atom and will not play.
                    if(recording[0]) { recorder.stop(); written = true; }
                } catch (Exception e) {
                    Log.w(TAG, "Nothing recorded into " + file.getName() + ": " + e.getMessage());
                }
                try { recorder.reset(); recorder.release(); } catch (Exception ignore) { }
                try { surface.release(); } catch (Exception ignore) { }
                try { if(sessionHolder[0] != null) sessionHolder[0].close(); } catch (Exception ignore) { }
                try { if(deviceHolder[0] != null) deviceHolder[0].close(); } catch (Exception ignore) { }

                if(written && file.length() > 0) {
                    Log.d(TAG, "Saved " + file.getName());
                    ++round.written;
                    listener.onCaptured(file);
                } else {
                    file.delete();
                }
                onDone.run();
            }
        };

        // A camera that goes quiet must not wedge the queue forever.
        handler.postDelayed(finish, clip + CAMERA_TIMEOUT_MILLIS);

        cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice device) {
                deviceHolder[0] = device;
                try {
                    device.createCaptureSession(Collections.singletonList(surface),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(@NonNull CameraCaptureSession session) {
                                    sessionHolder[0] = session;
                                    try {
                                        final CaptureRequest.Builder request = device
                                                .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                        request.addTarget(surface);
                                        request.set(CaptureRequest.CONTROL_MODE,
                                                CaptureRequest.CONTROL_MODE_AUTO);
                                        session.setRepeatingRequest(request.build(), null, handler);
                                        recorder.start();
                                        recording[0] = true;
                                        // Frames are being written. Said once for the gesture,
                                        // whichever camera got there first.
                                        if(!round.started) {
                                            round.started = true;
                                            buzz(START_TIMINGS, START_AMPLITUDES);
                                        }
                                        handler.postDelayed(finish, clip);
                                    } catch (Exception e) {
                                        Log.w(TAG, "Can't start recording: " + e.getMessage());
                                        finish.run();
                                    }
                                }

                                @Override
                                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                    Log.w(TAG, "Capture session config failed for camera " + cameraId);
                                    finish.run();
                                }
                            }, handler);
                } catch (Exception e) {
                    Log.w(TAG, "Can't create session: " + e.getMessage());
                    finish.run();
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice device) {
                deviceHolder[0] = device;
                finish.run();
            }

            @Override
            public void onError(@NonNull CameraDevice device, int error) {
                deviceHolder[0] = device;
                Log.w(TAG, "Camera " + cameraId + " error " + error);
                finish.run();
            }
        }, handler);
    }

    /**
     * Says one of the three things above through the vibrator. This is the whole of the feedback
     * the gesture gives: there is no preview, no shutter sound and, with the screen off, nothing
     * to see, so a shake that was understood and one that was not feel identical without it.
     *
     * The amplitudes are asked for outright rather than borrowed from the system's own haptics,
     * which are tuned to be barely there — this has to be felt through a pocket, by someone who
     * is not looking at the phone and may not be holding it in a hand at all.
     *
     * Camera thread. Vibrating is asynchronous, so nothing waits for it.
     */
    private void buzz(long[] timings, int[] amplitudes) {
        // Deafen the gesture for as long as the motor will be running, plus a moment for the
        // phone to stop ringing. Done first, and whether or not the vibration itself works out.
        long duration = 0;
        for(long segment : timings) duration += segment;
        ignoreSamplesUntil = SystemClock.elapsedRealtime() + duration + BUZZ_SETTLE_MILLIS;

        final Vibrator vibrator = this.vibrator;
        if(vibrator == null || !vibrator.hasVibrator()) return;
        try {
            // Without amplitude control every segment comes out at full strength, which keeps the
            // shapes distinguishable even where the strengths are not.
            vibrator.vibrate(vibrator.hasAmplitudeControl()
                    ? VibrationEffect.createWaveform(timings, amplitudes, -1)
                    : VibrationEffect.createWaveform(timings, -1));
        } catch (Exception e) {
            // A phone that will not vibrate is not a reason to lose the clip.
            Log.w(TAG, "Can't vibrate: " + e.getMessage());
        }
    }

    private Vibrator resolveVibrator() {
        try {
            if(android.os.Build.VERSION.SDK_INT >= 31) {
                final VibratorManager manager = context.getSystemService(VibratorManager.class);
                return manager == null ? null : manager.getDefaultVibrator();
            }
            return context.getSystemService(Vibrator.class);
        } catch (Exception e) {
            Log.w(TAG, "No vibrator: " + e.getMessage());
            return null;
        }
    }

    /** A recorder with no audio source at all, ready for the camera to draw into. */
    @SuppressWarnings("deprecation")
    private MediaRecorder buildRecorder(File file, Size size, Integer orientation) throws Exception {
        // The context-taking constructor only exists from API 31, and Echo still runs on 30.
        final MediaRecorder recorder = android.os.Build.VERSION.SDK_INT >= 31
                ? new MediaRecorder(context) : new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(file);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(size.getWidth(), size.getHeight());
        recorder.setVideoFrameRate(FRAME_RATE);
        recorder.setVideoEncodingBitRate(bitRateFor(size));
        if(orientation != null) recorder.setOrientationHint(orientation);
        recorder.prepare();
        return recorder;
    }

    /** Enough bits for a legible clip of this size and no more. */
    private static int bitRateFor(Size size) {
        final long pixels = (long) size.getWidth() * size.getHeight();
        return (int) Math.max(1000000L, Math.min(8000000L, pixels * FRAME_RATE / 12));
    }

    /** Picks a recording size no wider than {@link #MAX_VIDEO_WIDTH}, or the smallest on offer. */
    private static Size chooseSize(CameraCharacteristics characteristics) {
        try {
            final Size[] sizes = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(MediaRecorder.class);
            if(sizes != null && sizes.length > 0) {
                Size best = null;
                for(Size s : sizes) {
                    if(s.getWidth() <= MAX_VIDEO_WIDTH
                            && (best == null || s.getWidth() > best.getWidth())) best = s;
                }
                if(best != null) return best;
                Size smallest = sizes[0];
                for(Size s : sizes) if(s.getWidth() < smallest.getWidth()) smallest = s;
                return smallest;
            }
        } catch (Exception ignore) { }
        return new Size(640, 480);
    }
}
