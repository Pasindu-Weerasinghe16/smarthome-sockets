package smarthome.server;

import smarthome.common.Message;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks owner sessions that opted-in to receive live STATUS updates.
 */
public final class OwnerHub {
    private final Set<ClientSession> subscribers = ConcurrentHashMap.newKeySet();

    public void subscribe(ClientSession ownerSession) {
        if (ownerSession != null) subscribers.add(ownerSession);
    }

    public void unsubscribe(ClientSession ownerSession) {
        if (ownerSession != null) subscribers.remove(ownerSession);
    }

    public void broadcast(Message message) {
        if (message == null) return;
        for (ClientSession s : subscribers) {
            s.sendAsync(message);
        }
    }

    public void broadcastStatus(Message statusMessage) {
        broadcast(statusMessage);
    }
}
