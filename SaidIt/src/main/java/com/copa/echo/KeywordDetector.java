package com.copa.echo;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Listens for a spoken word in the audio Echo is already capturing and reports it the moment it
 * is recognised.
 *
 * The audio is the recorder's own PCM rather than a second microphone stream: Android hands the
 * input to one app at a time, and a recogniser living in another app would take the microphone
 * away from the very recording this is meant to annotate. Feeding it what has already been read
 * also means the detector keeps working with the screen off, for as long as capture does.
 *
 * Recognition is offline, done by Vosk against a model shipped in {@code assets/vosk-model}. That
 * model is tens of megabytes and is not in the repository, so a build without it still runs:
 * {@link #getStatus()} then reports {@link Status#NO_MODEL} and the settings screen says so.
 *
 * Everything heavy happens on a thread of the detector's own. {@link #feed} only copies bytes into
 * a small queue and drops them when recognition falls behind, so the audio thread is never held up
 * by the recogniser.
 */
public class KeywordDetector {

    private static final String TAG = KeywordDetector.class.getSimpleName();

    /** Audio waiting to be recognised, in chunks as they were read. Oldest is dropped when full. */
    private static final int QUEUE_CHUNKS = 24;
    /** Not reported again within this long, so one word does not sound the alert a dozen times. */
    private static final long COOLDOWN_MILLIS = 8000;
    /** Written into the unpacked model directory once it is complete. */
    private static final String READY_MARKER = ".unpacked";
    /**
     * Loudest sample, of the 32767 a 16-bit recording can reach, a stretch of audio may hold and
     * still be treated as silence. About -35 dBFS: quiet enough that a room with nobody talking in
     * it stays under, loud enough that speech from across that room does not.
     */
    private static final int SILENCE_PEAK = 550;

    public enum Status {
        /** Not running. */
        OFF,
        /** The model is being unpacked or loaded. */
        LOADING,
        /** Running and listening. */
        LISTENING,
        /** No speech model was shipped in the assets, so nothing can be recognised. */
        NO_MODEL,
        /** The model is there but could not be loaded. */
        ERROR
    }

    public interface Listener {
        /** A keyword has just been heard. Called on the detector's own thread. */
        void onKeyword(String word);
    }

    private final Context context;
    private final Listener listener;

    private HandlerThread thread;
    private Handler handler;
    /**
     * Everything one run of the detector owns. Changing the words stops one run and starts
     * another, and giving each its own model, recogniser and queue is what keeps the run being
     * torn down from closing the model the new one has just opened.
     */
    private volatile Session session;

    private volatile Status status = Status.OFF;
    private volatile boolean running = false;
    private volatile long lastReportElapsed = 0;

    public KeywordDetector(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isRunning() {
        return running;
    }

    /** True when a speech model was shipped with the app at all. */
    public boolean hasModel() {
        try {
            final String[] names = context.getAssets().list(SaidIt.KEYWORD_MODEL_ASSET);
            return names != null && names.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Starts listening for the given words, separated by commas or spaces, in audio captured at
     * the given rate. Loading the model takes seconds, so this returns straight away and the
     * status moves on by itself.
     */
    public synchronized void start(String words, int sampleRate) {
        if(running) return;
        final Session next = new Session(words, sampleRate);
        if(next.spoken.isEmpty()) return;
        if(!hasModel()) {
            status = Status.NO_MODEL;
            Log.w(TAG, "No speech model in assets/" + SaidIt.KEYWORD_MODEL_ASSET
                    + ", keyword detection stays off");
            return;
        }

        running = true;
        status = Status.LOADING;
        session = next;
        thread = new HandlerThread("keywordDetector");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                next.load();
            }
        });
    }

    public synchronized void stop() {
        if(!running) return;
        running = false;
        status = Status.OFF;
        final Session dying = session;
        session = null;
        if(handler != null && dying != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    dying.close();
                }
            });
        }
        // quitSafely lets the close above run before the thread ends.
        if(thread != null) { thread.quitSafely(); thread = null; }
        handler = null;
    }

    /**
     * Hands over audio just read from the microphone. Copies and returns; when recognition has
     * fallen behind, the oldest chunk waiting is dropped rather than blocking the audio thread.
     *
     * Audio with nothing in it loud enough to be a spoken word is dropped here rather than
     * recognised, which is what keeps listening for a word from costing much over recording alone
     * for most of a day: recognition is the expensive part, and a quiet room never reaches it. The
     * scan that decides is one pass over bytes that were just written, so it costs nothing next to
     * what it saves.
     */
    public void feed(byte[] array, int offset, int count) {
        if(!running || count <= 0) return;
        final Session current = session;
        final Handler handler = this.handler;
        if(current == null || handler == null) return;
        if(isNearSilent(array, offset, count)) return;

        final byte[] copy = new byte[count];
        System.arraycopy(array, offset, copy, 0, count);
        while(!current.queue.offer(copy)) {
            if(current.queue.poll() == null) return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                current.drain();
            }
        });
    }

    /**
     * Whether a stretch of 16-bit audio holds nothing that could be a spoken word. The loudest
     * sample decides rather than an average, so a single short word inside seconds of quiet still
     * counts as speech; the threshold sits well under conversation and over the noise a microphone
     * makes on its own.
     *
     * Every other sample is enough to find a peak in speech, which halves the scan.
     */
    private static boolean isNearSilent(byte[] array, int offset, int count) {
        final int end = offset + count - 1;
        for(int i = offset; i < end; i += 4) {
            final int sample = (short) ((array[i] & 0xff) | (array[i + 1] << 8));
            if(sample > SILENCE_PEAK || sample < -SILENCE_PEAK) return false;
        }
        return true;
    }

    /** One run of the detector: its words, its model and the audio waiting for it. */
    private final class Session {

        /** The words as typed, which is the form the model's own vocabulary uses. */
        final List<String> spoken = new ArrayList<String>();
        /** The same words folded, so every match is a plain comparison. */
        final List<String> folded = new ArrayList<String>();
        final int sampleRate;
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(QUEUE_CHUNKS);

        // Detector thread only.
        private Model model;
        private Recognizer recognizer;
        private boolean closed = false;

        Session(String words, int sampleRate) {
            this.sampleRate = sampleRate;
            if(words == null) return;
            for(String word : words.split("[,;\\s]+")) {
                final String trimmed = word.trim();
                if(trimmed.isEmpty()) continue;
                spoken.add(trimmed.toLowerCase(Locale.getDefault()));
                folded.add(fold(trimmed));
            }
        }

        void load() {
            if(closed) return;
            try {
                final File dir = unpackModel();
                model = new Model(dir.getAbsolutePath());
                recognizer = buildRecognizer(model);
                status = Status.LISTENING;
                Log.d(TAG, "Listening for " + spoken + " at " + sampleRate + " Hz");
            } catch (Throwable t) {
                status = Status.ERROR;
                Log.e(TAG, "Can't load the speech model", t);
            }
        }

        /**
         * A recogniser limited to the words we care about plus a catch-all, which is what makes a
         * small model usable for spotting one word: everything else is free to come out as [unk]
         * rather than as some other word that happens to sound similar. Models that cannot be
         * given a grammar fall back to recognising everything.
         */
        private Recognizer buildRecognizer(Model model) throws IOException {
            final StringBuilder grammar = new StringBuilder("[");
            for(String word : spoken) {
                grammar.append('"').append(word).append("\", ");
            }
            grammar.append("\"[unk]\"]");
            try {
                return new Recognizer(model, sampleRate, grammar.toString());
            } catch (Exception e) {
                Log.w(TAG, "The model won't take a grammar, recognising everything instead: "
                        + e.getMessage());
                return new Recognizer(model, sampleRate);
            }
        }

        void drain() {
            if(closed || recognizer == null) return;
            byte[] chunk;
            while((chunk = queue.poll()) != null) {
                if(closed) return;
                try {
                    if(recognizer.acceptWaveForm(chunk, chunk.length)) {
                        check(recognizer.getResult());
                    } else {
                        check(recognizer.getPartialResult());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Recognition failed: " + e.getMessage());
                    return;
                }
            }
        }

        void close() {
            closed = true;
            queue.clear();
            try { if(recognizer != null) recognizer.close(); } catch (Exception ignore) { }
            try { if(model != null) model.close(); } catch (Exception ignore) { }
            recognizer = null;
            model = null;
        }

        /** Reports the first keyword the recognised text holds, at most once per cooldown. */
        private void check(String json) {
            if(json == null) return;
            final String text = fold(json);
            for(int i = 0; i < folded.size(); ++i) {
                if(!containsWord(text, folded.get(i))) continue;
                final long now = SystemClock.elapsedRealtime();
                if(now - lastReportElapsed < COOLDOWN_MILLIS) return;
                lastReportElapsed = now;
                listener.onKeyword(spoken.get(i));
                return;
            }
        }
    }

    /**
     * Whether the word stands on its own in the text. The text is a JSON document rather than a
     * bare sentence, so its punctuation and quotes count as boundaries just like spaces do; that
     * is exactly what keeps "movil" from matching inside "automovil".
     */
    private static boolean containsWord(String text, String word) {
        int from = 0;
        while(true) {
            final int at = text.indexOf(word, from);
            if(at < 0) return false;
            final boolean startsClean = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            final int after = at + word.length();
            final boolean endsClean = after >= text.length()
                    || !Character.isLetterOrDigit(text.charAt(after));
            if(startsClean && endsClean) return true;
            from = at + 1;
        }
    }

    /** Lower case and without accents, so "Móvil" and "movil" are the same word. */
    private static String fold(String text) {
        final String lower = text.toLowerCase(Locale.getDefault());
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    /**
     * Copies the model out of the assets and onto disk, where the native library can open it.
     * Done once: a marker file written last says the copy finished, so a copy interrupted half
     * way is redone rather than loaded.
     */
    private File unpackModel() throws IOException {
        final File dir = new File(context.getFilesDir(), SaidIt.KEYWORD_MODEL_ASSET);
        final File marker = new File(dir, READY_MARKER);
        if(marker.exists()) return dir;

        deleteTree(dir);
        copyAsset(context.getAssets(), SaidIt.KEYWORD_MODEL_ASSET, dir);
        new FileOutputStream(marker).close();
        Log.d(TAG, "Unpacked the speech model into " + dir.getAbsolutePath());
        return dir;
    }

    private static void copyAsset(AssetManager assets, String path, File target) throws IOException {
        final String[] children = assets.list(path);
        if(children != null && children.length > 0) {
            if(!target.exists() && !target.mkdirs()) {
                throw new IOException("Can't create " + target.getAbsolutePath());
            }
            for(String child : children) {
                copyAsset(assets, path + "/" + child, new File(target, child));
            }
            return;
        }

        final InputStream in = assets.open(path);
        try {
            final OutputStream out = new FileOutputStream(target);
            try {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void deleteTree(File file) {
        final File[] children = file.listFiles();
        if(children != null) {
            for(File child : children) deleteTree(child);
        }
        file.delete();
    }
}
