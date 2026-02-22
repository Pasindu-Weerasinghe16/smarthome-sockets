package smarthome.server;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal embedded SQLite persistence.
 * Stores schedule entries and last known device state / last-seen.
 */
public final class Persistence {
    private final String jdbcUrl;

    public Persistence(Path dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    }

    public void init() {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate("PRAGMA synchronous=NORMAL");

                st.executeUpdate("CREATE TABLE IF NOT EXISTS devices (" +
                        "device_id TEXT PRIMARY KEY," +
                        "connected INTEGER NOT NULL," +
                        "last_seen_ms INTEGER NOT NULL," +
                        "state TEXT" +
                        ")");

                st.executeUpdate("CREATE TABLE IF NOT EXISTS schedule_entries (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "when_ms INTEGER NOT NULL," +
                        "device_id TEXT NOT NULL," +
                        "action TEXT NOT NULL," +
                        "created_at_ms INTEGER NOT NULL" +
                        ")");

                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_schedule_when ON schedule_entries(when_ms)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB init failed: " + e.getMessage(), e);
        }
    }

    public void upsertDeviceConnected(String deviceId, boolean connected, long lastSeenMs) {
        String sql = "INSERT INTO devices(device_id, connected, last_seen_ms, state) VALUES(?,?,?,NULL) " +
                "ON CONFLICT(device_id) DO UPDATE SET connected=excluded.connected, last_seen_ms=excluded.last_seen_ms";
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setInt(2, connected ? 1 : 0);
            ps.setLong(3, lastSeenMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB upsert device failed: " + e.getMessage(), e);
        }
    }

    public void updateDeviceState(String deviceId, String state, long lastSeenMs) {
        String sql = "INSERT INTO devices(device_id, connected, last_seen_ms, state) VALUES(?,?,?,?) " +
                "ON CONFLICT(device_id) DO UPDATE SET last_seen_ms=excluded.last_seen_ms, state=excluded.state";
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setInt(2, 1);
            ps.setLong(3, lastSeenMs);
            ps.setString(4, state);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB update state failed: " + e.getMessage(), e);
        }
    }

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
        String sql = "SELECT when_ms, device_id, action FROM schedule_entries WHERE when_ms >= ? ORDER BY when_ms";
        List<ScheduleParser.Entry> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, fromMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ScheduleParser.Entry(rs.getLong(1), rs.getString(2), rs.getString(3)));
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
