package com.example.persianaudio;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class EdgeTtsClient {
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String CHROMIUM_VERSION = "143.0.3650.75";
    private static final String SEC_MS_GEC_VERSION = "1-" + CHROMIUM_VERSION;
    private static final long WIN_EPOCH = 11644473600L;
    private static final Object REQUEST_LOCK = new Object();
    private static long lastRequestStartedAt = 0L;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private EdgeTtsClient() {}

    public static void synthesizeToFile(String text, String voice, File outputFile) throws Exception {
        if (text == null || text.trim().isEmpty()) throw new Exception("متن برای تولید صدا خالی است.");
        if (voice == null || voice.trim().isEmpty()) throw new Exception("صدای انتخابی نامعتبر است.");

        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                throttleRequests();
                synthesizeOnce(text, voice, outputFile);
                return;
            } catch (Exception e) {
                last = e;
                if (outputFile.exists()) outputFile.delete();
                if (attempt < 4) {
                    try { Thread.sleep(900L * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("تولید صدا متوقف شد.", ie);
                    }
                }
            }
        }
        String msg = (last == null || last.getMessage() == null) ? "خطای نامشخص در سرویس صوت" : last.getMessage();
        throw new Exception("پس از چند تلاش، این بخش صوت ساخته نشد: " + msg, last);
    }

    private static void throttleRequests() throws InterruptedException {
        synchronized (REQUEST_LOCK) {
            long now = System.currentTimeMillis();
            long wait = 650L - (now - lastRequestStartedAt);
            if (wait > 0) Thread.sleep(wait);
            lastRequestStartedAt = System.currentTimeMillis();
        }
    }

    private static void synthesizeOnce(String text, String voice, File outputFile) throws Exception {
        if (outputFile.exists()) outputFile.delete();
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("ساخت پوشه موقت صوت ممکن نشد.");
        }

        final FileOutputStream audioOut = new FileOutputStream(outputFile);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
        final AtomicReference<Boolean> turnEnded = new AtomicReference<>(false);

        String connectionId = UUID.randomUUID().toString().replace("-", "");
        String gec = generateSecMsGec();
        String url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
                + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + connectionId
                + "&Sec-MS-GEC=" + gec
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

        String major = CHROMIUM_VERSION.split("\\.")[0];
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + major + ".0.0.0 Safari/537.36 Edg/" + major + ".0.0.0";

        Request request = new Request.Builder()
                .url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + randomHex(16) + ";")
                .build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                wsRef.set(webSocket);
                String date = dateToString();
                String config = "X-Timestamp:" + date + "\r\n"
                        + "Content-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n"
                        + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                        + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                        + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
                if (!webSocket.send(config)) {
                    failure.compareAndSet(null, new Exception("ارسال تنظیمات به سرویس صوت ناموفق بود."));
                    done.countDown();
                    return;
                }

                String requestId = UUID.randomUUID().toString().replace("-", "");
                String lang = voice.startsWith("fa-") ? "fa-IR" : "en-US";
                String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + lang + "'>"
                        + "<voice name='" + voice + "'>"
                        + "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>"
                        + escapeXml(cleanForService(text))
                        + "</prosody></voice></speak>";
                String ssmlRequest = "X-RequestId:" + requestId + "\r\n"
                        + "Content-Type:application/ssml+xml\r\n"
                        + "X-Timestamp:" + date + "Z\r\n"
                        + "Path:ssml\r\n\r\n"
                        + ssml;
                if (!webSocket.send(ssmlRequest)) {
                    failure.compareAndSet(null, new Exception("ارسال متن به سرویس صوت ناموفق بود."));
                    done.countDown();
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String textMessage) {
                if (textMessage == null) return;
                if (textMessage.contains("Path:turn.end")) {
                    turnEnded.set(true);
                    done.countDown();
                    webSocket.close(1000, "done");
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                try {
                    byte[] data = bytes.toByteArray();
                    if (data.length < 2) return;
                    int headerLen = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
                    int start = 2 + headerLen;
                    if (start < 2 || start > data.length) return;
                    if (start < data.length) {
                        synchronized (audioOut) {
                            audioOut.write(data, start, data.length - start);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    done.countDown();
                    webSocket.cancel();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (response != null) {
                    failure.compareAndSet(null, new Exception("سرویس صوت پاسخ نداد (HTTP " + response.code() + ")", t));
                } else {
                    failure.compareAndSet(null, t);
                }
                done.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!turnEnded.get() && failure.get() == null && code != 1000) {
                    failure.compareAndSet(null, new Exception("ارتباط صوت قبل از پایان بسته شد (کد " + code + ")."));
                }
                done.countDown();
            }
        };

        CLIENT.newWebSocket(request, listener);
        boolean finished = done.await(90, TimeUnit.SECONDS);
        WebSocket ws = wsRef.get();
        if (!finished && ws != null) ws.cancel();

        synchronized (audioOut) {
            audioOut.flush();
            audioOut.close();
        }

        if (!finished) throw new Exception("زمان دریافت این بخش صوت تمام شد. اتصال اینترنت را بررسی کنید.");
        Throwable err = failure.get();
        if (err != null) throw new Exception(err.getMessage() == null ? "خطای ارتباط با سرویس صوت" : err.getMessage(), err);
        if (!outputFile.exists() || outputFile.length() < 300) {
            throw new Exception("فایل صوتی معتبر از سرویس دریافت نشد.");
        }
    }

    private static String cleanForService(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0 && c <= 8) || (c >= 11 && c <= 12) || (c >= 14 && c <= 31)) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String dateToString() {
        SimpleDateFormat df = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date());
    }

    private static String generateSecMsGec() throws Exception {
        long seconds = (System.currentTimeMillis() / 1000L) + WIN_EPOCH;
        seconds -= seconds % 300L;
        long ticks = seconds * 10_000_000L;
        String input = Long.toString(ticks) + TRUSTED_CLIENT_TOKEN;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format(Locale.US, "%02X", b));
        return sb.toString();
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : data) sb.append(String.format(Locale.US, "%02X", b));
        return sb.toString();
    }
}
