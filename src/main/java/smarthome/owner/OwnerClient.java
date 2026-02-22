package smarthome.owner;

import smarthome.common.*;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.*;

public class OwnerClient {
    private static Path parseUserPath(String raw) {
        String s = (raw == null) ? "" : raw.trim();
        if (s.isEmpty()) {
            return Path.of("schedule.csv");
        }

        // If user pasted a Windows UNC path that points into WSL, convert it to a Linux path.
        String wslPrefix = "\\\\wsl.localhost\\";
        if (s.startsWith(wslPrefix)) {
            String remainder = s.substring(wslPrefix.length());
            int distroSep = remainder.indexOf('\\');
            if (distroSep >= 0) {
                remainder = remainder.substring(distroSep); // keep leading \\path
            }
            remainder = remainder.replace('\\', '/');
            if (remainder.startsWith("/")) {
                return Path.of(remainder);
            }
        }

        // If user pasted a Windows drive path (C:\\...), map to WSL mount (/mnt/c/...).
        if (s.length() >= 3
                && Character.isLetter(s.charAt(0))
                && s.charAt(1) == ':'
                && s.charAt(2) == '\\') {
            char drive = Character.toLowerCase(s.charAt(0));
            String rest = s.substring(2).replace('\\', '/');
            return Path.of("/mnt/" + drive + rest);
        }

        // Helpful fallback if user used backslashes on Linux.
        if (s.indexOf('\\') >= 0 && s.indexOf('/') < 0) {
            s = s.replace('\\', '/');
        }

        return Path.of(s);
    }

