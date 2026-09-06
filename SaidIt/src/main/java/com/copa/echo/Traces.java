package com.copa.echo;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads back the traces sitting on disk so the app can show what has actually been kept, and
 * which stretches of time they cover.
 *
 * Echo writes two kinds of trace, audio (.wav) and location (.gpx), and every one of them is its
 * own file: they are listed, opened and deleted independently, even when a recording and a track
 * were written by the same save and so share a name.
 *
 * Every file is named after the wall clock time of its *first* sample or fix (yyyyMMdd_HHmmss,
 * plus a _2, _3... suffix when that second already had a file), so the covered range of a file
 * is [name, name + its length].
 */
public class Traces {

    private static final String TAG = Traces.class.getSimpleName();
    private static final int WAV_HEADER_SIZE = 44;

    /** Enough of the end of a GPX file to hold its last timestamp, whatever trails it. */
    private static final int GPX_TAIL_SIZE = 4096;

    private static final Pattern NAME_PATTERN = Pattern.compile("^(\\d{8}_\\d{6})(?:_\\d+)?$");
    private static final Pattern GPX_TIME_PATTERN = Pattern.compile("<time>([^<]+)</time>");

    /** Consecutive files closer than this are reported as a single uninterrupted range. */
    public static final long DEFAULT_MAX_GAP_MILLIS = 5000;

    /** What a trace is a trace of. */
    public enum Kind { AUDIO, LOCATION, IMAGE, VIDEO, NOTE, SURVEY }

    public static class Entry {
        public File file;
        public Kind kind;
        public long startMillis;
        public long endMillis;
        public float durationSeconds;
        public long sizeBytes;
        /** Audio only, in Hz. */
        public int sampleRate;
        /** True when the start time comes from the file name, false when guessed from its mtime. */
        public boolean exactStart;
    }

    /** An uninterrupted stretch of time covered by one or more consecutive files. */
    public static class Range {
        public long startMillis;
        public long endMillis;
        public int fileCount;
        public long sizeBytes;

        public long durationMillis() {
            return endMillis - startMillis;
        }
    }

    /** Traces found across every given directory, oldest first. */
    public static List<Entry> scanAll(List<File> dirs) {
        final List<Entry> entries = new ArrayList<Entry>();
        for (File dir : dirs) {
            collect(dir, entries);
        }
        sort(entries);
        return entries;
    }

    /** Traces found on disk, oldest first. */
    public static List<Entry> scan(File dir) {
        final List<Entry> entries = new ArrayList<Entry>();
        collect(dir, entries);
        sort(entries);
        return entries;
    }

