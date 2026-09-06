package com.copa.echo;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.http.HttpEngine;
import android.net.http.HttpException;
import android.net.http.UploadDataProvider;
import android.net.http.UploadDataSink;
import android.net.http.UrlRequest;
import android.net.http.UrlResponseInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Sends the traces sitting on disk to a server, oldest first, and deletes each one only once the
 * server has taken it.
 *
 * The transport is HTTP/3 (QUIC) where the platform offers it. Android 14 ships an HttpEngine that
 * speaks HTTP/3 with no extra library and no Google Play Services, so that is used on API 34 and
 * up; older versions fall back to a plain HTTPS POST, which negotiates the best the server and the
 * platform share (HTTP/2 at best, never HTTP/3). Either way one file is sent at a time and only a
 * 2xx response is allowed to delete it, so a trace is never lost to a failed upload.
 */
public class TraceUploader {

    private static final String TAG = TraceUploader.class.getSimpleName();

    /** A pass runs at least this often while uploading is on, to retry and to catch new files. */
    private static final long PASS_INTERVAL_MILLIS = 30000;
    /** A file younger than this is left alone, in case it is still being written. */
    private static final long MIN_AGE_MILLIS = 15000;
    /** How long to wait on a single upload before giving up on it and retrying later. */
    private static final long REQUEST_TIMEOUT_MILLIS = 60000;

    private final Context context;
    private final HandlerThread thread;
    private final Handler handler;
    private final Executor httpExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean enabled = false;
    private volatile String url = "";
    /** Guards a pass from being run on top of itself. Uploader thread only. */
    private boolean passRunning = false;

    private HttpEngine httpEngine; // built lazily on API 34+, uploader thread only

    public TraceUploader(Context context) {
        this.context = context.getApplicationContext();
        thread = new HandlerThread("traceUploader");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Turns uploading on or off and sets the destination. Safe to call from any thread. */
    public void configure(boolean enabled, String url) {
        this.url = url == null ? "" : url.trim();
        final boolean was = this.enabled;
        this.enabled = enabled && !this.url.isEmpty();
        handler.removeCallbacks(passLoop);
        if(this.enabled) {
            handler.post(passLoop);
        } else if(was) {
            Log.d(TAG, "Uploading off");
        }
    }

    /** Asks for a pass now, e.g. right after a save wrote a new trace. */
    public void kick() {
        if(!enabled) return;
        handler.post(passLoop);
    }

    public void shutdown() {
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private final Runnable passLoop = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(passLoop);
            if(enabled) {
                runPass();
                handler.postDelayed(passLoop, PASS_INTERVAL_MILLIS);
            }
        }
    };

