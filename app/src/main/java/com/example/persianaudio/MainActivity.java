package com.example.persianaudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_OPEN = 1001;
    private static final int REQ_SAVE = 1002;
    private static final String GOOGLE_TTS_PACKAGE = "com.google.android.tts";
    private static final Locale FA_IR = new Locale("fa", "IR");
    private static final Locale EN_US = Locale.US;
    private static final int LANG_FA = 1;
    private static final int LANG_EN = 2;

    private EditText textBox;
    private TextView status;
    private TextView engineInfo;
    private SeekBar speedBar;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private List<SpeechSegment> playSegments = new ArrayList<>();
    private int playIndex = 0;

    private List<SpeechSegment> saveSegments = new ArrayList<>();
    private final List<File> savePartFiles = new ArrayList<>();
    private int saveIndex = 0;
    private Uri pendingSaveUri;

    private static class SpeechSegment {
        final int lang;
        final String text;
        SpeechSegment(int lang, String text) {
            this.lang = lang;
            this.text = text;
        }
    }

    private static class WavInfo {
        final byte[] bytes;
        final int dataOffset;
        final int dataSize;
        final int dataSizeFieldOffset;
        final byte[] format;
        WavInfo(byte[] bytes, int dataOffset, int dataSize, int dataSizeFieldOffset, byte[] format) {
            this.bytes = bytes;
            this.dataOffset = dataOffset;
            this.dataSize = dataSize;
            this.dataSizeFieldOffset = dataSizeFieldOffset;
            this.format = format;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        buildUi();
        initPersianTts();
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
        subtitle.setText("خواندن متن فارسی + اصطلاحات انگلیسی، TXT و PDF");
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        Button open = new Button(this);
        open.setText("باز کردن PDF یا TXT");
        open.setOnClickListener(v -> openFile());
        root.addView(open, new LinearLayout.LayoutParams(-1, -2));

        textBox = new EditText(this);
        textBox.setHint("مثال: کنترل ولو Control Valve برای تنظیم Flow استفاده می‌شود.");
        textBox.setTextDirection(View.TEXT_DIRECTION_RTL);
        textBox.setGravity(Gravity.TOP | Gravity.RIGHT);
        textBox.setMinLines(12);
        textBox.setTextSize(17);
        root.addView(textBox, new LinearLayout.LayoutParams(-1, -2));

        engineInfo = new TextView(this);
        engineInfo.setText("در حال بررسی صدای فارسی...");
        engineInfo.setTextSize(14);
        engineInfo.setPadding(0, 10, 0, 6);
        engineInfo.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(engineInfo, new LinearLayout.LayoutParams(-1, -2));

        Button voiceSetup = new Button(this);
        voiceSetup.setText("نصب / تنظیم صدای فارسی");
        voiceSetup.setOnClickListener(v -> showPersianVoiceHelp());
        root.addView(voiceSetup, new LinearLayout.LayoutParams(-1, -2));

        TextView speedLabel = new TextView(this);
        speedLabel.setText("سرعت خواندن");
        speedLabel.setTextDirection(View.TEXT_DIRECTION_RTL);
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
        stop.setOnClickListener(v -> {
            if (tts != null) tts.stop();
            playSegments.clear();
            status.setText("متوقف شد");
        });
        buttons.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        Button save = new Button(this);
        save.setText("ذخیره فایل صوتی WAV");
        save.setOnClickListener(v -> chooseSaveLocation());
        root.addView(save, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("آماده");
        status.setTextSize(14);
        status.setPadding(0, 18, 0, 0);
        status.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(status);

        setContentView(scroll);
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void initPersianTts() {
        ttsReady = false;
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
        if (isPackageInstalled(GOOGLE_TTS_PACKAGE)) {
            engineInfo.setText("Google Speech پیدا شد؛ در حال بررسی فارسی...");
            tts = new TextToSpeech(this, this, GOOGLE_TTS_PACKAGE);
        } else {
            engineInfo.setText("در حال بررسی موتور گفتار پیش‌فرض گوشی...");
            tts = new TextToSpeech(this, this);
        }
    }

    private boolean hasLanguage(Locale locale) {
        if (tts == null) return false;
        int r = tts.isLanguageAvailable(locale);
        return r >= TextToSpeech.LANG_AVAILABLE;
    }

    private boolean setLanguageFor(int lang) {
        Locale wanted = (lang == LANG_EN) ? EN_US : FA_IR;
        int r = tts.setLanguage(wanted);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (lang == LANG_FA) {
                Locale fallbackFa = new Locale("fa");
                r = tts.setLanguage(fallbackFa);
            } else {
                r = tts.setLanguage(Locale.ENGLISH);
            }
        }
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
    }

    private void showPersianVoiceHelp() {
        new AlertDialog.Builder(this)
                .setTitle("صدای فارسی")
                .setMessage("این برنامه متن را به بخش‌های فارسی و انگلیسی تقسیم می‌کند. برای بخش فارسی باید موتور Text-to-Speech گوشی صدای فارسی داشته باشد. اگر فقط انگلیسی می‌شنوید، گزینه نصب دادهٔ صدا را بزنید و Persian / فارسی را در موتور گفتار نصب یا فعال کنید.")
                .setPositiveButton("نصب دادهٔ صدا", (d, w) -> installTtsData())
                .setNeutralButton("بررسی دوباره", (d, w) -> initPersianTts())
                .setNegativeButton("بستن", null)
                .show();
    }

    private void installTtsData() {
        try {
            Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "صفحه نصب صدای گوشی باز نشد. در Settings عبارت Text-to-speech را جستجو کنید.", Toast.LENGTH_LONG).show();
        }
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
        if (!ttsReady) {
            Toast.makeText(this, "موتور گفتار هنوز آماده نیست.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE, "PersianAudio_Mixed.wav");
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
            startMixedSave();
        }
    }

    private void loadDocument(Uri uri) {
        status.setText("در حال خواندن فایل...");
        new Thread(() -> {
            try {
                String name = getDisplayName(uri).toLowerCase(Locale.ROOT);
                String text = name.endsWith(".pdf") ? readPdf(uri) : readTxt(uri);
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
        if (!ttsReady || tts == null) {
            Toast.makeText(this, "موتور گفتار آماده نیست. دکمه نصب / تنظیم صدای فارسی را بزنید.", Toast.LENGTH_LONG).show();
            return;
        }
        String text = cleanText(textBox.getText().toString());
        if (text.isEmpty()) return;

        playSegments = splitByLanguage(text);
        playIndex = 0;
        tts.stop();
        status.setText("در حال پخش فارسی و انگلیسی...");
        speakNextSegment();
    }

    private void speakNextSegment() {
        if (playIndex >= playSegments.size()) {
            status.setText("پخش تمام شد");
            return;
        }
        SpeechSegment seg = playSegments.get(playIndex);
        if (!setLanguageFor(seg.lang)) {
            if (seg.lang == LANG_FA) {
                status.setText("صدای فارسی نصب نیست");
                showPersianVoiceHelp();
                return;
            }
        }
        applyRateAndPitch();
        String id = "play_" + playIndex;
        int r = tts.speak(seg.text, TextToSpeech.QUEUE_FLUSH, null, id);
        if (r != TextToSpeech.SUCCESS) {
            status.setText("خطا در پخش");
        }
    }

    private void startMixedSave() {
        if (!ttsReady || pendingSaveUri == null) return;
        String text = cleanText(textBox.getText().toString());
        saveSegments = splitByLanguage(text);
        savePartFiles.clear();
        saveIndex = 0;
        status.setText("در حال ساخت فایل صوتی...");
        synthesizeNextSavePart();
    }

    private void synthesizeNextSavePart() {
        if (saveIndex >= saveSegments.size()) {
            mergeAndCopySavedParts();
            return;
        }
        SpeechSegment seg = saveSegments.get(saveIndex);
        if (!setLanguageFor(seg.lang)) {
            status.setText(seg.lang == LANG_FA ? "صدای فارسی نصب نیست" : "صدای انگلیسی نصب نیست");
            if (seg.lang == LANG_FA) showPersianVoiceHelp();
            return;
        }
        applyRateAndPitch();
        try {
            File part = new File(getCacheDir(), String.format(Locale.US, "tts_part_%04d.wav", saveIndex));
            if (part.exists()) part.delete();
            savePartFiles.add(part);
            int r = tts.synthesizeToFile(seg.text, null, part, "save_" + saveIndex);
            if (r != TextToSpeech.SUCCESS) {
                status.setText("خطا در ساخت بخش صوتی");
            }
        } catch (Exception e) {
            status.setText("خطا در ساخت فایل صوتی");
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyRateAndPitch() {
        float rate = 0.65f + speedBar.getProgress() * 0.09f;
        tts.setSpeechRate(rate);
        tts.setPitch(1.0f);
    }

    private List<SpeechSegment> splitByLanguage(String text) {
        List<SpeechSegment> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentLang = 0;

        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            int type = scriptType(cp);
            String chars = new String(Character.toChars(cp));

            if (type == 0) {
                current.append(chars);
            } else if (currentLang == 0) {
                currentLang = type;
                current.append(chars);
            } else if (type == currentLang) {
                current.append(chars);
            } else {
                addSegment(result, currentLang, current.toString());
                current.setLength(0);
                currentLang = type;
                current.append(chars);
            }
            offset += Character.charCount(cp);
        }

        if (current.length() > 0) {
            if (currentLang == 0) currentLang = LANG_FA;
            addSegment(result, currentLang, current.toString());
        }
        return result;
    }

    private void addSegment(List<SpeechSegment> list, int lang, String raw) {
        String s = raw;
        if (s.trim().isEmpty()) {
            if (!list.isEmpty()) {
                SpeechSegment prev = list.remove(list.size() - 1);
                list.add(new SpeechSegment(prev.lang, prev.text + s));
            }
            return;
        }
        final int max = 2800;
        while (s.length() > max) {
            int cut = s.lastIndexOf(' ', max);
            if (cut < 1000) cut = max;
            list.add(new SpeechSegment(lang, s.substring(0, cut)));
            s = s.substring(cut);
        }
        if (!s.isEmpty()) list.add(new SpeechSegment(lang, s));
    }

    private int scriptType(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        if (script == Character.UnicodeScript.ARABIC) return LANG_FA;
        if (script == Character.UnicodeScript.LATIN) return LANG_EN;
        return 0;
    }

    @Override
    public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS) {
            ttsReady = true;
            boolean fa = hasLanguage(FA_IR) || hasLanguage(new Locale("fa"));
            boolean en = hasLanguage(EN_US) || hasLanguage(Locale.ENGLISH);
            engineInfo.setText("فارسی: " + (fa ? "آماده" : "نصب نیست") + "   |   English: " + (en ? "Ready" : "Not installed"));
            if (!fa) {
                status.setText("صدای فارسی نصب نیست؛ دکمه نصب / تنظیم صدای فارسی را بزنید.");
            } else {
                setLanguageFor(LANG_FA);
                status.setText("آماده - متن فارسی و انگلیسی را با هم وارد کنید");
            }

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}

                @Override public void onError(String utteranceId) {
                    runOnUiThread(() -> status.setText("خطا در تولید صدا"));
                }

                @Override public void onDone(String utteranceId) {
                    if (utteranceId != null && utteranceId.startsWith("play_")) {
                        playIndex++;
                        runOnUiThread(() -> speakNextSegment());
                    } else if (utteranceId != null && utteranceId.startsWith("save_")) {
                        saveIndex++;
                        runOnUiThread(() -> synthesizeNextSavePart());
                    }
                }
            });
        } else {
            ttsReady = false;
            engineInfo.setText("موتور Text-to-Speech آماده نشد");
            Toast.makeText(this, "موتور Text-to-Speech آماده نشد.", Toast.LENGTH_LONG).show();
        }
    }

    private void mergeAndCopySavedParts() {
        new Thread(() -> {
            try {
                if (savePartFiles.isEmpty()) throw new Exception("بخش صوتی ساخته نشد.");
                List<WavInfo> infos = new ArrayList<>();
                for (File f : savePartFiles) infos.add(readWav(f));

                byte[] fmt = infos.get(0).format;
                int totalData = 0;
                for (WavInfo info : infos) {
                    if (!Arrays.equals(fmt, info.format)) {
                        throw new Exception("فرمت صدای فارسی و انگلیسی یکسان نیست و ادغام فایل ممکن نشد.");
                    }
                    totalData += info.dataSize;
                }

                WavInfo first = infos.get(0);
                ByteArrayOutputStream merged = new ByteArrayOutputStream(first.dataOffset + totalData);
                merged.write(first.bytes, 0, first.dataOffset);
                for (WavInfo info : infos) {
                    merged.write(info.bytes, info.dataOffset, info.dataSize);
                }
                byte[] outBytes = merged.toByteArray();
                putLeInt(outBytes, 4, outBytes.length - 8);
                putLeInt(outBytes, first.dataSizeFieldOffset, totalData);

                try (OutputStream out = getContentResolver().openOutputStream(pendingSaveUri, "w")) {
                    out.write(outBytes);
                    out.flush();
                }

                for (File f : savePartFiles) try { f.delete(); } catch (Exception ignored) {}
                runOnUiThread(() -> {
                    status.setText("فایل صوتی فارسی + انگلیسی ذخیره شد");
                    Toast.makeText(this, "فایل صوتی ذخیره شد.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("خطا در ذخیره فایل صوتی");
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private WavInfo readWav(File file) throws Exception {
        byte[] b = Files.readAllBytes(file.toPath());
        if (b.length < 44 || !asciiEquals(b, 0, "RIFF") || !asciiEquals(b, 8, "WAVE")) {
            throw new Exception("خروجی موتور گفتار WAV استاندارد نیست.");
        }
        int pos = 12;
        byte[] fmt = null;
        int dataOffset = -1;
        int dataSize = -1;
        int dataSizeField = -1;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, StandardCharsets.US_ASCII);
            int size = getLeInt(b, pos + 4);
            int start = pos + 8;
            if (size < 0 || start + size > b.length) break;
            if ("fmt ".equals(id)) fmt = Arrays.copyOfRange(b, start, start + size);
            if ("data".equals(id)) {
                dataOffset = start;
                dataSize = size;
                dataSizeField = pos + 4;
                break;
            }
            pos = start + size + (size % 2);
        }
        if (fmt == null || dataOffset < 0) throw new Exception("ساختار فایل WAV قابل تشخیص نیست.");
        return new WavInfo(b, dataOffset, dataSize, dataSizeField, fmt);
    }

    private boolean asciiEquals(byte[] b, int offset, String s) {
        if (offset + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if ((byte)s.charAt(i) != b[offset + i]) return false;
        return true;
    }

    private int getLeInt(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8) | ((b[o+2] & 0xff) << 16) | ((b[o+3] & 0xff) << 24);
    }

    private void putLeInt(byte[] b, int o, int v) {
        b[o] = (byte)(v & 0xff);
        b[o+1] = (byte)((v >> 8) & 0xff);
        b[o+2] = (byte)((v >> 16) & 0xff);
        b[o+3] = (byte)((v >> 24) & 0xff);
    }

    private String cleanText(String s) {
        return s.replace('ي', 'ی').replace('ك', 'ک').trim();
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
