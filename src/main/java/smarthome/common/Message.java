package smarthome.common;

import java.util.Map;

public class Message {
    public String id;
    public MessageType type;
    public String role;
    public String deviceId;
    public Map<String, Object> payload;
    public long timestamp;

    public static Message of(MessageType t) {
        Message m = new Message();
        m.type = t;
        m.timestamp = System.currentTimeMillis();
        return m;
    }
}
