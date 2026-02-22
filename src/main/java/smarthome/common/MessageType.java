package smarthome.common;

public enum MessageType {
    REGISTER,
    ACK,
    ERROR,
    LIST_DEVICES,
    DEVICES,
    COMMAND,
    STATUS,
    DEVICE_LOG,
    UPLOAD_SCHEDULE,
    SCHEDULE_ACCEPTED,
    GET_DEVICE_STATS,       // owner → server: request usage stats for all known devices
    DEVICE_STATS,           // server → owner: usage stats response
    GET_DEVICE_USAGE,       // owner → server: {deviceId, date "YYYY-MM-DD"}
    DEVICE_USAGE,           // server → owner: {deviceId, date, onMs}
    DELETE_DEVICE,          // owner → server: {deviceId}  – remove from DB entirely
    CLEAR_DEVICE_HISTORY    // owner → server: {deviceId}  – reset ON-time history
}
