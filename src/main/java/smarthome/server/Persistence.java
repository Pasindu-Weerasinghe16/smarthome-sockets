package smarthome.server;

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded SQLite persistence.
 *
 * Tracks schedule entries, last known device state, and a full history of
 * state-change events so that "device ON time for any selected day" can be
 * computed accurately.
 *
 * Schema additions (migrated safely via ALTER TABLE ADD COLUMN):
 *   devices.on_since_ms           – epoch-ms when the current ON session started (NULL if OFF)
 *   devices.last_state_change_ms  – epoch-ms of the most recent state change
 *
 * New table:
 *   device_state_events(id PK, device_id, ts_ms, state)
 *     – one row per state transition; used to calculate ON-time for any window
 */
public final class Persistence {
    private final String jdbcUrl;

    public Persistence(Path dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    }

    // ── Schema ──────────────────────────────────────────────────────────────

    public void init() {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate("PRAGMA synchronous=NORMAL");

                // Core devices table (unchanged columns kept for backwards compat)
                st.executeUpdate("CREATE TABLE IF NOT EXISTS devices (" +
                        "device_id             TEXT PRIMARY KEY," +
                        "connected             INTEGER NOT NULL DEFAULT 0," +
                        "last_seen_ms          INTEGER NOT NULL DEFAULT 0," +
                        "state                 TEXT," +
                        "on_since_ms           INTEGER," +
                        "last_state_change_ms  INTEGER NOT NULL DEFAULT 0" +
                        ")");

                // Safe migrations for databases that pre-date these columns
                safeAddColumn(st, "devices", "on_since_ms",          "INTEGER");
                safeAddColumn(st, "devices", "last_state_change_ms", "INTEGER NOT NULL DEFAULT 0");

                // Schedule entries
                st.executeUpdate("CREATE TABLE IF NOT EXISTS schedule_entries (" +
                        "id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "when_ms      INTEGER NOT NULL," +
                        "device_id    TEXT    NOT NULL," +
                        "action       TEXT    NOT NULL," +
                        "created_at_ms INTEGER NOT NULL" +
                        ")");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_schedule_when " +
                        "ON schedule_entries(when_ms)");