    private void runPass() {
        if(passRunning || !enabled) return;
        if(!hasNetwork()) return;
        passRunning = true;
        try {
            final List<File> files = uploadable();
            for(File file : files) {
                if(!enabled) break;
                if(!hasNetwork()) break;
                final boolean ok = upload(file);
                if(ok) {
                    if(file.delete()) {
                        Log.d(TAG, "Sent and deleted " + file.getName());
                    } else {
                        Log.w(TAG, "Sent but could not delete " + file.getName());
                    }
                } else {
                    // Leave this file and everything after it for the next pass, so order holds
                    // and nothing is dropped over a transient failure.
                    Log.d(TAG, "Upload of " + file.getName() + " failed, will retry");
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Upload pass failed: " + e.getMessage());
        } finally {
            passRunning = false;
        }
    }

    /** Every trace worth sending, oldest first: audio, tracks and the visual captures. */
    private List<File> uploadable() {
        final List<File> out = new ArrayList<File>();
        final long now = System.currentTimeMillis();
        for(File dir : Storage.readable(context)) {
            final File[] files = dir.listFiles();
            if(files == null) continue;
            for(File file : files) {
                if(!file.isFile()) continue;
                final String name = file.getName().toLowerCase(Locale.US);
                if(!(name.endsWith(".wav") || name.endsWith(".gpx")
                        || name.endsWith(".jpg") || name.endsWith(".png")
                        || name.endsWith(".mp4") || name.endsWith(".txt")
                        || name.endsWith(".json"))) continue;
                if(now - file.lastModified() < MIN_AGE_MILLIS) continue;
                out.add(file);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                final long ta = startMillis(a);
                final long tb = startMillis(b);
                final int byTime = Long.compare(ta, tb);
                return byTime != 0 ? byTime : a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    /** The wall clock a file belongs to: its name's leading timestamp, or its mtime otherwise. */
    private static long startMillis(File file) {
        final Long named = Traces.parseLeadingTimestamp(file.getName());
        return named != null ? named : file.lastModified();
    }

    private boolean hasNetwork() {
        final ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if(cm == null) return false;
        final Network network = cm.getActiveNetwork();
        if(network == null) return false;
        final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private boolean upload(File file) {
        if(Build.VERSION.SDK_INT >= 34) {
            return uploadWithHttpEngine(file);
        }
        return uploadWithUrlConnection(file);
    }

    private static String mimeOf(String name) {
        name = name.toLowerCase(Locale.US);
        if(name.endsWith(".wav")) return "audio/wav";
        if(name.endsWith(".gpx")) return "application/gpx+xml";
        if(name.endsWith(".jpg")) return "image/jpeg";
        if(name.endsWith(".png")) return "image/png";
        if(name.endsWith(".mp4")) return "video/mp4";
        if(name.endsWith(".txt")) return "text/plain; charset=utf-8";
        if(name.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- HTTP/3 via HttpEngine

    private boolean uploadWithHttpEngine(final File file) {
        try {
            if(httpEngine == null) {
                httpEngine = new HttpEngine.Builder(context).setEnableQuic(true).build();
            }
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = { false };

            final UrlRequest.Callback callback = new UrlRequest.Callback() {
                private final ByteBuffer sink = ByteBuffer.allocateDirect(16 * 1024);

                @Override
                public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
                    request.followRedirect();
                }

                @Override
                public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
                    final int status = info.getHttpStatusCode();
                    ok[0] = status >= 200 && status < 300;
                    sink.clear();
                    request.read(sink);
                }

                @Override
                public void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) {
                    sink.clear();
                    request.read(sink);
                }

                @Override
                public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
                    done.countDown();
                }

                @Override
                public void onFailed(UrlRequest request, UrlResponseInfo info, HttpException error) {
                    Log.w(TAG, "HttpEngine upload failed: " + error.getMessage());
                    ok[0] = false;
                    done.countDown();
                }

                @Override
                public void onCanceled(UrlRequest request, UrlResponseInfo info) {
                    done.countDown();
                }
            };

            final UrlRequest.Builder builder = httpEngine.newUrlRequestBuilder(url, httpExecutor, callback);
            builder.setHttpMethod("POST");
            builder.addHeader("Content-Type", mimeOf(file.getName()));
            builder.addHeader("X-Filename", file.getName());
            builder.setUploadDataProvider(new FileUploadProvider(file), httpExecutor);
            final UrlRequest request = builder.build();
            request.start();

            if(!done.await(REQUEST_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                request.cancel();
                return false;
            }
            return ok[0];
        } catch (Throwable t) {
            Log.w(TAG, "HttpEngine upload error: " + t.getMessage());
            return false;
        }
    }

    /** Feeds a file to an HttpEngine request without ever holding all of it in memory. */
    private static final class FileUploadProvider extends UploadDataProvider {
        private final File file;
        private final long length;
        private FileChannel channel;

        FileUploadProvider(File file) {
            this.file = file;
            this.length = file.length();
        }

        @Override
        public long getLength() {
            return length;
        }

        @Override
        public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer) throws IOException {
            if(channel == null) channel = new FileInputStream(file).getChannel();
            final int read = channel.read(byteBuffer);
            uploadDataSink.onReadSucceeded(read == -1);
        }

        @Override
        public void rewind(UploadDataSink uploadDataSink) throws IOException {
            if(channel != null) channel.position(0);
            uploadDataSink.onRewindSucceeded();
        }

        @Override
        public void close() throws IOException {
            if(channel != null) channel.close();
        }
    }

    // ---------------------------------------------------------------- HTTPS fallback

    private boolean uploadWithUrlConnection(File file) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setFixedLengthStreamingMode(file.length());
            connection.setRequestProperty("Content-Type", mimeOf(file.getName()));
            connection.setRequestProperty("X-Filename", file.getName());
            connection.setConnectTimeout(20000);
            connection.setReadTimeout((int) REQUEST_TIMEOUT_MILLIS);

            final OutputStream out = connection.getOutputStream();
            final FileInputStream in = new FileInputStream(file);
            try {
                final byte[] buffer = new byte[16 * 1024];
                int n;
                while((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.flush();
            } finally {
                in.close();
                out.close();
            }
            final int status = connection.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            Log.w(TAG, "HTTPS upload failed: " + e.getMessage());
            return false;
        } finally {
            if(connection != null) connection.disconnect();
        }
    }
}
