package com.example.persianaudio;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_OPEN = 1001;
    private static final int REQ_SAVE = 1002;

    private EditText textBox;
    private TextView status;
    private SeekBar speedBar;
    private TextToSpeech tts;
    private Uri pendingSaveUri;
    private File pendingAudioFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        tts = new TextToSpeech(this, this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Persian Audio");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("تبدیل متن فارسی، TXT و PDF به صدا");
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        Button open = new Button(this);
        open.setText("باز کردن PDF یا TXT");
        open.setOnClickListener(v -> openFile());
        root.addView(open, new LinearLayout.LayoutParams(-1, -2));

        textBox = new EditText(this);
        textBox.setHint("متن فارسی را اینجا بنویسید یا فایل باز کنید...");
        textBox.setTextDirection(View.TEXT_DIRECTION_RTL);
        textBox.setGravity(Gravity.TOP | Gravity.RIGHT);
        textBox.setMinLines(12);
        textBox.setTextSize(17);
        root.addView(textBox, new LinearLayout.LayoutParams(-1, -2));

        TextView speedLabel = new TextView(this);
        speedLabel.setText("سرعت خواندن");
        root.addView(speedLabel);

        speedBar = new SeekBar(this);
        speedBar.setMax(10);
        speedBar.setProgress(5);
        root.addView(speedBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        Button play = new Button(this);
        play.setText("پخش");
        play.setOnClickListener(v -> speakText());
        buttons.addView(play, new LinearLayout.LayoutParams(0, -2, 1));

        Button stop = new Button(this);
        stop.setText("توقف");
        stop.setOnClickListener(v -> { if (tts != null) tts.stop(); });
        buttons.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        Button save = new Button(this);
        save.setText("ذخیره فایل صوتی");
        save.setOnClickListener(v -> chooseSaveLocation());
        root.addView(save, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("آماده");
        status.setTextSize(14);
        status.setPadding(0, 18, 0, 0);
        root.addView(status);

        setContentView(scroll);
    }

    private void openFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "text/plain"});
        startActivityForResult(i, REQ_OPEN);
    }

    private void chooseSaveLocation() {
        String text = cleanText(textBox.getText().toString());
        if (text.isEmpty()) {
            Toast.makeText(this, "ابتدا متن وارد کنید.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE, "PersianAudio.wav");
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN) {
            loadDocument(uri);
        } else if (requestCode == REQ_SAVE) {
            pendingSaveUri = uri;
            synthesizeToTempAndCopy();
        }
    }

    private void loadDocument(Uri uri) {
        status.setText("در حال خواندن فایل...");
        new Thread(() -> {
            try {
                String name = getDisplayName(uri).toLowerCase(Locale.ROOT);
                String text;
                if (name.endsWith(".pdf")) text = readPdf(uri);
                else text = readTxt(uri);
                final String finalText = cleanText(text);
                runOnUiThread(() -> {
                    textBox.setText(finalText);
                    status.setText("فایل خوانده شد: " + getDisplayName(uri));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("خطا در خواندن فایل");
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String readTxt(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String readPdf(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String getDisplayName(Uri uri) {
        String result = "document";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return result == null ? "document" : result;
    }

    private void speakText() {
        if (tts == null) return;
        String text = cleanText(textBox.getText().toString());
        if (text.isEmpty()) return;
        applySpeechSettings();
        status.setText("در حال پخش...");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "play");
    }

    private void synthesizeToTempAndCopy() {
        if (tts == null || pendingSaveUri == null) return;
        String text = cleanText(textBox.getText().toString());
        if (text.isEmpty()) return;
        applySpeechSettings();
        try {
            pendingAudioFile = new File(getCacheDir(), "persian_audio.wav");
            if (pendingAudioFile.exists()) pendingAudioFile.delete();
            status.setText("در حال ساخت فایل صوتی...");
            int r = tts.synthesizeToFile(text, null, pendingAudioFile, "save_audio");
            if (r != TextToSpeech.SUCCESS) {
                status.setText("ساخت فایل صوتی شروع نشد");
                Toast.makeText(this, "موتور گفتار نتوانست فایل را بسازد.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applySpeechSettings() {
        tts.setLanguage(new Locale("fa", "IR"));
        float rate = 0.65f + speedBar.getProgress() * 0.09f;
        tts.setSpeechRate(rate);
        tts.setPitch(1.0f);
    }

    @Override
    public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS) {
            int lang = tts.setLanguage(new Locale("fa", "IR"));
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "صدای فارسی در موتور گفتار گوشی نصب نیست. از تنظیمات Text-to-Speech صدای فارسی را نصب کنید.", Toast.LENGTH_LONG).show();
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onError(String utteranceId) {
                    runOnUiThread(() -> status.setText("خطا در تولید صدا"));
                }
                @Override public void onDone(String utteranceId) {
                    if ("save_audio".equals(utteranceId)) copyAudioToDestination();
                    if ("play".equals(utteranceId)) runOnUiThread(() -> status.setText("پخش تمام شد"));
                }
            });
        } else {
            Toast.makeText(this, "موتور Text-to-Speech آماده نشد.", Toast.LENGTH_LONG).show();
        }
    }

    private void copyAudioToDestination() {
        if (pendingAudioFile == null || pendingSaveUri == null) return;
        try (FileInputStream in = new FileInputStream(pendingAudioFile);
             OutputStream out = getContentResolver().openOutputStream(pendingSaveUri, "w")) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
            runOnUiThread(() -> {
                status.setText("فایل صوتی ذخیره شد");
                Toast.makeText(this, "فایل صوتی ذخیره شد.", Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "خطا در ذخیره: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private String cleanText(String s) {
        return s.replace('ي','ی').replace('ك','ک').trim();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