                // Full state-change history – enables per-day ON-time queries
                st.executeUpdate("CREATE TABLE IF NOT EXISTS device_state_events (" +
                        "id        INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "device_id TEXT    NOT NULL," +
                        "ts_ms     INTEGER NOT NULL," +
                        "state     TEXT    NOT NULL" +
                        ")");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_device_ts " +
                        "ON device_state_events(device_id, ts_ms)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB init failed: " + e.getMessage(), e);
        }
    }

    private static void safeAddColumn(Statement st, String table, String col, String type) {
        try {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
        } catch (SQLException ignored) {
            // column already exists
        }
    }

    // ── Device connection ────────────────────────────────────────────────────

    /**
     * Called when a device connects (connected=true) or disconnects (connected=false).
     * On disconnect, if the device was last known to be ON, a synthetic OFF event is
     * inserted so that ON-time is not counted past the disconnect moment.
     */
    public void upsertDeviceConnected(String deviceId, boolean connected, long lastSeenMs) {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            if (!connected) {
                // Close any open ON session at the disconnect timestamp
                String prevState = readDeviceState(c, deviceId);
                if ("ON".equals(prevState)) {
                    insertStateEvent(c, deviceId, lastSeenMs, "OFF");
                }
            }
            String sql;
            if (connected) {
                sql = "INSERT INTO devices(device_id, connected, last_seen_ms, state) VALUES(?,?,?,NULL) " +
                      "ON CONFLICT(device_id) DO UPDATE SET " +
                      "connected=excluded.connected, last_seen_ms=excluded.last_seen_ms";
            } else {
                sql = "INSERT INTO devices(device_id, connected, last_seen_ms, state) VALUES(?,?,?,NULL) " +
                      "ON CONFLICT(device_id) DO UPDATE SET " +
                      "connected=excluded.connected, last_seen_ms=excluded.last_seen_ms, on_since_ms=NULL";
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, deviceId);
                ps.setInt(2, connected ? 1 : 0);
                ps.setLong(3, lastSeenMs);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB upsert device failed: " + e.getMessage(), e);
        }
    }

    // ── Device state (STATUS messages) ──────────────────────────────────────

    /**
     * Records a state update received from a device.
     * <ul>
     *   <li>A state-change event is inserted into {@code device_state_events} only when
     *       the state actually differs from the previous value (deduplication).
     *   <li>When transitioning ON→x, {@code on_since_ms} is cleared.
     *   <li>When transitioning x→ON, {@code on_since_ms} is set to {@code nowMs}.
     *   <li>While staying ON across multiple STATUS messages, {@code on_since_ms} is preserved.
     * </ul>
     */
    public void updateDeviceState(String deviceId, String state, long nowMs) {
        String normalised = (state == null) ? "UNKNOWN" : state.trim().toUpperCase();
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            String prevState  = readDeviceState(c, deviceId);
            boolean wasOn     = "ON".equals(prevState);
            boolean isOn      = "ON".equals(normalised);
            boolean changed   = !normalised.equals(prevState);

            if (changed) {
                insertStateEvent(c, deviceId, nowMs, normalised);
            }

            Long newOnSince;
            if (isOn && !wasOn) {
                // Starting a new ON session
                newOnSince = nowMs;
            } else if (isOn) {
                // Staying ON – keep the existing session start.
                // After a DB migration the column may be NULL; treat that as a new session
                // start and insert a synthetic ON event so computeOnTime can see it.
                Long existing = readOnSinceMs(c, deviceId);
                if (existing == null) {
                    newOnSince = nowMs;
                    insertStateEvent(c, deviceId, nowMs, "ON"); // synthetic start event
                } else {
                    newOnSince = existing;
                }
            } else {
                // OFF or unknown
                newOnSince = null;
            }

            long stateChangeMs = changed ? nowMs : readLastStateChangeMs(c, deviceId);

            String sql = "INSERT INTO devices(" +
                    "device_id, connected, last_seen_ms, state, on_since_ms, last_state_change_ms" +
                    ") VALUES(?,1,?,?,?,?) " +
                    "ON CONFLICT(device_id) DO UPDATE SET " +
                    "connected=1, last_seen_ms=excluded.last_seen_ms, state=excluded.state, " +
                    "on_since_ms=excluded.on_since_ms, " +
                    "last_state_change_ms=excluded.last_state_change_ms";

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, deviceId);
                ps.setLong(2, nowMs);
                ps.setString(3, normalised);
                if (newOnSince != null) ps.setLong(4, newOnSince); else ps.setNull(4, Types.INTEGER);
                ps.setLong(5, stateChangeMs);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB update state failed: " + e.getMessage(), e);
        }
    }

    // ── Low-level column readers ─────────────────────────────────────────────

    private String readDeviceState(Connection c, String deviceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT state FROM devices WHERE device_id=?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Long readOnSinceMs(Connection c, String deviceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT on_since_ms FROM devices WHERE device_id=?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private long readLastStateChangeMs(Connection c, String deviceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT last_state_change_ms FROM devices WHERE device_id=?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private void insertStateEvent(Connection c, String deviceId, long tsMs, String state)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO device_state_events(device_id, ts_ms, state) VALUES(?,?,?)")) {
            ps.setString(1, deviceId);
            ps.setLong(2, tsMs);
            ps.setString(3, state);
            ps.executeUpdate();
        }
    }

    // ── Stats queries ────────────────────────────────────────────────────────

    /** Snapshot of usage metrics for one device. */
    public record DeviceStats(
            String  deviceId,
            boolean connected,
            String  state,
            long    lastSeenMs,
            long    lastStateChangeMs,
            long    totalOnMs,    // all-time ON (closed sessions + current open session)
            long    todayOnMs,    // ON time since local midnight (server zone)
            long    currentOnMs   // duration of the current open ON session (0 if OFF)
    ) {}

    /**
     * Returns usage stats for every device known to the database,
     * including currently-offline devices.
     *
     * @param nowMs  epoch-ms to treat as "now" (used for the open session end)
     * @param zone   server time-zone (determines today's midnight boundary)
     */
    public List<DeviceStats> getAllDeviceStats(long nowMs, ZoneId zone) {
        List<DeviceStats> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT device_id, connected, state, last_seen_ms, " +
                     "on_since_ms, last_state_change_ms FROM devices ORDER BY device_id");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String  id        = rs.getString(1);
                boolean conn      = rs.getInt(2) != 0;
                String  st        = rs.getString(3);
                long    lastSeen  = rs.getLong(4);
                long    onSince   = rs.getLong(5); boolean onSinceNull = rs.wasNull();
                long    stateChg  = rs.getLong(6);

                long curOnMs = (!onSinceNull && "ON".equals(st))
                               ? Math.max(0, nowMs - onSince) : 0L;

                // Compute only *closed* sessions by stopping history at the point the
                // current session started (avoids double-counting the open session).
                long histEnd   = (!onSinceNull && "ON".equals(st)) ? onSince : nowMs;
                long closedMs  = computeOnTime(c, id, 0L, histEnd);
                long totalOnMs = closedMs + curOnMs;

                long dayStartMs = LocalDate.now(zone).atStartOfDay(zone)
                                           .toInstant().toEpochMilli();
                // Closed sessions that overlap today
                long dayHistEnd    = Math.max(dayStartMs, histEnd);
                long todayClosedMs = computeOnTime(c, id, dayStartMs, dayHistEnd);
                // Today's portion of the current open session
                long curTodayMs = curOnMs > 0
                        ? Math.max(0L, nowMs - Math.max(dayStartMs, onSince))
                        : 0L;
                long todayOnMs = todayClosedMs + curTodayMs;

                out.add(new DeviceStats(id, conn, st, lastSeen, stateChg,
                                        totalOnMs, todayOnMs, curOnMs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB getAllDeviceStats failed: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Returns the total milliseconds the device was ON during the given calendar day
     * (interpreted in {@code zone}).
     *
     * <p>If no state history exists before the day begins, the device is assumed to
     * have been OFF at the start of the day.
     */
    public long getDeviceOnTimeForDate(String deviceId, LocalDate date, ZoneId zone) {
        ZonedDateTime start = date.atStartOfDay(zone);
        ZonedDateTime end   = date.plusDays(1).atStartOfDay(zone);
        long fromMs = start.toInstant().toEpochMilli();
        long toMs   = end.toInstant().toEpochMilli();
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            return computeOnTime(c, deviceId, fromMs, toMs);
        } catch (SQLException e) {
            throw new RuntimeException("DB getDeviceOnTimeForDate failed: " + e.getMessage(), e);
        }
    }

    /**
     * Core ON-time algorithm for an arbitrary time window [fromMs, toMs).
     *
     * <ol>
     *   <li>Determines the device's state just before {@code fromMs} by looking at
     *       the most recent event before the window (defaults to OFF if none).
     *   <li>Walks every event inside the window and accumulates durations where
     *       the device is ON, clamped to the window boundaries.
     *   <li>If the device is still ON at {@code toMs}, counts up to that boundary.
     * </ol>
     */
    private long computeOnTime(Connection c, String deviceId, long fromMs, long toMs)
            throws SQLException {
        // 1. State just before the window
        String initialState = "OFF";
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT state FROM device_state_events " +
                "WHERE device_id=? AND ts_ms<? ORDER BY ts_ms DESC LIMIT 1")) {
            ps.setString(1, deviceId);
            ps.setLong(2, fromMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) initialState = rs.getString(1);
            }
        }

        // 2. Walk events inside [fromMs, toMs)
        long total  = 0L;
        long onStart = "ON".equals(initialState) ? fromMs : -1L;

        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ts_ms, state FROM device_state_events " +
                "WHERE device_id=? AND ts_ms>=? AND ts_ms<? ORDER BY ts_ms")) {
            ps.setString(1, deviceId);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long   ts = rs.getLong(1);
                    String ev = rs.getString(2);
                    if ("ON".equals(ev)) {
                        if (onStart < 0) onStart = ts;
                    } else {
                        if (onStart >= 0) {
                            total  += (ts - onStart);
                            onStart = -1L;
                        }
                    }
                }
            }
        }

        // 3. Open session reaches the end of the window
        if (onStart >= 0) {
            total += (toMs - onStart);
        }
        return total;
    }

    // ── Schedule ─────────────────────────────────────────────────────────────

    // ── Device management ─────────────────────────────────────────────────────

    /** Removes a device and all its history entirely from the database. */
    public void deleteDevice(String deviceId) {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM device_state_events WHERE device_id=?")) {
                ps.setString(1, deviceId); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM devices WHERE device_id=?")) {
                ps.setString(1, deviceId); ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB deleteDevice failed: " + e.getMessage(), e);
        }
    }

    /**
     * Clears a device's state-change history (resets ON-time counters to zero)
     * without removing the device record itself.
     */
    public void clearDeviceHistory(String deviceId) {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM device_state_events WHERE device_id=?")) {
                ps.setString(1, deviceId); ps.executeUpdate();
            }
            // Reset session tracking so existing ON state looks like it just started
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE devices SET on_since_ms=NULL, last_state_change_ms=0 WHERE device_id=?")) {
                ps.setString(1, deviceId); ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB clearDeviceHistory failed: " + e.getMessage(), e);
        }
    }

    // ── Schedule ─────────────────────────────────────────────────────────────

    public void insertScheduleEntries(List<ScheduleParser.Entry> entries) {
        if (entries == null || entries.isEmpty()) return;
        String sql = "INSERT INTO schedule_entries(when_ms, device_id, action, created_at_ms) VALUES(?,?,?,?)";
        long createdAt = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            for (ScheduleParser.Entry e : entries) {
                ps.setLong(1, e.epochMillis());
                ps.setString(2, e.deviceId());
                ps.setString(3, e.action());
                ps.setLong(4, createdAt);
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("DB insert schedule failed: " + e.getMessage(), e);
        }
    }

    public List<ScheduleParser.Entry> loadPendingScheduleEntries(long fromMs) {
        String sql = "SELECT when_ms, device_id, action FROM schedule_entries " +
                     "WHERE when_ms >= ? ORDER BY when_ms";
        List<ScheduleParser.Entry> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, fromMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ScheduleParser.Entry(
                            rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB load schedule failed: " + e.getMessage(), e);
        }
        return out;
    }

    public void cleanupScheduleOlderThan(long beforeMs) {
        String sql = "DELETE FROM schedule_entries WHERE when_ms < ?";
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, beforeMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB cleanup failed: " + e.getMessage(), e);
        }
    }
}
