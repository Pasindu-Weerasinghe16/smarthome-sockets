# Viva Preparation — Smart Home Automation (Java Sockets)

---

## Project Overview (30-second pitch)

> "This is a **Smart Home Automation system** built entirely in **Java** using **raw TCP sockets**.
> A central server manages multiple IoT devices (lights, fans, etc.) and an owner client.
> The owner can send ON/OFF commands, schedule future commands via CSV, and query how long
> any device was ON on any given day — all persisted in an embedded SQLite database.
> There is also a full JavaFX GUI as an alternative to the CLI owner client."

---

## Requirement Checklist

| Requirement | How this project satisfies it |
|---|---|
| Unique title | "Smart Home Automation — Java Sockets" |
| Uses threads | `ExecutorService` thread pools everywhere — see §3 below |
| At least 2 hosts | Server ↔ DeviceClient on different processes/machines via TCP |
| Any language / library | Java 17 + Gson + SQLite-JDBC + JavaFX |

---

## 1. Architecture

```
[DeviceClient]  ──TCP──►  [HomeServer]  ◄──TCP──  [OwnerClient / OwnerFxApp]
   (device)                  (hub)                       (owner)
                               │
                          [SQLite DB]
                        smarthome.db
```

- **HomeServer** — accepts all connections on a single `ServerSocket` port (default 5000).
- **ClientSession** — one `Runnable` per connected client, submitted to a thread pool.
- **DeviceRegistry** — `ConcurrentHashMap<String, ClientSession>` mapping device IDs to their sessions.
- **OwnerHub** — `ConcurrentHashSet<ClientSession>` of owner sessions that subscribed to live STATUS pushes.
- **CommandScheduler** — uses a `ScheduledExecutorService` to fire future commands from the CSV schedule.
- **Persistence** — embedded SQLite (`smarthome.db`) via `sqlite-jdbc`.

---

## 2. Communication Protocol

### Wire format — `FrameIO`
Every message is a **length-prefixed JSON frame** over TCP:

```
| 4 bytes (int, big-endian) | N bytes (UTF-8 JSON) |
       ↑ length                    ↑ payload
```

Why? Raw TCP is a stream — you need framing so you know where one message ends and the next begins.

### Message type flow

```
Device REGISTER  →  Server ACK
Owner  REGISTER  →  Server ACK

Owner  COMMAND   →  Server  (forward)  →  Device COMMAND
Device STATUS    →  Server  (persist + broadcast)  →  Owner STATUS (push)

Owner  UPLOAD_SCHEDULE  →  Server SCHEDULE_ACCEPTED
Owner  GET_DEVICE_STATS →  Server DEVICE_STATS
Owner  GET_DEVICE_USAGE →  Server DEVICE_USAGE
```

### MessageType enum (full list)
`REGISTER / ACK / ERROR / LIST_DEVICES / DEVICES / COMMAND / STATUS / DEVICE_LOG / UPLOAD_SCHEDULE / SCHEDULE_ACCEPTED / GET_DEVICE_STATS / DEVICE_STATS / GET_DEVICE_USAGE / DEVICE_USAGE / DELETE_DEVICE / CLEAR_DEVICE_HISTORY`

---

## 3. Threads — Where & Why

This is the most likely viva question. Know every thread pool.

| Thread / Pool | Class | Purpose |
|---|---|---|
| `clientPool` — `newCachedThreadPool()` | `HomeServer` | One thread per connected client (device or owner). Grows as needed. |
| `writerPool` — `newFixedThreadPool(8)` | `HomeServer` | Async writes back to clients via `sendAsync()`. Prevents slow clients blocking the reader thread. |
| `schedulerPool` — `newScheduledThreadPool(4)` | `HomeServer` | Fires scheduled commands (ON/OFF) at the right time using `schedule(delay, MILLISECONDS)`. |
| `ioExecutor` — `newSingleThreadExecutor` | `OwnerFxApp` | All blocking socket I/O on a dedicated thread; never blocks the JavaFX UI thread. |
| `logPoller` — single scheduled thread | `OwnerFxApp` | Polls device log files every 500 ms to update the Device Console tab. |
| `readerThread` — manual `Thread` | `OwnerFxApp` | Continuously reads incoming frames from the server (STATUS pushes, log pushes) without blocking the UI. |
| `SimulatedDevice.apply()` — `synchronized` | `SimulatedDevice` | Guards device state against concurrent command access. |

