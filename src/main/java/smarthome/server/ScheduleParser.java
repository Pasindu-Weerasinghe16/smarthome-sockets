package smarthome.server;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV format (one per line):
 * ISO_INSTANT,DEVICE_ID,ACTION
 * e.g., 2025-11-11T18:30:00Z,LIGHT1,ON
 * or relative seconds: +60,FAN1,OFF  (means 60s from now)
 */
public final class ScheduleParser {

    private static final DateTimeFormatter LOCAL_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter LOCAL_SEC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record Entry(long epochMillis, String deviceId, String action) {}

    public static List<Entry> parse(String csv, ZoneId zone) {
        List<Entry> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;

        String[] lines = csv.replace("\r", "").split("\n");
        long now = System.currentTimeMillis();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s*,\\s*");
            if (parts.length != 3) continue;

            long when;
            String ts = parts[0];
            try {
                when = parseTimestamp(ts, now, zone);
            } catch (RuntimeException ex) {
                continue;
            }
            out.add(new Entry(when, parts[1], parts[2].toUpperCase()));
        }
        return out;
    }

    /**
     * Supported timestamp formats:
     * - Relative seconds: +60
     * - Relative durations: 30s, 5m, 2h
     * - ISO instant UTC: 2025-11-11T18:30:00Z
     * - Local datetime (server zone): 2026-02-22 18:30  or  2026-02-22 18:30:00
     * - ISO local datetime: 2026-02-22T18:30:00
     * - Epoch seconds or millis: 1740258600 or 1740258600000
     */
    private static long parseTimestamp(String tsRaw, long nowMs, ZoneId zone) {
        String ts = tsRaw.trim();
        if (ts.isEmpty()) throw new IllegalArgumentException("empty");

        if (ts.startsWith("+")) {
            long sec = Long.parseLong(ts.substring(1));
            return nowMs + sec * 1000L;
        }

        // Duration with unit, relative from now
        if (ts.length() >= 2) {
            char unit = Character.toLowerCase(ts.charAt(ts.length() - 1));
            String num = ts.substring(0, ts.length() - 1);
            if (num.chars().allMatch(Character::isDigit) && (unit == 's' || unit == 'm' || unit == 'h')) {
                long n = Long.parseLong(num);
                long sec = switch (unit) {
                    case 's' -> n;
                    case 'm' -> n * 60L;
                    case 'h' -> n * 3600L;
                    default -> throw new IllegalArgumentException("unit");
                };
                return nowMs + sec * 1000L;
            }
        }

        // Epoch seconds or milliseconds
        if (ts.chars().allMatch(Character::isDigit)) {
            long v = Long.parseLong(ts);
            if (ts.length() <= 10) {
                return v * 1000L;
            }
            return v;
        }

        // ISO instant (UTC)
        if (ts.endsWith("Z")) {
            return Instant.parse(ts).toEpochMilli();
        }

        // Local date time with space
        if (ts.indexOf(' ') > 0) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(ts, LOCAL_SEC);
                return ldt.atZone(zone).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                LocalDateTime ldt = LocalDateTime.parse(ts, LOCAL_MIN);
                return ldt.atZone(zone).toInstant().toEpochMilli();
            }
        }

        // ISO local date time (no zone)
        LocalDateTime ldt = LocalDateTime.parse(ts);
        return ldt.atZone(zone).toInstant().toEpochMilli();
    }
}
