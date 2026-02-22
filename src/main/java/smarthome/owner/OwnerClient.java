package smarthome.owner;

import smarthome.common.*;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
