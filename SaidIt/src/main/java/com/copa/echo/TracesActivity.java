package com.copa.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import com.copa.echo.android.StringFormat;
import com.copa.echo.android.Views;

/**
 * Shows what is actually on disk, one calendar day at a time. Months of continuous recording
 * add up to hundreds of files; scrolling through all of them flat is not navigation, so a day
 * picker at the top jumps straight to the day you actually want.
 *
 * Audio and location traces are listed side by side as the independent files they are, each
 * opened and deleted on its own.
 */
public class TracesActivity extends Activity {

    private static final String TAG = TracesActivity.class.getSimpleName();

    /** Every trace on disk, oldest first, regardless of which day is on screen. */
    private final List<Traces.Entry> allEntries = new ArrayList<Traces.Entry>();
    /** Midnight (local time) of every day that has at least one trace, oldest first. */
    private final List<Long> availableDays = new ArrayList<Long>();
    /** Index into availableDays of the day on screen, or -1 when there is nothing to show. */
    private int currentDayIndex = -1;

    /** Traces of the day on screen, newest first: what the adapter actually draws. */
    private final List<Traces.Entry> entries = new ArrayList<Traces.Entry>();
    private final TracesAdapter adapter = new TracesAdapter();
    private final Handler handler = new Handler();

    private Typeface bold;
    private Typeface regular;

    private TextView summary;
    private Button dayPrev;
    private Button dayNext;
    private TextView dayLabel;
    private TextView daySummary;
    private TextView rangesTitle;
    private LinearLayout rangesContainer;
    private TextView empty;
    private ListView list;

    private SimpleDateFormat timeFormat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final AssetManager assets = getAssets();
        bold = Typeface.createFromAsset(assets, "RobotoCondensedBold.ttf");
        regular = Typeface.createFromAsset(assets, "RobotoCondensed-Regular.ttf");
        timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

