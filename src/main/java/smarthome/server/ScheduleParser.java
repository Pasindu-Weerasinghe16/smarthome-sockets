package smarthome.server;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
            if (ts.startsWith("+")) {
                long sec = Long.parseLong(ts.substring(1));
                when = now + sec * 1000L;
            } else {
                try {
                    if (ts.endsWith("Z")) {
                        when = Instant.parse(ts).toEpochMilli();
                    } else {
                        LocalDateTime ldt = LocalDateTime.parse(ts);
                        when = ldt.atZone(zone).toInstant().toEpochMilli();
                    }
                } catch (DateTimeParseException ex) {
                    continue;
                }
            }
            out.add(new Entry(when, parts[1], parts[2].toUpperCase()));
        }
        return out;
    }
}
