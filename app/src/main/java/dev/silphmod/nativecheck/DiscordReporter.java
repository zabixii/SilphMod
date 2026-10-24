package dev.silphmod.nativecheck;

import android.os.SystemClock;
import dev.rootcatch.nativeengine.NativeSessionState;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class DiscordReporter {
    private static final int COLOR_ACTIVE = 6416822;   // 0x61E9B6 (Emerald Accent)
    private static final int COLOR_PAUSED = 16753920;  // 0xFFA500 (Amber)
    private static final int COLOR_HOURLY = 5793266;   // 0x5865F2 (Discord Blurple)
    private static final int COLOR_STOPPED = 10066329; // 0x99AABD (Muted Gray)
    private static final int COLOR_ERROR = 15548997;   // 0xED4245 (Red)

    private final String webhookUrl;
    private final int mode;
    private final NativeSettings settings;
    private final CheckLog log;

    private volatile boolean running = true;
    private Thread worker;

    private String currentMessageId = null;
    private long sessionStartElapsed = 0;
    private long lastHourMarkElapsed = 0;
    private int completedHours = 0;

    private NativeSessionState.Totals hourBaselineTotals;
    private NativeSessionState.Totals currentTotals;
    private String currentStatus = "Starting…";
    private String currentDetails = "";

    DiscordReporter(String webhookUrl, int mode, NativeSettings settings, CheckLog log) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.mode = mode;
        this.settings = settings;
        this.log = log;
        this.currentTotals = settings.totals(mode);
        this.hourBaselineTotals = this.currentTotals;
    }

    void start() {
        if (webhookUrl.isEmpty() || !webhookUrl.startsWith("http")) return;
        sessionStartElapsed = SystemClock.elapsedRealtime();
        lastHourMarkElapsed = sessionStartElapsed;
        worker = new Thread(this::loop, "silph-discord-reporter");
        worker.setDaemon(true);
        worker.start();
    }

    void update(String status, String details) {
        this.currentStatus = status == null ? "" : status;
        this.currentDetails = details == null ? "" : details;
        if (settings != null) {
            this.currentTotals = settings.totals(mode);
        }
    }

    void stop(String finalReason, boolean cleanupConfirmed) {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        if (webhookUrl.isEmpty() || !webhookUrl.startsWith("http")) return;

        new Thread(() -> {
            try {
                JSONObject payload = buildStoppedPayload(finalReason, cleanupConfirmed);
                if (currentMessageId != null) {
                    executeRequest("PATCH", webhookUrl + "/messages/" + currentMessageId, payload.toString());
                } else {
                    executeRequest("POST", webhookUrl, payload.toString());
                }
            } catch (Exception error) {
                if (log != null) log.write("discord_stop_error", error.toString());
            }
        }, "silph-discord-stop").start();
    }

    private void loop() {
        long lastUpdate = 0;
        while (running) {
            try {
                long now = SystemClock.elapsedRealtime();

                // Check 1-hour boundary (3600000 ms)
                if (now - lastHourMarkElapsed >= 3600000L) {
                    completedHours++;
                    sendHourlyReportAndRotate(now);
                    lastHourMarkElapsed = now;
                    lastUpdate = now;
                } else if (now - lastUpdate >= 10000L) {
                    sendOrUpdateLiveStatus(now);
                    lastUpdate = now;
                }

                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                break;
            } catch (Exception error) {
                if (log != null) log.write("discord_loop_error", error.toString());
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    private void sendOrUpdateLiveStatus(long now) {
        try {
            JSONObject payload = buildLivePayload(now);
            if (currentMessageId == null) {
                String response = executeRequest("POST", webhookUrl + "?wait=true", payload.toString());
                if (response != null && !response.isEmpty()) {
                    JSONObject obj = new JSONObject(response);
                    currentMessageId = obj.optString("id", null);
                }
            } else {
                int code = executeRequestCode("PATCH", webhookUrl + "/messages/" + currentMessageId, payload.toString());
                if (code == 404) {
                    String response = executeRequest("POST", webhookUrl + "?wait=true", payload.toString());
                    if (response != null && !response.isEmpty()) {
                        JSONObject obj = new JSONObject(response);
                        currentMessageId = obj.optString("id", null);
                    }
                }
            }
        } catch (Exception error) {
            if (log != null) log.write("discord_live_update_error", error.toString());
        }
    }

    private void sendHourlyReportAndRotate(long now) {
        try {
            JSONObject reportPayload = buildHourlyReportPayload(completedHours);
            if (currentMessageId != null) {
                executeRequest("PATCH", webhookUrl + "/messages/" + currentMessageId, reportPayload.toString());
            } else {
                executeRequest("POST", webhookUrl, reportPayload.toString());
            }

            hourBaselineTotals = currentTotals;
            currentMessageId = null;

            JSONObject nextLivePayload = buildLivePayload(now);
            String response = executeRequest("POST", webhookUrl + "?wait=true", nextLivePayload.toString());
            if (response != null && !response.isEmpty()) {
                JSONObject obj = new JSONObject(response);
                currentMessageId = obj.optString("id", null);
            }
        } catch (Exception error) {
            if (log != null) log.write("discord_hourly_error", error.toString());
        }
    }

    private JSONObject buildLivePayload(long now) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("username", "SilphMod");

        JSONArray embeds = new JSONArray();
        JSONObject embed = new JSONObject();

        int color = COLOR_ACTIVE;
        if (currentStatus.contains("Paused")) {
            color = COLOR_PAUSED;
        } else if (currentStatus.contains("error") || currentStatus.contains("Error") || currentStatus.contains("failed")) {
            color = COLOR_ERROR;
        }
        embed.put("color", color);

        JSONObject author = new JSONObject();
        author.put("name", "SilphMod · " + (mode == 1 ? "Farmer Mode" : "Kecleon Mode (BETA)"));
        embed.put("author", author);

        embed.put("title", "Live Farming Status");

        long sessionElapsedMs = settings != null ? settings.elapsed(mode) : (now - sessionStartElapsed);
        long sec = sessionElapsedMs / 1000;
        String timeStr = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);

        JSONArray fields = new JSONArray();
        fields.put(makeField("State", "`" + currentStatus + "`", true));
        fields.put(makeField("Uptime", "`" + timeStr + "`", true));
        fields.put(makeField("Engine", "`v2.0.15 Native C`", true));

        NativeSessionState.Totals t = currentTotals != null ? currentTotals : new NativeSessionState.Totals();
        if (mode == 1) {
            fields.put(makeField("Catches", String.format(Locale.US, "**%,d**", t.caught), true));
            fields.put(makeField("PokéStops", String.format(Locale.US, "**%,d**", t.spun), true));
            fields.put(makeField("Transferred", String.format(Locale.US, "**%,d**", t.transferred), true));
            fields.put(makeField("Items Deleted", String.format(Locale.US, "**%,d**", t.discarded), true));
            fields.put(makeField("XP", t.xp > 0 ? String.format(Locale.US, "**%,d**", t.xp) : "—", true));
            fields.put(makeField("Stardust", t.stardust > 0 ? String.format(Locale.US, "**%,d**", t.stardust) : "—", true));
        } else {
            fields.put(makeField("Encounters", String.format(Locale.US, "**%,d**", t.encounters), true));
            fields.put(makeField("Catches", String.format(Locale.US, "**%,d**", t.caught), true));
            fields.put(makeField("Shinies", String.format(Locale.US, "**%,d**", t.shinies), true));
            fields.put(makeField("Items Deleted", String.format(Locale.US, "**%,d**", t.discarded), true));
            fields.put(makeField("XP", t.xp > 0 ? String.format(Locale.US, "**%,d**", t.xp) : "—", true));
            fields.put(makeField("Stardust", t.stardust > 0 ? String.format(Locale.US, "**%,d**", t.stardust) : "—", true));
        }

        embed.put("fields", fields);

        JSONObject footer = new JSONObject();
        footer.put("text", "Updates every 10s • SilphMod Automation");
        embed.put("footer", footer);
        embed.put("timestamp", getUtcIsoTimestamp());

        embeds.put(embed);
        payload.put("embeds", embeds);
        return payload;
    }

    private JSONObject buildHourlyReportPayload(int hourNumber) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("username", "SilphMod");

        JSONArray embeds = new JSONArray();
        JSONObject embed = new JSONObject();

        embed.put("color", COLOR_HOURLY);

        JSONObject author = new JSONObject();
        author.put("name", "SilphMod · 1-Hour Report (Hour " + hourNumber + ")");
        embed.put("author", author);

        embed.put("title", "Hour " + hourNumber + " Summary Report");

        NativeSessionState.Totals curr = currentTotals != null ? currentTotals : new NativeSessionState.Totals();
        NativeSessionState.Totals base = hourBaselineTotals != null ? hourBaselineTotals : new NativeSessionState.Totals();

        long hourCaught = Math.max(0, curr.caught - base.caught);
        long hourSpun = Math.max(0, curr.spun - base.spun);
        long hourTransferred = Math.max(0, curr.transferred - base.transferred);
        long hourDiscarded = Math.max(0, curr.discarded - base.discarded);
        long hourEncounters = Math.max(0, curr.encounters - base.encounters);
        long hourShinies = Math.max(0, curr.shinies - base.shinies);
        long hourXp = Math.max(0, curr.xp - base.xp);
        long hourDust = Math.max(0, curr.stardust - base.stardust);

        JSONArray fields = new JSONArray();
        fields.put(makeField("Interval", "Hour " + hourNumber + " (60 mins)", true));
        fields.put(makeField("Mode", mode == 1 ? "Farmer" : "Kecleon", true));
        fields.put(makeField("Status", "Archived Hour Report", true));

        if (mode == 1) {
            fields.put(makeField("Catches this Hour", String.format(Locale.US, "+%,d *(Total %,d)*", hourCaught, curr.caught), false));
            fields.put(makeField("Spins this Hour", String.format(Locale.US, "+%,d *(Total %,d)*", hourSpun, curr.spun), false));
            fields.put(makeField("Transferred", String.format(Locale.US, "+%,d *(Total %,d)*", hourTransferred, curr.transferred), true));
            fields.put(makeField("Items Cleaned", String.format(Locale.US, "+%,d *(Total %,d)*", hourDiscarded, curr.discarded), true));
            if (curr.xp > 0) fields.put(makeField("XP Gained", String.format(Locale.US, "+%,d", hourXp), true));
            if (curr.stardust > 0) fields.put(makeField("Stardust Gained", String.format(Locale.US, "+%,d", hourDust), true));
        } else {
            fields.put(makeField("Encounters this Hour", String.format(Locale.US, "+%,d *(Total %,d)*", hourEncounters, curr.encounters), false));
            fields.put(makeField("Catches this Hour", String.format(Locale.US, "+%,d *(Total %,d)*", hourCaught, curr.caught), false));
            fields.put(makeField("Shinies Found", String.format(Locale.US, "+%,d *(Total %,d)*", hourShinies, curr.shinies), true));
            fields.put(makeField("Items Cleaned", String.format(Locale.US, "+%,d *(Total %,d)*", hourDiscarded, curr.discarded), true));
            if (curr.xp > 0) fields.put(makeField("XP Gained", String.format(Locale.US, "+%,d", hourXp), true));
            if (curr.stardust > 0) fields.put(makeField("Stardust Gained", String.format(Locale.US, "+%,d", hourDust), true));
        }

        embed.put("fields", fields);

        JSONObject footer = new JSONObject();
        footer.put("text", "Hourly Milestone Completed");
        embed.put("footer", footer);
        embed.put("timestamp", getUtcIsoTimestamp());

        embeds.put(embed);
        payload.put("embeds", embeds);
        return payload;
    }

    private JSONObject buildStoppedPayload(String reason, boolean cleanupConfirmed) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("username", "SilphMod");

        JSONArray embeds = new JSONArray();
        JSONObject embed = new JSONObject();

        embed.put("color", COLOR_STOPPED);

        JSONObject author = new JSONObject();
        author.put("name", "SilphMod · Session Ended");
        embed.put("author", author);

        embed.put("title", "Service Stopped");
        embed.put("description", "Session terminated: **" + (reason == null || reason.isEmpty() ? "Stopped by user" : reason) + "**\nCleanup: `" + (cleanupConfirmed ? "Confirmed" : "Not confirmed") + "`");

        NativeSessionState.Totals t = currentTotals != null ? currentTotals : new NativeSessionState.Totals();
        JSONArray fields = new JSONArray();
        long sessionElapsedMs = settings != null ? settings.elapsed(mode) : 0;
        long sec = sessionElapsedMs / 1000;
        String timeStr = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);

        fields.put(makeField("Total Run Time", "`" + timeStr + "`", true));
        fields.put(makeField("Final Status", "`Stopped`", true));

        if (mode == 1) {
            fields.put(makeField("Total Catches", String.format(Locale.US, "**%,d**", t.caught), true));
            fields.put(makeField("Total Spins", String.format(Locale.US, "**%,d**", t.spun), true));
            fields.put(makeField("Transferred", String.format(Locale.US, "**%,d**", t.transferred), true));
            fields.put(makeField("Items Deleted", String.format(Locale.US, "**%,d**", t.discarded), true));
        } else {
            fields.put(makeField("Encounters", String.format(Locale.US, "**%,d**", t.encounters), true));
            fields.put(makeField("Catches", String.format(Locale.US, "**%,d**", t.caught), true));
            fields.put(makeField("Shinies", String.format(Locale.US, "**%,d**", t.shinies), true));
        }

        embed.put("fields", fields);

        JSONObject footer = new JSONObject();
        footer.put("text", "SilphMod Finished");
        embed.put("footer", footer);
        embed.put("timestamp", getUtcIsoTimestamp());

        embeds.put(embed);
        payload.put("embeds", embeds);
        return payload;
    }

    private JSONObject makeField(String name, String value, boolean inline) throws Exception {
        JSONObject field = new JSONObject();
        field.put("name", name);
        field.put("value", value);
        field.put("inline", inline);
        return field;
    }

    private String getUtcIsoTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private String executeRequest(String method, String targetUrl, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("User-Agent", "SilphMod/2.0.15");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int len;
            while ((len = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, len, StandardCharsets.UTF_8));
            }
            is.close();
            return sb.toString();
        }
        return "";
    }

    private int executeRequestCode(String method, String targetUrl, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("User-Agent", "SilphMod/2.0.15");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }
}
