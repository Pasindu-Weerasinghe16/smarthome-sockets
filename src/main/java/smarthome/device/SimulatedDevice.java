package smarthome.device;

public class SimulatedDevice {
    private String state = "OFF";

    public synchronized String apply(String action) {
        switch (action.toUpperCase()) {
            case "ON"     -> state = "ON";
            case "OFF"    -> state = "OFF";
            case "STATUS" -> { /* no change */ }
            default       -> { /* ignore */ }
        }
        return state;
    }

    public synchronized String state() { return state; }
}