    private static Process startLocalDevice(String deviceId, String host, int port) throws IOException {
        String classpath = System.getProperty("java.class.path");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        Path logDir = Path.of("server-logs");
        try {
            Files.createDirectories(logDir);
        } catch (IOException ignored) {
            // best-effort
        }
        Path logFile = logDir.resolve("device-" + deviceId + ".log");
        if (!Files.exists(logFile)) {
            try {
                Files.writeString(logFile, "", StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            } catch (IOException ignored) {
                // best-effort
            }
        }

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classpath,
                "smarthome.device.DeviceClient",
                deviceId,
                host,
                String.valueOf(port)
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return pb.start();
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String fmtMs(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000;
        long m = s / 60; s %= 60;
        long h = m / 60; m %= 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        sb.append(s).append("s");
        return sb.toString().trim();
    }

    public static void main(String[] args) throws Exception {
        String host = (args.length > 0) ? args[0] : "127.0.0.1";
        int port = (args.length > 1) ? Integer.parseInt(args[1]) : 5000;

        try (Socket socket = new Socket(host, port);
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             Scanner sc = new Scanner(System.in)) {

            List<Process> startedDevices = new ArrayList<>();

            Message reg = Message.of(MessageType.REGISTER);
            reg.role = "owner";
            FrameIO.writeJsonFrame(out, reg);
            System.out.println("Server: " + FrameIO.readJsonFrame(in));

            while (true) {
                System.out.println("\n=== Owner Menu ===");
                System.out.println("1) List devices");
                System.out.println("2) Send command (ON/OFF/STATUS)");
                System.out.println("3) Upload schedule CSV");
                System.out.println("5) Create (start) simulated device");
                System.out.println("6) View usage stats (all devices)");
                System.out.println("7) Query ON-time for device on a specific day");
                System.out.println("4) Exit");
                System.out.print("Select: ");
                String sel = sc.nextLine().trim();

                if ("1".equals(sel)) {
                    FrameIO.writeJsonFrame(out, Message.of(MessageType.LIST_DEVICES));
                    System.out.println("Server: " + FrameIO.readJsonFrame(in));
                } else if ("2".equals(sel)) {
                    System.out.print("Device Id: ");
                    String dev = sc.nextLine().trim();
                    System.out.print("Action [ON|OFF|STATUS]: ");
                    String act = sc.nextLine().trim().toUpperCase();
                    Message cmd = Message.of(MessageType.COMMAND);
                    cmd.payload = Map.of("deviceId", dev, "action", act);
                    FrameIO.writeJsonFrame(out, cmd);
                    System.out.println("Server: " + FrameIO.readJsonFrame(in));
                } else if ("3".equals(sel)) {
                    System.out.print("Path to CSV: ");
                    Path path = parseUserPath(sc.nextLine());
                    String content;
                    try {
                        content = Files.readString(path);
                    } catch (IOException e) {
                        System.out.println("Could not read CSV: " + path);
                        System.out.println("Tip: if the file is in this folder, just type: schedule.csv");
                        continue;
                    }
                    Message up = Message.of(MessageType.UPLOAD_SCHEDULE);
                    up.payload = Map.of("content", content);
                    FrameIO.writeJsonFrame(out, up);
                    System.out.println("Server: " + FrameIO.readJsonFrame(in));
                } else if ("5".equals(sel)) {
                    System.out.print("New Device Id: ");
                    String devId = sc.nextLine().trim();
                    if (devId.isEmpty()) {
                        System.out.println("Device Id cannot be empty.");
                        continue;
                    }
                    try {
                        Process p = startLocalDevice(devId, host, port);
                        startedDevices.add(p);
                        System.out.println("Started device '" + devId + "' (pid=" + p.pid() + ")");
                        System.out.println("Logs: server-logs/device-" + devId + ".log");
                    } catch (IOException e) {
                        System.out.println("Failed to start device: " + e.getMessage());
                    }
                } else if ("6".equals(sel)) {
                    FrameIO.writeJsonFrame(out, Message.of(MessageType.GET_DEVICE_STATS));
                    String raw = FrameIO.readJsonFrame(in);
                    Message resp = Json.fromJson(raw, Message.class);
                    Object statsObj = resp.payload == null ? null : resp.payload.get("stats");
                    if (statsObj instanceof List<?> list) {
                        System.out.printf("%-14s %-5s %-6s %10s %10s%n",
                                "Device", "Live", "State", "All-time", "Today");
                        System.out.println("-".repeat(50));
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> row) {
                                Object idO    = row.get("deviceId");
                                Object stateO = row.get("state");
                                String id     = idO    != null ? String.valueOf(idO)    : "?";
                                boolean conn  = Boolean.TRUE.equals(row.get("connected"));
                                String  state = stateO != null ? String.valueOf(stateO) : "?";
                                long    total = toLong(row.get("totalOnMs"));
                                long    today = toLong(row.get("todayOnMs"));
                                System.out.printf("%-14s %-5s %-6s %10s %10s%n",
                                        id, conn ? "ON" : "off", state,
                                        fmtMs(total), fmtMs(today));
                            }
                        }
                    } else {
                        System.out.println("Server: " + raw);
                    }
                } else if ("7".equals(sel)) {
                    System.out.print("Device Id: ");
                    String devId = sc.nextLine().trim();
                    System.out.print("Date [YYYY-MM-DD, blank = today]: ");
                    String dateInput = sc.nextLine().trim();
                    String dateStr;
                    try {
                        dateStr = dateInput.isBlank()
                                ? LocalDate.now().toString()
                                : LocalDate.parse(dateInput).toString();
                    } catch (Exception e) {
                        System.out.println("Invalid date: " + dateInput);
                        continue;
                    }
                    Message req = Message.of(MessageType.GET_DEVICE_USAGE);
                    req.payload = Map.of("deviceId", devId, "date", dateStr);
                    FrameIO.writeJsonFrame(out, req);
                    String raw = FrameIO.readJsonFrame(in);
                    Message resp = Json.fromJson(raw, Message.class);
                    if (resp.type == MessageType.DEVICE_USAGE && resp.payload != null) {
                        long onMs = toLong(resp.payload.get("onMs"));
                        System.out.println(devId + " was ON for " + fmtMs(onMs) + " on " + dateStr);
                    } else {
                        System.out.println("Server: " + raw);
                    }
                } else if ("4".equals(sel)) {
                    System.out.println("Bye.");
                    for (Process p : startedDevices) {
                        if (p.isAlive()) {
                            p.destroy();
                        }
                    }
                    break;
                } else {
                    System.out.println("Invalid selection.");
                }
            }
        }
    }
}
