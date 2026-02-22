package smarthome.server;

import smarthome.common.*;

import java.io.*;
import java.net.Socket;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientSession implements Runnable {
    private static final Logger log = Logger.getLogger(ClientSession.class.getName());

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final DeviceRegistry registry;
    private final CommandScheduler scheduler;
    private final ExecutorService writerPool;
    private final Persistence persistence;
    private final OwnerHub ownerHub;
    private String role;
    private String deviceId;
    private boolean subscribedToPush;

    public ClientSession(Socket socket,
                         DeviceRegistry registry,
                         CommandScheduler scheduler,
                         ExecutorService writerPool,
                         Persistence persistence,
                         OwnerHub ownerHub) throws IOException {
        this.socket = socket;
        this.registry = registry;
        this.scheduler = scheduler;
        this.writerPool = writerPool;
        this.persistence = persistence;
        this.ownerHub = ownerHub;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    @Override
    public void run() {
        try (socket; in; out) {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            var reg = read();
            if (reg.type != MessageType.REGISTER) {
                send(MessageType.ERROR, Map.of("msg", "First message must be REGISTER"));
                return;
            }
            this.role = reg.role;
            this.deviceId = reg.deviceId;

            if ("device".equalsIgnoreCase(role)) {
                if (deviceId == null || deviceId.isBlank()) {
                    send(MessageType.ERROR, Map.of("msg", "deviceId required for device REGISTER"));
                    return;
                }
                registry.register(deviceId, this);
                try {
                    persistence.upsertDeviceConnected(deviceId, true, System.currentTimeMillis());
                } catch (RuntimeException ignored) {
                    // persistence is optional
                }
                log.info(() -> "Device connected: " + deviceId + " from " + socket.getRemoteSocketAddress());
                send(MessageType.ACK, Map.of("msg", "REGISTERED"));
            } else {
                this.role = "owner";
                boolean subStatus = bool(reg.payload, "subscribeStatus");
                boolean subLogs = bool(reg.payload, "subscribeDeviceLogs");
                this.subscribedToPush = subStatus || subLogs;
                if (subscribedToPush) {
                    ownerHub.subscribe(this);
                }
                log.info(() -> "Owner connected from " + socket.getRemoteSocketAddress());
                send(MessageType.ACK, Map.of("msg", "OWNER_OK"));
            }

            while (true) {
                Message m = read();
                switch (m.type) {
                    case LIST_DEVICES -> {
                        send(MessageType.DEVICES, Map.of("devices", List.of(registry.listDeviceIds())));
                    }
                    case COMMAND -> {
                        String target = str(m.payload, "deviceId");
                        String action = str(m.payload, "action").toUpperCase();
                        var targetSession = registry.get(target);
                        if (targetSession == null) {
                            send(MessageType.ERROR, Map.of("msg", "Device not connected: " + target));
                        } else {
                            targetSession.sendAsync(m);
                            send(MessageType.ACK, Map.of("msg", "FORWARDED", "deviceId", target, "action", action));
                        }
                    }
                    case STATUS, ACK -> {
                        log.info(() -> "From " + who() + ": " + Json.toJson(m));
                        if (m.type == MessageType.STATUS) {
                            String dev = m.deviceId;
                            String state = str(m.payload, "state");
                            if (dev != null && state != null) {
                                try {
                                    persistence.updateDeviceState(dev, state, System.currentTimeMillis());
                                } catch (RuntimeException ignored) {
                                }
                            }

                            // Broadcast live device state updates to subscribed owners.
                            try {
                                ownerHub.broadcast(m);
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                    case DEVICE_LOG -> {
                        // Forward device console lines to subscribed owners
                        try {
                            ownerHub.broadcast(m);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    case UPLOAD_SCHEDULE -> {
                        String content = str(m.payload, "content");
                        var entries = ScheduleParser.parse(content, HomeServer.SERVER_ZONE);
                        try {
                            persistence.insertScheduleEntries(entries);
                        } catch (RuntimeException ignored) {
                        }
                        scheduler.schedule(entries);
                        send(MessageType.SCHEDULE_ACCEPTED, Map.of("count", entries.size()));
                    }
                    case GET_DEVICE_STATS -> {
                        List<Persistence.DeviceStats> stats = persistence.getAllDeviceStats(
                                System.currentTimeMillis(), HomeServer.SERVER_ZONE);
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (Persistence.DeviceStats s : stats) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("deviceId",           s.deviceId());
                            row.put("connected",          s.connected());
                            row.put("state",              s.state());
                            row.put("totalOnMs",          s.totalOnMs());
                            row.put("todayOnMs",          s.todayOnMs());
                            row.put("currentOnMs",        s.currentOnMs());
                            row.put("lastSeenMs",         s.lastSeenMs());
                            row.put("lastStateChangeMs",  s.lastStateChangeMs());
                            rows.add(row);
                        }
                        send(MessageType.DEVICE_STATS, Map.of("stats", rows));
                    }
                    case GET_DEVICE_USAGE -> {
                        String devId   = str(m.payload, "deviceId");
                        String dateStr = str(m.payload, "date");
                        if (devId == null || dateStr == null) {
                            send(MessageType.ERROR, Map.of("msg", "deviceId and date required"));
                            break;
                        }
                        LocalDate date;
                        try {
                            date = LocalDate.parse(dateStr);
                        } catch (Exception ex) {
                            send(MessageType.ERROR, Map.of("msg", "Invalid date: " + dateStr));
                            break;
                        }
                        long onMs = persistence.getDeviceOnTimeForDate(
                                devId, date, HomeServer.SERVER_ZONE);
                        send(MessageType.DEVICE_USAGE,
                                Map.of("deviceId", devId, "date", dateStr, "onMs", onMs));
                    }
                    case DELETE_DEVICE -> {
                        String devId = str(m.payload, "deviceId");
                        if (devId == null) {
                            send(MessageType.ERROR, Map.of("msg", "deviceId required"));
                            break;
                        }
                        persistence.deleteDevice(devId);
                        send(MessageType.ACK, Map.of("msg", "DELETED", "deviceId", devId));
                    }
                    case CLEAR_DEVICE_HISTORY -> {
                        String devId = str(m.payload, "deviceId");
                        if (devId == null) {
                            send(MessageType.ERROR, Map.of("msg", "deviceId required"));
                            break;
                        }
                        persistence.clearDeviceHistory(devId);
                        send(MessageType.ACK, Map.of("msg", "HISTORY_CLEARED", "deviceId", devId));
                    }
                    default -> send(MessageType.ERROR, Map.of("msg", "Unsupported type: " + m.type));
                }
            }
        } catch (EOFException eof) {
            log.info(() -> who() + " disconnected");
        } catch (Exception e) {
            log.log(Level.WARNING, who() + " error: " + e.getMessage(), e);
        } finally {
            registry.unregister(deviceId);
            if (deviceId != null) {
                try {
                    persistence.upsertDeviceConnected(deviceId, false, System.currentTimeMillis());
                } catch (RuntimeException ignored) {
                }
            }
            if (subscribedToPush) {
                ownerHub.unsubscribe(this);
            }
        }
    }

    private String who() {
        return role == null ? "unknown" : ("device".equals(role) ? "device[" + deviceId + "]" : "owner");
    }

    private static String str(Map<String, Object> p, String k) {
        Object v = p == null ? null : p.get(k);
        return v == null ? null : v.toString();
    }

    private static boolean bool(Map<String, Object> p, String k) {
        Object v = p == null ? null : p.get(k);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(v.toString());
    }

    private Message read() throws IOException {
        String json = FrameIO.readJsonFrame(in);
        return Json.fromJson(json, Message.class);
    }

    private synchronized void send(MessageType type, Map<String, Object> payload) throws IOException {
        Message m = Message.of(type);
        m.payload = payload;
        FrameIO.writeJsonFrame(out, m);
    }

    public void sendAsync(Message m) {
        writerPool.submit(() -> {
            synchronized (this) {
                try {
                    FrameIO.writeJsonFrame(out, m);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }
}
