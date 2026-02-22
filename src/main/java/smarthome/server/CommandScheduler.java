package smarthome.server;

import smarthome.common.Message;
import smarthome.common.MessageType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Schedules device ON/OFF/STATUS commands for future execution. */
public class CommandScheduler {
    private static final Logger log = Logger.getLogger(CommandScheduler.class.getName());
    private final ScheduledExecutorService ses;
    private final DeviceRegistry registry;

    public CommandScheduler(ScheduledExecutorService ses, DeviceRegistry registry) {
        this.ses = ses;
        this.registry = registry;
    }

    public void schedule(List<ScheduleParser.Entry> entries) {
        long now = System.currentTimeMillis();
        for (var e : entries) {
            long delay = Math.max(0, e.epochMillis() - now);
            ses.schedule(() -> sendCommand(e.deviceId(), e.action()), delay, TimeUnit.MILLISECONDS);
            log.info(() -> "Scheduled " + e.action() + " for " + e.deviceId() + " in " + delay + " ms");
        }
    }

    private void sendCommand(String deviceId, String action) {
        var session = registry.get(deviceId);
        if (session == null) {
            log.warning(() -> "No active session for device " + deviceId);
            return;
        }
        Message m = Message.of(MessageType.COMMAND);
        m.deviceId = deviceId;
        m.payload = Map.of("deviceId", deviceId, "action", action);
        session.sendAsync(m);
        log.info(() -> "Sent command " + action + " to " + deviceId);
    }
}
