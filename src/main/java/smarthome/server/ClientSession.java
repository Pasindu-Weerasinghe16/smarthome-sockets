package smarthome.server;

import smarthome.common.*;

import java.io.*;
import java.net.Socket;
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
    private String role;
    private String deviceId;

    public ClientSession(Socket socket, DeviceRegistry registry, CommandScheduler scheduler, ExecutorService writerPool) throws IOException {
        this.socket = socket;
        this.registry = registry;
        this.scheduler = scheduler;
        this.writerPool = writerPool;
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
                log.info(() -> "Device connected: " + deviceId + " from " + socket.getRemoteSocketAddress());
                send(MessageType.ACK, Map.of("msg", "REGISTERED"));
            } else {
                this.role = "owner";
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
                    }
                    case UPLOAD_SCHEDULE -> {
                        String content = str(m.payload, "content");
                        var entries = ScheduleParser.parse(content, HomeServer.SERVER_ZONE);
                        scheduler.schedule(entries);
                        send(MessageType.SCHEDULE_ACCEPTED, Map.of("count", entries.size()));
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
        }
    }

    private String who() {
        return role == null ? "unknown" : ("device".equals(role) ? "device[" + deviceId + "]" : "owner");
    }

    private static String str(Map<String, Object> p, String k) {
        Object v = p == null ? null : p.get(k);
        return v == null ? null : v.toString();
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
