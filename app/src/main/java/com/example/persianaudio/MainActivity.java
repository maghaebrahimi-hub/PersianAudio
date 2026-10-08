package com.example.persianaudio;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1001;
    private static final int REQ_SAVE = 1002;

    private EditText originalBox;
    private EditText persianBox;
    private TextView status;
    private TextView languageInfo;

    private Translator translator;
    private MediaPlayer player;
    private List<File> playingFiles = new ArrayList<>();
    private int playingIndex = 0;
    private String pendingSaveText = "";

    private interface TextCallback {
        void onReady(String text);
    }

    private interface AudioCallback {
        void onReady(List<File> files);
        void onError(Exception e);
    }

    private static class TtsRequest {
        final String lang;
        final String text;
        TtsRequest(String lang, String text) {
            this.lang = lang;
            this.text = text;
        }
    }

    private static class LangSegment {
        final String lang;
        final String text;
        LangSegment(String lang, String text) {
            this.lang = lang;
            this.text = text;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 28);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Persian Audio Translator");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("PDF / TXT فارسی → صوت فارسی\nPDF / TXT انگلیسی → ترجمه فارسی → صوت فارسی");
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        subtitle.setPadding(0, 6, 0, 12);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        Button open = new Button(this);
        open.setText("باز کردن PDF یا TXT");
        open.setOnClickListener(v -> openFile());
        root.addView(open, new LinearLayout.LayoutParams(-1, -2));

        TextView originalLabel = new TextView(this);
        originalLabel.setText("متن اصلی");
        originalLabel.setTextSize(16);
        originalLabel.setTextDirection(View.TEXT_DIRECTION_RTL);
        originalLabel.setPadding(0, 12, 0, 4);
        root.addView(originalLabel);

        originalBox = new EditText(this);
        originalBox.setHint("متن فارسی یا انگلیسی را اینجا Paste کنید، یا فایل باز کنید...");
        originalBox.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        originalBox.setGravity(Gravity.TOP | Gravity.RIGHT);
        originalBox.setMinLines(7);
        originalBox.setTextSize(16);
        root.addView(originalBox, new LinearLayout.LayoutParams(-1, -2));

        languageInfo = new TextView(this);
        languageInfo.setText("زبان: هنوز بررسی نشده");
        languageInfo.setTextDirection(View.TEXT_DIRECTION_RTL);
        languageInfo.setPadding(0, 7, 0, 4);
        root.addView(languageInfo);

        Button translate = new Button(this);
        translate.setText("تشخیص زبان و آماده‌سازی فارسی");
        translate.setOnClickListener(v -> translateOrPrepare(null));
        root.addView(translate, new LinearLayout.LayoutParams(-1, -2));

        TextView persianLabel = new TextView(this);
        persianLabel.setText("متن فارسی نهایی (قابل ویرایش)");
        persianLabel.setTextSize(16);
        persianLabel.setTextDirection(View.TEXT_DIRECTION_RTL);
        persianLabel.setPadding(0, 12, 0, 4);
        root.addView(persianLabel);

        persianBox = new EditText(this);
        persianBox.setHint("اگر متن انگلیسی باشد، ترجمه فارسی اینجا نمایش داده می‌شود.");
        persianBox.setTextDirection(View.TEXT_DIRECTION_RTL);
        persianBox.setGravity(Gravity.TOP | Gravity.RIGHT);
        persianBox.setMinLines(7);
        persianBox.setTextSize(16);
        root.addView(persianBox, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout audioButtons = new LinearLayout(this);
        audioButtons.setOrientation(LinearLayout.HORIZONTAL);
        audioButtons.setGravity(Gravity.CENTER);

        Button play = new Button(this);
        play.setText("پخش فارسی");
        play.setOnClickListener(v -> playPersianAudio());
        audioButtons.addView(play, new LinearLayout.LayoutParams(0, -2, 1));

        Button stop = new Button(this);
        stop.setText("توقف");
        stop.setOnClickListener(v -> stopPlayback());
        audioButtons.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(audioButtons, new LinearLayout.LayoutParams(-1, -2));

        Button save = new Button(this);
        save.setText("ساخت و ذخیره فایل صوتی MP3");
        save.setOnClickListener(v -> savePersianAudio());
        root.addView(save, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("نکته: ترجمه انگلیسی→فارسی با ML Kit انجام می‌شود. مدل ترجمه در اولین استفاده دانلود می‌شود. تولید صدای فارسی آنلاین است و به صدای فارسی نصب‌شده روی گوشی وابسته نیست. اصطلاحات انگلیسی باقی‌مانده در متن با صدای انگلیسی خوانده می‌شوند.");
        note.setTextDirection(View.TEXT_DIRECTION_RTL);
        note.setTextSize(13);
        note.setPadding(0, 12, 0, 6);
        root.addView(note);

        status = new TextView(this);
        status.setText("آماده");
        status.setTextDirection(View.TEXT_DIRECTION_RTL);
        status.setTextSize(14);
        status.setPadding(0, 8, 0, 0);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN) {
            loadDocument(uri);
        } else if (requestCode == REQ_SAVE) {
            generateAndSaveMp3(uri, pendingSaveText);
        }
    }

    private void loadDocument(Uri uri) {
        status.setText("در حال خواندن فایل...");
        new Thread(() -> {
            try {
                String name = getDisplayName(uri);
                String lower = name.toLowerCase(Locale.ROOT);
                String text = lower.endsWith(".pdf") ? readPdf(uri) : readTxt(uri);
                String clean = cleanText(text);
                if (clean.isEmpty()) {
                    throw new Exception("متنی از فایل استخراج نشد. اگر PDF اسکن‌شده است، OCR لازم دارد.");
                }
                runOnUiThread(() -> {
                    originalBox.setText(clean);
                    persianBox.setText("");
                    updateLanguageInfo(clean);
                    status.setText("فایل خوانده شد: " + name);
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

    private void translateOrPrepare(TextCallback callback) {
        String source = cleanText(originalBox.getText().toString());
        if (source.isEmpty()) {
            Toast.makeText(this, "ابتدا متن وارد کنید یا فایل باز کنید.", Toast.LENGTH_SHORT).show();
            return;
        }

        updateLanguageInfo(source);
        if (!looksEnglish(source)) {
            persianBox.setText(source);
            status.setText("متن فارسی تشخیص داده شد؛ آماده تولید صوت است.");
            if (callback != null) callback.onReady(source);
            return;
        }

        status.setText("متن انگلیسی تشخیص داده شد؛ در حال آماده‌سازی مدل ترجمه...");
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.PERSIAN)
                .build();

        if (translator != null) translator.close();
        translator = Translation.getClient(options);
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v -> {
                    status.setText("مدل آماده است؛ در حال ترجمه به فارسی...");
                    List<String> chunks = splitByLength(source, 1100);
                    translateChunk(chunks, 0, new StringBuilder(), callback);
                })
                .addOnFailureListener(e -> {
                    status.setText("دانلود مدل ترجمه ناموفق بود");
                    Toast.makeText(this, "خطا در آماده‌سازی ترجمه: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void translateChunk(List<String> chunks, int index, StringBuilder result, TextCallback callback) {
        if (index >= chunks.size()) {
            String translated = cleanText(result.toString());
            persianBox.setText(translated);
            status.setText("ترجمه فارسی آماده شد. می‌توانید متن را ویرایش یا صوت را تولید کنید.");
            if (callback != null) callback.onReady(translated);
            return;
        }

        status.setText("در حال ترجمه... " + (index + 1) + " از " + chunks.size());
        translator.translate(chunks.get(index))
                .addOnSuccessListener(t -> {
                    if (result.length() > 0) result.append("\n\n");
                    result.append(t);
                    translateChunk(chunks, index + 1, result, callback);
                })
                .addOnFailureListener(e -> {
                    status.setText("خطا در ترجمه");
                    Toast.makeText(this, "ترجمه انجام نشد: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void ensurePersianText(TextCallback callback) {
        String editedPersian = cleanText(persianBox.getText().toString());
        if (!editedPersian.isEmpty()) {
            callback.onReady(editedPersian);
            return;
        }
        translateOrPrepare(callback);
    }

    private void playPersianAudio() {
        ensurePersianText(text -> {
            stopPlayback();
            status.setText("در حال ساخت صدای فارسی آنلاین...");
            createAudioFiles(text, new AudioCallback() {
                @Override public void onReady(List<File> files) {
                    runOnUiThread(() -> {
                        playingFiles = files;
                        playingIndex = 0;
                        status.setText("پخش صوت فارسی...");
                        playNextFile();
                    });
                }
                @Override public void onError(Exception e) {
                    runOnUiThread(() -> {
                        status.setText("خطا در ساخت صدا");
                        Toast.makeText(MainActivity.this, "تولید صدا انجام نشد: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
    }

    private void savePersianAudio() {
        ensurePersianText(text -> {
            pendingSaveText = text;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/mpeg");
            i.putExtra(Intent.EXTRA_TITLE, "PersianAudio.mp3");
            startActivityForResult(i, REQ_SAVE);
        });
    }

    private void generateAndSaveMp3(Uri destination, String text) {
        status.setText("در حال تولید فایل صوتی MP3...");
        createAudioFiles(text, new AudioCallback() {
            @Override public void onReady(List<File> files) {
                new Thread(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
                        if (out == null) throw new Exception("محل ذخیره قابل دسترسی نیست.");
                        byte[] buffer = new byte[8192];
                        for (File f : files) {
                            try (FileInputStream in = new FileInputStream(f)) {
                                int n;
                                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                            }
                        }
                        out.flush();
                        runOnUiThread(() -> {
                            status.setText("فایل صوتی MP3 ذخیره شد.");
                            Toast.makeText(MainActivity.this, "فایل صوتی ذخیره شد.", Toast.LENGTH_LONG).show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "خطا در ذخیره: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
            @Override public void onError(Exception e) {
                runOnUiThread(() -> {
                    status.setText("خطا در تولید فایل صوتی");
                    Toast.makeText(MainActivity.this, "تولید صدا انجام نشد: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void createAudioFiles(String text, AudioCallback callback) {
        new Thread(() -> {
            try {
                List<TtsRequest> requests = makeTtsRequests(text);
                if (requests.isEmpty()) throw new Exception("متنی برای خواندن وجود ندارد.");

                List<File> files = new ArrayList<>();
                File dir = new File(getCacheDir(), "online_tts");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("ساخت پوشه موقت ممکن نشد.");
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();

                for (int i = 0; i < requests.size(); i++) {
                    TtsRequest req = requests.get(i);
                    final int done = i + 1;
                    runOnUiThread(() -> status.setText("در حال تولید صدا... " + done + " از " + requests.size()));
                    File file = new File(dir, String.format(Locale.US, "part_%04d.mp3", i));
                    downloadTts(req, file);
                    files.add(file);
                }
                callback.onReady(files);
            } catch (Exception e) {
                callback.onError(e);
            }
        }).start();
    }

    private void downloadTts(TtsRequest req, File file) throws Exception {
        String q = URLEncoder.encode(req.text, "UTF-8");
        String address = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=" + req.lang + "&q=" + q;
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PersianAudioTranslator/2.0");
        conn.setRequestProperty("Accept", "audio/mpeg,*/*");
        conn.setRequestProperty("Referer", "https://translate.google.com/");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("سرویس صوت پاسخ نداد (HTTP " + code + "). اتصال اینترنت را بررسی کنید.");
        }
        try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            conn.disconnect();
        }
        if (!file.exists() || file.length() < 100) throw new Exception("فایل صوتی معتبر دریافت نشد.");
    }

    private void playNextFile() {
        if (playingIndex >= playingFiles.size()) {
            status.setText("پخش تمام شد.");
            stopPlayerOnly();
            return;
        }
        try {
            stopPlayerOnly();
            File file = playingFiles.get(playingIndex++);
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> playNextFile());
            player.setOnErrorListener((mp, what, extra) -> {
                status.setText("خطا در پخش صوت");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            status.setText("خطا در پخش صوت");
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlayback() {
        stopPlayerOnly();
        playingFiles = new ArrayList<>();
        playingIndex = 0;
        status.setText("متوقف شد.");
    }

    private void stopPlayerOnly() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private List<TtsRequest> makeTtsRequests(String text) {
        List<TtsRequest> result = new ArrayList<>();
        for (LangSegment seg : splitMixedLanguage(cleanText(text))) {
            for (String part : splitByLength(seg.text, 170)) {
                String p = part.trim();
                if (!p.isEmpty()) result.add(new TtsRequest(seg.lang, p));
            }
        }
        return result;
    }

    private List<LangSegment> splitMixedLanguage(String text) {
        List<LangSegment> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        String currentLang = "fa";
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String charLang = null;
            if (isPersianChar(c)) charLang = "fa";
            else if (isLatinChar(c)) charLang = "en";

            if (charLang != null && !charLang.equals(currentLang) && current.length() > 0) {
                String s = current.toString().trim();
                if (!s.isEmpty()) out.add(new LangSegment(currentLang, s));
                current.setLength(0);
                currentLang = charLang;
            } else if (charLang != null) {
                currentLang = charLang;
            }
            current.append(c);
        }
        String s = current.toString().trim();
        if (!s.isEmpty()) out.add(new LangSegment(currentLang, s));
        return mergeAdjacent(out);
    }

    private List<LangSegment> mergeAdjacent(List<LangSegment> input) {
        List<LangSegment> out = new ArrayList<>();
        for (LangSegment seg : input) {
            if (!out.isEmpty() && out.get(out.size() - 1).lang.equals(seg.lang)) {
                LangSegment last = out.remove(out.size() - 1);
                out.add(new LangSegment(last.lang, last.text + " " + seg.text));
            } else {
                out.add(seg);
            }
        }
        return out;
    }

    private List<String> splitByLength(String text, int max) {
        List<String> out = new ArrayList<>();
        String remaining = cleanText(text);
        while (remaining.length() > max) {
            int cut = max;
            int p = -1;
            for (int i = max; i > Math.max(0, max - 180); i--) {
                char c = remaining.charAt(i - 1);
                if (c == ' ' || c == '\n' || c == '.' || c == '!' || c == '?' || c == '؟' || c == '؛') {
                    p = i;
                    break;
                }
            }
            if (p > 0) cut = p;
            out.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) out.add(remaining);
        return out;
    }

    private void updateLanguageInfo(String text) {
        if (looksEnglish(text)) {
            languageInfo.setText("زبان تشخیص داده‌شده: انگلیسی → ترجمه به فارسی");
        } else {
            languageInfo.setText("زبان تشخیص داده‌شده: فارسی / متن ترکیبی فارسی و انگلیسی");
        }
    }

    private boolean looksEnglish(String text) {
        int fa = 0, en = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isPersianChar(c)) fa++;
            else if (isLatinChar(c)) en++;
        }
        if (en < 12) return false;
        return en > Math.max(20, (int)(fa * 1.35));
    }

    private boolean isPersianChar(char c) {
        return (c >= '\u0600' && c <= '\u06FF') || (c >= '\u0750' && c <= '\u077F') || (c >= '\u08A0' && c <= '\u08FF');
    }

    private boolean isLatinChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private String cleanText(String s) {
        if (s == null) return "";
        return s.replace('ي', 'ی')
                .replace('ك', 'ک')
                .replace("\u200c", " ")
                .replace("\u200f", "")
                .replace("\u200e", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    @Override
    protected void onDestroy() {
        stopPlayerOnly();
        if (translator != null) translator.close();
        super.onDestroy();
    }
}