### Key concurrency patterns used
- **`ConcurrentHashMap`** — `DeviceRegistry` for device→session lookup; safe for concurrent register/unregister.
- **`ConcurrentHashMap.newKeySet()`** — `OwnerHub.subscribers` for concurrent subscribe/unsubscribe/broadcast.
- **`synchronized` on `ClientSession`** — `send()` and `sendAsync()` both lock on `this` so frames are not interleaved.
- **`BlockingQueue<Message>`** — `OwnerFxApp` uses `LinkedBlockingQueue` to hand responses from the reader thread to the I/O executor thread (request/response pairing).
- **`Platform.runLater()`** — All JavaFX UI updates are marshalled back to the FX Application Thread.

---

## 4. Key Classes — Quick Reference

### `HomeServer`
- Entry point: `main(String[] args)` — reads port from args, creates `Persistence`, calls `start()`.
- `start()` — loop: `ServerSocket.accept()` → `clientPool.submit(new ClientSession(...))`.
- Replays persisted schedule entries on startup.

### `ClientSession` (implements `Runnable`)
- **One instance per TCP connection** — handles both device and owner connections.
- First message **must** be `REGISTER` (sets `role` and `deviceId`).
- Device flow: `registry.register()` → `persistence.upsertDeviceConnected(true)` → loop reading commands.
- Owner flow: optionally subscribes to `OwnerHub` for live STATUS/log pushes → loop handling requests.
- `finally` block: `registry.unregister()` + `persistence.upsertDeviceConnected(false)` + `ownerHub.unsubscribe()`.

### `DeviceClient`
- Registers as `role=device`.
- Main loop: reads `COMMAND` messages → calls `SimulatedDevice.apply(action)` → sends back `STATUS`.
- Also sends `DEVICE_LOG` messages so the owner GUI can show a live console.

### `OwnerClient` (CLI)
- Registers as `role=owner`.
- Simple `Scanner` menu — synchronous request/response.

### `OwnerFxApp` (GUI)
- Same protocol as CLI owner, but async: all socket I/O on `ioExecutor`, all UI on FX thread.
- `readerThread` runs in background; routes STATUS push → `handleStatusPush()`, log push → `handleDeviceLogPush()`, everything else → `responseQueue`.

### `Persistence`
- Embedded SQLite — no external database server needed.
- Two core tables:
  - `devices` — current state: `device_id, connected, state, on_since_ms, last_state_change_ms, last_seen_ms`
  - `device_state_events` — full state-change history: `device_id, ts_ms, state`
- `computeOnTime(fromMs, toMs)` — walks this history to calculate ON duration for any window.

### `CommandScheduler`
- `schedule(List<Entry>)` — for each entry, computes `delay = epochMillis - now` and calls `ses.schedule(lambda, delay, MILLISECONDS)`.
- The lambda calls `registry.get(deviceId)` and `session.sendAsync(command)`.

### `ScheduleParser`
- Parses CSV with timestamps in many formats: `+60` / `5m` / `2h` (relative), `2026-02-22 18:30` (local datetime), ISO-UTC (`Z` suffix), epoch seconds/millis.

---

## 5. SQLite Schema

```sql
CREATE TABLE devices (
    device_id             TEXT PRIMARY KEY,
    connected             INTEGER NOT NULL DEFAULT 0,
    last_seen_ms          INTEGER NOT NULL DEFAULT 0,
    state                 TEXT,
    on_since_ms           INTEGER,          -- epoch-ms when current ON session started
    last_state_change_ms  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE device_state_events (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT    NOT NULL,
    ts_ms     INTEGER NOT NULL,
    state     TEXT    NOT NULL
);
CREATE INDEX idx_events_device_ts ON device_state_events(device_id, ts_ms);
```

**ON-time algorithm** (`computeOnTime`):
1. Find the device's state just *before* the window (look back for the most recent event before `fromMs`). Default = OFF.
2. Walk all events *inside* the window in order.
3. Accumulate `ts_end - ts_on` for each closed ON segment.
4. If still ON at `toMs`, count up to `toMs`.

**"Stop at disconnect" rule**: when a device disconnects while ON, a synthetic `OFF` event is inserted at the disconnect timestamp — so ON-time is never counted past disconnection.

---

## 6. How to Demo (4 minutes)