    private static void collect(File dir, List<Entry> entries) {
        final File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!file.isFile()) continue;
            final String name = file.getName().toLowerCase(Locale.US);
            final Entry entry;
            if (name.endsWith(".wav")) {
                entry = readAudioEntry(file);
            } else if (name.endsWith(".gpx")) {
                entry = readLocationEntry(file);
            } else if (name.endsWith(".jpg") || name.endsWith(".png")) {
                entry = readInstantEntry(file, Kind.IMAGE);
            } else if (name.endsWith(".mp4")) {
                entry = readVideoEntry(file);
            } else if (name.endsWith(".txt")) {
                entry = readInstantEntry(file, Kind.NOTE);
            } else if (name.endsWith(".json")) {
                entry = readInstantEntry(file, Kind.SURVEY);
            } else {
                continue;
            }
            if (entry != null) entries.add(entry);
        }
    }

    private static void sort(List<Entry> entries) {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                final int byTime = Long.compare(a.startMillis, b.startMillis);
                if (byTime != 0) return byTime;
                // Two traces of the same moment are a recording and its track. The screen draws
                // the day newest first, so sorting the track ahead here is what puts the
                // recording above it once the list is reversed.
                return b.kind.compareTo(a.kind);
            }
        });
    }

    private static Entry readAudioEntry(File file) {
        final Entry entry = new Entry();
        entry.file = file;
        entry.kind = Kind.AUDIO;
        entry.sizeBytes = file.length();

        int byteRate = 0;
        long dataBytes = 0;
        try {
            final RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                final byte[] header = new byte[WAV_HEADER_SIZE];
                raf.readFully(header);
                entry.sampleRate = intLE(header, 24);
                byteRate = intLE(header, 28);
                dataBytes = intLE(header, 40) & 0xffffffffL;
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Can't read the wav header of " + file.getName() + ": " + e.getMessage());
            return null;
        }

        if (byteRate <= 0) return null; // not a wav file we wrote

        final long audioBytes = Math.max(0, entry.sizeBytes - WAV_HEADER_SIZE);
        // A file whose writer never got to close keeps a zero length in its header; trust the disk instead.
        if (dataBytes <= 0 || dataBytes > audioBytes) dataBytes = audioBytes;
        entry.durationSeconds = dataBytes / (float) byteRate;

        finishEntry(entry);
        return entry;
    }

    /**
     * A track's length is the time between its first and last fix. Both live in {@code <time>}
     * elements, the first within the opening metadata and the last just before the end, so only
     * the two ends of the file are read: a day of tracks is hundreds of files, and reading every
     * fix of every one of them to draw a list is work nobody asked for.
     */
    private static Entry readLocationEntry(File file) {
        final Entry entry = new Entry();
        entry.file = file;
        entry.kind = Kind.LOCATION;
        entry.sizeBytes = file.length();

        long first = 0;
        long last = 0;
        try {
            final RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                first = firstTimeIn(read(raf, 0, (int) Math.min(GPX_TAIL_SIZE, raf.length())));
                final long tailAt = Math.max(0, raf.length() - GPX_TAIL_SIZE);
                last = lastTimeIn(read(raf, tailAt, (int) Math.min(GPX_TAIL_SIZE, raf.length() - tailAt)));
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Can't read the track " + file.getName() + ": " + e.getMessage());
            return null;
        }

        // A file with neither a timestamp inside nor one in its name cannot be placed in time at
        // all, and this screen is nothing but a timeline.
        if (first == 0 && last == 0 && parseStartFromName(file.getName()) == null) return null;

        if (first > 0) {
            // The name is the time of the audio this track was written beside, and capture starts
            // before the first fix arrives, so the name would place the track earlier than it is.
            entry.startMillis = first;
            entry.exactStart = true;
            if (last >= first) entry.durationSeconds = (last - first) / 1000f;
            entry.endMillis = entry.startMillis + (long) (entry.durationSeconds * 1000);
        } else {
            finishEntry(entry);
        }
        return entry;
    }

    /**
     * A screenshot, a note or a filled-in survey is a single instant, not a stretch: it has no
     * duration, so its start and end are the same moment, taken from the file name or, failing
     * that, its mtime.
     */
    private static Entry readInstantEntry(File file, Kind kind) {
        final Entry entry = new Entry();
        entry.file = file;
        entry.kind = kind;
        entry.sizeBytes = file.length();
        entry.durationSeconds = 0;
        stampFromName(entry);
        entry.endMillis = entry.startMillis;
        return entry;
    }

    /**
     * A clip covers the seconds it was recorded over, and only the file itself knows how many
     * those were. Reading that costs one metadata open per clip, which is why it happens on the
     * scanning thread and not while a list is being drawn.
     */
    private static Entry readVideoEntry(File file) {
        final Entry entry = new Entry();
        entry.file = file;
        entry.kind = Kind.VIDEO;
        entry.sizeBytes = file.length();
        entry.durationSeconds = durationOf(file) / 1000f;
        stampFromName(entry);
        entry.endMillis = entry.startMillis + (long) (entry.durationSeconds * 1000);
        return entry;
    }

    private static long durationOf(File file) {
        final android.media.MediaMetadataRetriever retriever =
                new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            final String millis = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return millis == null ? 0 : Long.parseLong(millis);
        } catch (Exception e) {
            Log.w(TAG, "Can't read the length of " + file.getName() + ": " + e.getMessage());
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignore) { }
        }
    }

    /**
     * Places a trace in time by its name. These names carry a text suffix (_back, _front, _screen,
     * _note, _survey) the audio names never do, so the timestamp is read from the front of the
     * name rather than by matching the whole of it, and falls back to the mtime.
     */
    private static void stampFromName(Entry entry) {
        final Long namedStart = parseLeadingTimestamp(entry.file.getName());
        if (namedStart != null) {
            entry.startMillis = namedStart;
            entry.exactStart = true;
        } else {
            entry.startMillis = entry.file.lastModified();
            entry.exactStart = false;
        }
    }

    private static final Pattern LEADING_TIME_PATTERN = Pattern.compile("^(\\d{8}_\\d{6})");

    static Long parseLeadingTimestamp(String fileName) {
        final Matcher matcher = LEADING_TIME_PATTERN.matcher(fileName);
        if (!matcher.find()) return null;
        try {
            return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(matcher.group(1)).getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    /** Fills in the start and end every kind of trace shares, given its length. */
    private static void finishEntry(Entry entry) {
        final Long namedStart = parseStartFromName(entry.file.getName());
        if (namedStart != null) {
            entry.startMillis = namedStart;
            entry.exactStart = true;
        } else {
            entry.startMillis = entry.file.lastModified() - (long) (entry.durationSeconds * 1000);
            entry.exactStart = false;
        }
        entry.endMillis = entry.startMillis + (long) (entry.durationSeconds * 1000);
    }

    private static String read(RandomAccessFile raf, long at, int count) throws IOException {
        if (count <= 0) return "";
        final byte[] bytes = new byte[count];
        raf.seek(at);
        raf.readFully(bytes);
        return new String(bytes, "UTF-8");
    }

    private static long firstTimeIn(String text) {
        final Matcher matcher = GPX_TIME_PATTERN.matcher(text);
        return matcher.find() ? parseGpxTime(matcher.group(1)) : 0;
    }

    private static long lastTimeIn(String text) {
        final Matcher matcher = GPX_TIME_PATTERN.matcher(text);
        long last = 0;
        while (matcher.find()) {
            final long time = parseGpxTime(matcher.group(1));
            if (time > 0) last = time;
        }
        return last;
    }

    private static long parseGpxTime(String text) {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return format.parse(text).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    static Long parseStartFromName(String fileName) {
        final int dot = fileName.lastIndexOf('.');
        final String base = (dot < 0) ? fileName : fileName.substring(0, dot);
        final Matcher matcher = NAME_PATTERN.matcher(base);
        if (!matcher.matches()) return null;
        try {
            return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(matcher.group(1)).getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    /** Collapses the entries into the stretches of time they cover, oldest first. */
    public static List<Range> ranges(List<Entry> sortedEntries, long maxGapMillis) {
        final List<Range> ranges = new ArrayList<Range>();
        for (Entry entry : sortedEntries) {
            final Range last = ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
            if (last != null && entry.startMillis - last.endMillis <= maxGapMillis) {
                last.endMillis = Math.max(last.endMillis, entry.endMillis);
                last.fileCount++;
                last.sizeBytes += entry.sizeBytes;
            } else {
                final Range range = new Range();
                range.startMillis = entry.startMillis;
                range.endMillis = entry.endMillis;
                range.fileCount = 1;
                range.sizeBytes = entry.sizeBytes;
                ranges.add(range);
            }
        }
        return ranges;
    }

    public static long totalSizeBytes(List<Entry> entries) {
        long total = 0;
        for (Entry entry : entries) total += entry.sizeBytes;
        return total;
    }

    /**
     * Audio only: a recording and the track written beside it cover the same seconds, so adding
     * both up would report twice the time that was actually recorded.
     */
    public static float totalDurationSeconds(List<Entry> entries) {
        float total = 0;
        for (Entry entry : entries) {
            if (entry.kind == Kind.AUDIO) total += entry.durationSeconds;
        }
        return total;
    }

    public static int countOf(List<Entry> entries, Kind kind) {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.kind == kind) ++count;
        }
        return count;
    }

    private static int intLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }
}
