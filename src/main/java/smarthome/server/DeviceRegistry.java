package smarthome.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceRegistry {
    private final Map<String, ClientSession> devices = new ConcurrentHashMap<>();

    public void register(String deviceId, ClientSession session) {
        devices.put(deviceId, session);
    }

    public void unregister(String deviceId) {
        if (deviceId != null) devices.remove(deviceId);
    }

    public ClientSession get(String deviceId) {
        return devices.get(deviceId);
    }

    public String[] listDeviceIds() {
        return devices.keySet().stream().sorted().toArray(String[]::new);
    }
}