```
Terminal 1:  java -cp target/smarthome-sockets-1.0.0-jar-with-dependencies.jar smarthome.server.HomeServer 5000

Terminal 2:  java -cp target/smarthome-sockets-1.0.0-jar-with-dependencies.jar smarthome.device.DeviceClient LIGHT1

Terminal 3:  java -cp target/smarthome-sockets-1.0.0-jar-with-dependencies.jar smarthome.device.DeviceClient FAN1

GUI:         mvn -q javafx:run
```

**Demo script:**
1. Show server accepting connections from two devices (LIGHT1, FAN1).
2. From GUI → Command tab → send ON to LIGHT1. Show STATUS reply.
3. From GUI → Schedule tab → upload `schedule.csv`. Show scheduled commands fire automatically.
4. Wait ~1 minute. Open Usage tab → click Refresh. Show all-time ON and today ON columns.
5. Schedule tab → date picker → Get Usage for Selected Date. Show `LIGHT1 was ON for Xs on 2026-02-23`.
6. Disconnect LIGHT1. Reconnect. Show history survives restart (SQLite persisted).

---

## 7. Expected Viva Questions & Answers

### Q: What is a socket?
> A **socket** is an endpoint for two-way communication between processes over a network. It is identified by an IP address + port number. Java's `java.net.Socket` wraps a TCP connection; `ServerSocket` listens for incoming connections.

### Q: Why TCP and not UDP?
> TCP guarantees **ordered, reliable delivery** with no message loss. Our protocol depends on message ordering (REGISTER must be first; command then status). UDP would require implementing reliability ourselves.

### Q: How does your server handle multiple clients at the same time?
> `HomeServer.start()` loops on `serverSocket.accept()`. Each accepted `Socket` is wrapped in a `ClientSession` and submitted to a **`CachedThreadPool`**. Each `ClientSession.run()` blocks on `FrameIO.readJsonFrame()` — so each client has its own thread, independent of all others.

### Q: What is a thread pool and why use one instead of `new Thread()`?
> A thread pool **reuses threads** rather than creating and destroying one per task. `newCachedThreadPool()` creates threads on demand but reuses idle ones — better for bursty workloads like client connections. Raw `new Thread()` per connection would waste OS resources and crash under load.

### Q: How do you prevent race conditions on the device registry?
> `DeviceRegistry` uses a **`ConcurrentHashMap`**, which uses lock striping internally — reads are non-blocking and writes only lock the relevant bucket. So `register()`, `unregister()`, and `get()` can be called safely from multiple client threads simultaneously.

### Q: How does `sendAsync` work? Why not just call `send()` directly?
> `sendAsync()` submits a write task to the `writerPool`. This means:
> - The **reader thread** (currently in `ClientSession.run()`) is never blocked waiting for a slow write.
> - Multiple threads can enqueue messages to the same client — `synchronized(this)` inside `sendAsync` means frames are never interleaved on the wire.

### Q: How does the owner get live STATUS updates without polling?
> On connect, the owner sends `subscribeStatus: true` in its `REGISTER` payload. The server calls `ownerHub.subscribe(this)`. Whenever any `ClientSession` receives a `STATUS` from a device, it calls `ownerHub.broadcast(message)`, which iterates over all subscribed owner sessions and calls `sendAsync()` on each — pushing the update without the owner having to ask.

### Q: How does scheduled command delivery work?
> `ScheduleParser.parse()` converts each CSV line into an `Entry(epochMillis, deviceId, action)`. `CommandScheduler.schedule()` then calls `scheduledExecutorService.schedule(lambda, delay, MILLISECONDS)` for each entry. When the delay expires, the lambda calls `registry.get(deviceId)` to find the live session and `sendAsync(command)` to deliver it.

### Q: What happens if a scheduled device is not yet connected when the command fires?
> `CommandScheduler.sendCommand()` calls `registry.get(deviceId)`. If the session is `null`, it logs a warning and drops the command. There is no retry mechanism.

### Q: How is ON-time calculated accurately across server restarts?
> Every state change is written to `device_state_events` in SQLite. ON-time is computed by replaying this history for any time window — it doesn't rely on in-memory state. After a restart, `Persistence.init()` re-reads the DB, and the history is intact.