        final ViewGroup root = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_traces, null);
        applyTypefaces(root);

        // The screen draws behind the status bar, so leave room for it.
        final int statusBarId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (statusBarId > 0) {
            root.setPadding(root.getPaddingLeft(),
                    root.getPaddingTop() + getResources().getDimensionPixelSize(statusBarId),
                    root.getPaddingRight(), root.getPaddingBottom());
        }

        list = (ListView) root.findViewById(R.id.traces_list);

        // Part of the scrolling list itself, see the comment in activity_traces.xml.
        // Added before setAdapter: some ListView implementations require that order.
        final View header = getLayoutInflater().inflate(R.layout.traces_header, list, false);
        applyTypefaces((ViewGroup) header);
        list.addHeaderView(header, null, false);

        summary = (TextView) header.findViewById(R.id.traces_summary);
        dayPrev = (Button) header.findViewById(R.id.day_prev);
        dayNext = (Button) header.findViewById(R.id.day_next);
        dayLabel = (TextView) header.findViewById(R.id.day_label);
        daySummary = (TextView) header.findViewById(R.id.day_summary);
        rangesTitle = (TextView) header.findViewById(R.id.ranges_title);
        rangesContainer = (LinearLayout) header.findViewById(R.id.ranges_container);
        empty = (TextView) header.findViewById(R.id.traces_empty);

        dayPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDayIndex > 0) {
                    --currentDayIndex;
                    showDay();
                }
            }
        });

        dayNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDayIndex >= 0 && currentDayIndex < availableDays.size() - 1) {
                    ++currentDayIndex;
                    showDay();
                }
            }
        });

        dayLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDatePicker();
            }
        });

        list.setAdapter(adapter);

        // position counts the header added above, so the real entries start after it.
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final int index = position - list.getHeaderViewsCount();
                if (index >= 0 && index < entries.size()) open(entries.get(index));
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final int index = position - list.getHeaderViewsCount();
                if (index >= 0 && index < entries.size()) confirmDelete(entries.get(index).file);
                return true;
            }
        });

        root.findViewById(R.id.traces_return).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void applyTypefaces(ViewGroup root) {
        Views.search(root, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if (view instanceof TextView) {
                    final Object tag = view.getTag();
                    ((TextView) view).setTypeface("bold".equals(tag) ? bold : regular);
                }
            }
        });
    }

    /** Reads the directories off the UI thread, since they can hold hundreds of files. */
    private void reload() {
        // Traces may sit in a directory an older version wrote to, so look in every readable
        // one rather than only the one being written to now.
        final List<File> dirs = Storage.readable(this);
        final File dir = Storage.resolve(this);
        // Keep showing the same day across a reload (e.g. after deleting a file), falling back
        // to the closest one if it no longer has anything in it.
        final Long keepDay = (currentDayIndex >= 0 && currentDayIndex < availableDays.size())
                ? availableDays.get(currentDayIndex) : null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Traces.Entry> scanned = Traces.scanAll(dirs);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        show(dir, scanned, keepDay);
                    }
                });
            }
        }, "recordings-scan").start();
    }

    private void show(File dir, List<Traces.Entry> scanned, Long keepDay) {
        allEntries.clear();
        allEntries.addAll(scanned);

        final TreeSet<Long> days = new TreeSet<Long>();
        for (Traces.Entry entry : scanned) days.add(dayStart(entry.startMillis));
        availableDays.clear();
        availableDays.addAll(days);

        final int count = scanned.size();
        if (count == 0) {
            summary.setText(getString(R.string.traces_summary_empty, dir.getAbsolutePath()));
        } else {
            summary.setText(getString(R.string.traces_summary,
                    countLabel(scanned),
                    TracesActivity.longDuration((long) (Traces.totalDurationSeconds(scanned) * 1000)),
                    StringFormat.shortFileSize(Traces.totalSizeBytes(scanned)),
                    dir.getAbsolutePath()));
        }

        if (availableDays.isEmpty()) {
            currentDayIndex = -1;
        } else if (keepDay != null && availableDays.contains(keepDay)) {
            currentDayIndex = availableDays.indexOf(keepDay);
        } else if (keepDay != null) {
            currentDayIndex = closestDayIndex(keepDay);
        } else {
            currentDayIndex = availableDays.size() - 1; // most recent day by default
        }

        showDay();
    }

    /** Redraws everything below the overall summary for currentDayIndex. */
    private void showDay() {
        entries.clear();

        if (currentDayIndex < 0) {
            dayLabel.setText("");
            daySummary.setText("");
            dayPrev.setEnabled(false);
            dayNext.setEnabled(false);
            empty.setText(R.string.traces_empty);
            empty.setVisibility(View.VISIBLE);
            rangesTitle.setVisibility(View.GONE);
            rangesContainer.removeAllViews();
            adapter.notifyDataSetChanged();
            return;
        }

        final long dayStart = availableDays.get(currentDayIndex);
        dayLabel.setText(dayLabelLong(dayStart));
        dayPrev.setEnabled(currentDayIndex > 0);
        dayNext.setEnabled(currentDayIndex < availableDays.size() - 1);

        final List<Traces.Entry> dayEntries = new ArrayList<Traces.Entry>();
        for (Traces.Entry entry : allEntries) {
            if (dayStart(entry.startMillis) == dayStart) dayEntries.add(entry);
        }
        // Newest first, which is what you want to look at when you open this screen.
        for (int i = dayEntries.size() - 1; i >= 0; --i) entries.add(dayEntries.get(i));
        adapter.notifyDataSetChanged();

        if (dayEntries.isEmpty()) {
            daySummary.setText("");
            empty.setText(R.string.traces_day_empty);
            empty.setVisibility(View.VISIBLE);
            rangesTitle.setVisibility(View.GONE);
            rangesContainer.removeAllViews();
            return;
        }

        empty.setVisibility(View.GONE);
        daySummary.setText(getString(R.string.traces_day_summary,
                countLabel(dayEntries),
                longDuration((long) (Traces.totalDurationSeconds(dayEntries) * 1000)),
                StringFormat.shortFileSize(Traces.totalSizeBytes(dayEntries))));

        final List<Traces.Range> ranges = Traces.ranges(dayEntries, Traces.DEFAULT_MAX_GAP_MILLIS);
        rangesTitle.setVisibility(View.VISIBLE);
        rangesContainer.removeAllViews();
        // Newest range first, same order as the list below.
        for (int i = ranges.size() - 1; i >= 0; --i) {
            final Traces.Range range = ranges.get(i);
            final TextView view = new TextView(this);
            view.setTypeface(regular);
            view.setTextSize(16);
            view.setTextColor(getResources().getColor(R.color.gray_c));
            view.setText(getString(R.string.traces_range_line,
                    dayLabel(range.startMillis),
                    timeFormat.format(new Date(range.startMillis)),
                    timeFormat.format(new Date(range.endMillis)),
                    longDuration(range.durationMillis()),
                    getResources().getQuantityString(R.plurals.traces_file_count,
                            range.fileCount, range.fileCount)));
            rangesContainer.addView(view);
        }
    }

    /** "25 traces (6 audio, 6 GPS, 13 clips)", dropping the breakdown when there is one kind. */
    private String countLabel(List<Traces.Entry> entries) {
        final int total = entries.size();
        final String count = getResources().getQuantityString(
                R.plurals.traces_file_count, total, total);
        final int audio = Traces.countOf(entries, Traces.Kind.AUDIO);
        final int location = Traces.countOf(entries, Traces.Kind.LOCATION);
        final int image = Traces.countOf(entries, Traces.Kind.IMAGE);
        final int video = Traces.countOf(entries, Traces.Kind.VIDEO);
        final int note = Traces.countOf(entries, Traces.Kind.NOTE);
        final int survey = Traces.countOf(entries, Traces.Kind.SURVEY);

        final List<String> parts = new ArrayList<String>();
        if (audio > 0) parts.add(getString(R.string.traces_kind_audio, audio));
        if (location > 0) parts.add(getString(R.string.traces_kind_gps, location));
        if (image > 0) parts.add(getString(R.string.traces_kind_images, image));
        if (video > 0) parts.add(getString(R.string.traces_kind_videos, video));
        if (note > 0) parts.add(getString(R.string.traces_kind_notes, note));
        if (survey > 0) parts.add(getString(R.string.traces_kind_surveys, survey));
        if (parts.size() <= 1) return count;

        final StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.size(); ++i) {
            if (i > 0) joined.append(", ");
            joined.append(parts.get(i));
        }
        return getString(R.string.traces_count_wrap, count, joined.toString());
    }

    private void openDatePicker() {
        if (availableDays.isEmpty() || currentDayIndex < 0) return;
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(availableDays.get(currentDayIndex));
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                final Calendar picked = Calendar.getInstance();
                picked.set(year, month, dayOfMonth, 0, 0, 0);
                picked.set(Calendar.MILLISECOND, 0);
                jumpToDay(picked.getTimeInMillis());
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** Jumps to the given day, or the closest one that actually has recordings. */
    private void jumpToDay(long target) {
        final int index = closestDayIndex(target);
        if (index < 0) return;
        final boolean exact = availableDays.get(index) == target;
        currentDayIndex = index;
        showDay();
        if (!exact) {
            Toast.makeText(this, getString(R.string.traces_jumped_to_day,
                    dayLabelLong(availableDays.get(index))), Toast.LENGTH_SHORT).show();
        }
    }

    private int closestDayIndex(long target) {
        int best = -1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < availableDays.size(); ++i) {
            final long diff = Math.abs(availableDays.get(i) - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    /** Midnight, local time, of the day millis falls on: the key entries are grouped by. */
    private static long dayStart(long millis) {
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private String dayLabel(long millis) {
        return DateUtils.formatDateTime(this, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
    }

    /** Full weekday and date, no abbreviations: what the day picker itself shows. */
    private String dayLabelLong(long millis) {
        return DateUtils.formatDateTime(this, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_YEAR);
    }

    /** h:mm:ss, dropping the hours when there are none. */
    static String longDuration(long millis) {
        final long totalSeconds = Math.max(0, millis / 1000);
        final long hours = totalSeconds / 3600;
        final long minutes = (totalSeconds % 3600) / 60;
        final long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private void open(Traces.Entry entry) {
        // Written traces are a few lines of text Echo itself produced, and asking another app to
        // display them is a detour: read them right here instead.
        if (entry.kind == Traces.Kind.NOTE || entry.kind == Traces.Kind.SURVEY) {
            showText(entry);
            return;
        }
        final File file = entry.file;
        try {
            final Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeOf(entry));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Can't open " + file.getName(), e);
            final int message;
            if (entry.kind == Traces.Kind.AUDIO) message = R.string.traces_cant_open;
            else if (entry.kind == Traces.Kind.IMAGE) message = R.string.traces_cant_open_image;
            else if (entry.kind == Traces.Kind.VIDEO) message = R.string.traces_cant_open_video;
            else message = R.string.traces_cant_open_track;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    /** Shows a note or a survey as the text it is, without leaving the screen. */
    private void showText(Traces.Entry entry) {
        String text;
        try {
            text = readText(entry.file);
        } catch (Exception e) {
            Log.w(TAG, "Can't read " + entry.file.getName(), e);
            Toast.makeText(this, R.string.traces_cant_read, Toast.LENGTH_LONG).show();
            return;
        }
        if (entry.kind == Traces.Kind.SURVEY) text = Survey.readable(text);
        new AlertDialog.Builder(this)
                .setTitle(entry.file.getName())
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static String readText(File file) throws java.io.IOException {
        final java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            final byte[] bytes = new byte[(int) Math.min(file.length(), 256 * 1024)];
            int read = 0;
            while (read < bytes.length) {
                final int got = in.read(bytes, read, bytes.length - read);
                if (got < 0) break;
                read += got;
            }
            return new String(bytes, 0, read, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String mimeOf(Traces.Entry entry) {
        if (entry.kind == Traces.Kind.AUDIO) return "audio/wav";
        if (entry.kind == Traces.Kind.LOCATION) return "application/gpx+xml";
        if (entry.kind == Traces.Kind.VIDEO) return "video/mp4";
        if (entry.kind == Traces.Kind.NOTE) return "text/plain";
        if (entry.kind == Traces.Kind.SURVEY) return "application/json";
        return entry.file.getName().toLowerCase(Locale.US).endsWith(".png") ? "image/png" : "image/jpeg";
    }

    /** The label a trace shows for itself, told apart by the suffix its capturer wrote. */
    private String kindLabel(Traces.Entry entry) {
        final String name = entry.file.getName().toLowerCase(Locale.US);
        if (entry.kind == Traces.Kind.NOTE) return getString(R.string.traces_note_label);
        if (entry.kind == Traces.Kind.SURVEY) return getString(R.string.traces_survey_label);
        if (entry.kind == Traces.Kind.VIDEO) {
            if (name.contains("_front.")) return getString(R.string.traces_video_front);
            if (name.contains("_back.")) return getString(R.string.traces_video_back);
            return getString(R.string.traces_video_generic);
        }
        if (name.contains("_screen.")) return getString(R.string.traces_image_screen);
        if (name.contains("_front.")) return getString(R.string.traces_image_front);
        if (name.contains("_back.")) return getString(R.string.traces_image_back);
        return getString(R.string.traces_image_generic);
    }

    private void confirmDelete(final File file) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.traces_delete_title)
                .setMessage(getString(R.string.traces_delete_message, file.getName()))
                .setPositiveButton(R.string.traces_delete_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!file.delete()) {
                            Toast.makeText(TracesActivity.this, R.string.traces_cant_delete, Toast.LENGTH_LONG).show();
                        }
                        reload();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private class TracesAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(TracesActivity.this).inflate(R.layout.item_trace, parent, false);
                applyTypefaces((ViewGroup) view);
            }

            final Traces.Entry entry = entries.get(position);
            final String start = timeFormat.format(new Date(entry.startMillis));
            final String end = timeFormat.format(new Date(entry.endMillis));
            final String approx = entry.exactStart ? "" : "~";

            // A photo, a note and a survey are instants, so they show a single time rather than
            // a start → end range.
            final boolean instant = entry.kind == Traces.Kind.IMAGE
                    || entry.kind == Traces.Kind.NOTE
                    || entry.kind == Traces.Kind.SURVEY;
            final String range = instant
                    ? getString(R.string.traces_item_moment, approx, dayLabel(entry.startMillis), start)
                    : getString(R.string.traces_item_range, approx, dayLabel(entry.startMillis), start, end);
            ((TextView) view.findViewById(R.id.trace_range)).setText(range);

            final String duration = longDuration((long) (entry.durationSeconds * 1000));
            final String size = StringFormat.shortFileSize(entry.sizeBytes);
            final String detail;
            if (entry.kind == Traces.Kind.AUDIO) {
                detail = getString(R.string.traces_item_detail, duration, size,
                        entry.sampleRate / 1000, entry.file.getName());
            } else if (entry.kind == Traces.Kind.VIDEO) {
                detail = getString(R.string.traces_item_detail_video, kindLabel(entry),
                        duration, size, entry.file.getName());
            } else if (instant) {
                detail = getString(R.string.traces_item_detail_image,
                        kindLabel(entry), size, entry.file.getName());
            } else {
                detail = getString(R.string.traces_item_detail_gps, duration, size, entry.file.getName());
            }
            ((TextView) view.findViewById(R.id.trace_detail)).setText(detail);
            return view;
        }
    }
}
