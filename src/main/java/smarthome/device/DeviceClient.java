package smarthome.device;

import smarthome.common.*;

import java.io.*;
import java.net.Socket;
import java.util.Map;

public class DeviceClient {
    private static void sendDeviceLog(DataOutputStream out, String deviceId, String line) throws IOException {
        Message log = Message.of(MessageType.DEVICE_LOG);
        log.deviceId = deviceId;
        log.payload = Map.of("deviceId", deviceId, "line", line);
        FrameIO.writeJsonFrame(out, log);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: DeviceClient <DEVICE_ID> [host] [port]");
            return;
        }
        String deviceId = args[0];
        String host = (args.length > 1) ? args[1] : "127.0.0.1";
        int port = (args.length > 2) ? Integer.parseInt(args[2]) : 5000;

        var device = new SimulatedDevice();

        try (Socket socket = new Socket(host, port);
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            Message reg = Message.of(MessageType.REGISTER);
            reg.role = "device";
            reg.deviceId = deviceId;
            FrameIO.writeJsonFrame(out, reg);

            String ack = FrameIO.readJsonFrame(in);
            System.out.println("Server: " + ack);

            try {
                sendDeviceLog(out, deviceId, "Connected and registered");
            } catch (IOException ignored) {
            }

            while (true) {
                Message m = Json.fromJson(FrameIO.readJsonFrame(in), Message.class);
                if (m.type == MessageType.COMMAND) {
                    String action = String.valueOf(m.payload.get("action"));
                    String result = device.apply(action);
                    Message status = Message.of(MessageType.STATUS);
                    status.deviceId = deviceId;
                    status.payload = Map.of("deviceId", deviceId, "state", result, "msg", "OK");
                    FrameIO.writeJsonFrame(out, status);
                    String line = "Executed " + action + ", state=" + result;
                    System.out.println(line);
                    try {
                        sendDeviceLog(out, deviceId, line);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }
}