### Q: What if a device disconnects while ON? Is that ON-time lost?
> No. In `upsertDeviceConnected(deviceId, false, disconnectMs)`, the code checks if the device's last state was `"ON"`. If so, it inserts a **synthetic `OFF` event** at `disconnectMs` before marking the device disconnected. The ON-time up to the disconnect moment is preserved; time after the disconnect is not counted.

### Q: What is the wire protocol format?
> **4-byte length-prefixed UTF-8 JSON frames** (see `FrameIO`). The sender writes a big-endian `int` (the byte length of the JSON), then the JSON bytes. The receiver reads 4 bytes to get the length, then reads exactly that many bytes. This delimits messages in a TCP byte stream.

### Q: Why embed SQLite instead of a separate database server?
> SQLite runs **in-process** — no external server, no configuration, no networking. It writes a single file (`smarthome.db`). For a small IoT hub, this is simpler and sufficient. The `sqlite-jdbc` library provides a JDBC driver.

### Q: What does `synchronized` do on `SimulatedDevice.apply()`?
> It ensures only **one thread at a time** can change or read the device state. Without it, two concurrent commands (e.g., ON and OFF) could race and leave the state inconsistent.

### Q: How does the JavaFX GUI avoid blocking the UI thread?
> All socket I/O is done on `ioExecutor` (a separate single-threaded executor). Responses come back via `CompletableFuture.supplyAsync(..., ioExecutor).whenComplete(...)`. Inside `whenComplete`, `Platform.runLater()` marshals any UI update back to the JavaFX Application Thread, which is the only thread allowed to touch UI components.

### Q: What libraries did you use and why?
| Library | Why |
|---|---|
| **Gson** | Simple, zero-config JSON serialise/deserialise for `Message` objects |
| **sqlite-jdbc** | In-process SQL database — no external server needed |
| **JavaFX 17** | Rich UI framework bundled with `javafx-maven-plugin` for easy launch |
| **java.util.logging** | Built-in JDK logger; configured via `logging.properties` to write rotating log files |

### Q: How do two separate machines communicate?
> Change `"127.0.0.1"` to the server's IP when starting `DeviceClient` or `OwnerClient`. The server binds on `0.0.0.0` (all interfaces) by default via `new ServerSocket(port)`. The only requirement is network connectivity and the port being open.

---

## 8. Code Locations — Quick Navigation

| Topic | File | Key lines |
|---|---|---|
| Thread pools | `HomeServer.java` | `clientPool`, `writerPool`, `schedulerPool` fields |
| Accepting connections | `HomeServer.java` | `start()` method |
| Session lifecycle | `ClientSession.java` | `run()` — register → loop → finally |
| Async write | `ClientSession.java` | `sendAsync()` |
| Wire framing | `FrameIO.java` | `writeJsonFrame()` / `readJsonFrame()` |
| Device state machine | `SimulatedDevice.java` | `apply()` |
| Schedule parsing | `ScheduleParser.java` | `parseTimestamp()` |
| Schedule firing | `CommandScheduler.java` | `schedule()` → `ses.schedule(lambda, delay, ms)` |
| ON-time algorithm | `Persistence.java` | `computeOnTime()` |
| Disconnect-while-ON | `Persistence.java` | `upsertDeviceConnected(false, ...)` |
| Live push to owner | `OwnerHub.java` | `broadcast()` |
| GUI async pattern | `OwnerFxApp.java` | `request()`, `startReader()`, `Platform.runLater()` |

---

## 9. Possible Trick Questions

**"Isn't a CachedThreadPool dangerous — can it create unlimited threads?"**
> Yes, theoretically. In production you'd cap it. For a demo/lab with ~5 devices it is fine, and it simplifies the code.

**"What if two owners send commands at exactly the same time?"**
> Each owner is on its own `ClientSession` thread. Commands are forwarded to the device via `sendAsync()`, which queues writes to the `writerPool`. The `synchronized(this)` lock on the device's session ensures frames are written one at a time — no corruption, though the order of delivery depends on which task the pool picks first.

**"Is your JSON parsing thread-safe?"**
> `Json` holds a single `Gson` instance. `Gson` is **thread-safe for read operations** (parsing / serialising). All calls to `Json.fromJson` / `Json.toJson` are safe from multiple threads.

**"What happens if the server crashes mid-schedule?"**
> On restart, `HomeServer.start()` calls `persistence.loadPendingScheduleEntries(now)` and re-submits all future-dated entries to the scheduler. Past entries are ignored (they are also cleaned up after 24 hours).
