package com.roadspeed.alert;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class StorageActivity extends Activity {
    private static final int PICK = 330;

    private LinearLayout filesBox;
    private EditText region;
    private CheckBox autoUpdate, deleteSource;
    private TextView dbInfo, progressText;
    private ProgressBar progress;
    private BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                progress.setProgress(i.getIntExtra(PbfImportService.EXTRA_PERCENT, 0));
                progressText.setText(i.getStringExtra(PbfImportService.EXTRA_TEXT));
                refresh();
            }
        };

        IntentFilter f = new IntentFilter(PbfImportService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
    }

    @Override
    protected void onStop() {
        savePrefs();
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        super.onStop();
    }

    private void buildUi() {
        ScrollView s = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(17, 17, 17));
        s.addView(root);

        root.addView(label("ROAD DATA & STORAGE", 24, true));

        dbInfo = label("", 14, false);
        root.addView(dbInfo);

        root.addView(label("Geofabrik U.S. region slug", 14, true));

        region = new EditText(this);
        region.setTextColor(Color.WHITE);
        region.setHintTextColor(Color.GRAY);
        region.setHint("oklahoma");
        region.setText(Prefs.get(this).getString(Prefs.KEY_REGION, "oklahoma"));
        root.addView(region);

        autoUpdate = check(
                "Automatically update when Wi-Fi is available",
                Prefs.get(this).getBoolean(Prefs.KEY_AUTO_UPDATE, true));

        deleteSource = check(
                "Delete large PBF after successful processing",
                Prefs.get(this).getBoolean(Prefs.KEY_DELETE_SOURCE, true));

        root.addView(autoUpdate);
        root.addView(deleteSource);

        Button dl = button("CHECK / DOWNLOAD UPDATE ON WI-FI");
        dl.setOnClickListener(v -> {
            savePrefs();
            startImport("download", null);
        });
        root.addView(dl);

        Button imp = button("IMPORT .OSM.PBF FILE");
        imp.setOnClickListener(v -> pickFile());
        root.addView(imp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress);

        progressText = label("Idle", 13, false);
        root.addView(progressText);

        root.addView(label("SOURCE DOWNLOADS", 18, true));
        root.addView(label(
                "Retained PBF files can be extracted into the compact offline road database, then deleted.",
                13,
                false));

        filesBox = new LinearLayout(this);
        filesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(filesBox);

        Button all = button("DELETE ALL RETAINED DOWNLOADS");
        all.setOnClickListener(v -> {
            File[] fs = RoadDataManager.downloadsDir(this).listFiles();
            if (fs != null) {
                for (File f : fs) f.delete();
            }
            refresh();
        });
        root.addView(all);

        Button ext = button("DELETE ORIGINAL MANUALLY IMPORTED FILE");
        ext.setOnClickListener(v -> deleteOriginal());
        root.addView(ext);

        setContentView(s);
    }

    private void refresh() {
        long db = RoadDatabase.databaseSize(this);
        long updated = Prefs.get(this).getLong(Prefs.KEY_LAST_UPDATE, 0);

        dbInfo.setText(
                "Processed offline database: " +
                        (db == 0 ? "not installed" : RoadDataManager.sizeText(db)) +
                        (updated > 0
                                ? "\nLast successful update: " +
                                DateFormat.getDateTimeInstance().format(new Date(updated))
                                : ""));

        filesBox.removeAllViews();

        File[] fs = RoadDataManager.downloadsDir(this).listFiles();
        if (fs == null || fs.length == 0) {
            filesBox.addView(label("No retained source downloads.", 13, false));
            return;
        }

        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File f : fs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            TextView info = label(
                    f.getName() + "\n" + RoadDataManager.sizeText(f.length()),
                    13,
                    false);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            Button process = button("EXTRACT");
            process.setOnClickListener(v -> startRetained(f));
            row.addView(process);

            Button del = button("DELETE");
            del.setOnClickListener(v -> {
                f.delete();
                refresh();
            });
            row.addView(del);

            filesBox.addView(row);
        }
    }

    private void savePrefs() {
        String slug = region.getText().toString()
                .trim()
                .toLowerCase(Locale.US)
                .replace(' ', '-');

        if (slug.isEmpty()) slug = "oklahoma";

        Prefs.get(this).edit()
                .putString(Prefs.KEY_REGION, slug)
                .putBoolean(Prefs.KEY_AUTO_UPDATE, autoUpdate.isChecked())
                .putBoolean(Prefs.KEY_DELETE_SOURCE, deleteSource.isChecked())
                .apply();

        UpdateScheduler.schedulePeriodic(this);
        if (autoUpdate.isChecked()) {
            UpdateScheduler.scheduleNowWhenWifiAvailable(this);
        }
    }

    private void startImport(String mode, Uri uri) {
        Intent i = new Intent(this, PbfImportService.class)
                .putExtra(PbfImportService.EXTRA_MODE, mode);

        if (uri != null) {
            i.putExtra(PbfImportService.EXTRA_URI, uri.toString());
        }

        startForegroundService(i);
    }

    private void startRetained(File f) {
        progress.setProgress(0);
        progressText.setText("Starting extraction…");

        Intent i = new Intent(this, PbfImportService.class)
                .putExtra(PbfImportService.EXTRA_MODE, "retained")
                .putExtra(PbfImportService.EXTRA_FILE, f.getName());

        startForegroundService(i);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int result, Intent data) {
        super.onActivityResult(requestCode, result, data);

        if (requestCode == PICK &&
                result == RESULT_OK &&
                data != null &&
                data.getData() != null) {

            Uri u = data.getData();

            try {
                getContentResolver().takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}

            startImport("uri", u);
        }
    }

    private void deleteOriginal() {
        String raw = Prefs.get(this).getString(Prefs.KEY_EXTERNAL_URI, "");

        if (raw.isEmpty()) {
            Toast.makeText(this, "No remembered external import", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(raw));
            Prefs.get(this).edit().remove(Prefs.KEY_EXTERNAL_URI).apply();
            Toast.makeText(this, "Original file deleted", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Android did not allow deleting that original file",
                    Toast.LENGTH_LONG).show();
        }
    }

    private CheckBox check(String text, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(Color.WHITE);
        c.setChecked(on);
        return c;
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        if (bold) {
            v.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        v.setPadding(dp(4), dp(6), dp(4), dp(6));
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }
}
