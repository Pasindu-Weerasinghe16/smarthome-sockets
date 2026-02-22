# Smart Home Automation — Java Socket Project

## Ubuntu Setup & Run

### 1. Install Java 17 and Maven
```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven
java -version
mvn -version
```

### 2. Build the project
```bash
cd smarthome-sockets
mvn -q package
```
This produces: `target/smarthome-sockets-1.0.0-jar-with-dependencies.jar`

### 3. Create the log directory
```bash
mkdir -p server-logs
```

### 4. Run — open three terminals

**Terminal 1 — Server:**
```bash
java -cp target/smarthome-sockets-1.0.0-jar-with-dependencies.jar \
     smarthome.server.HomeServer 5000
```

**Terminal 2 — Device (LIGHT1):**
```bash
java -cp target/smarthome-sockets-1.0.0-jar-with-dependencies.jar \
     smarthome.device.DeviceClient LIGHT1
```

**Terminal 3 — Owner UI:**
```bash
java -cp target/smarthome-sockets-1.0.0-jar-with-dependencies.jar \
     smarthome.owner.OwnerClient
```

### Optional: JavaFX Owner UI (GUI)
Run the server and at least one device first, then launch the GUI:
```bash
mvn -q javafx:run
```
This starts: `smarthome.owner.OwnerFxApp`

### 5. Owner menu options
```
1) List devices
2) Send command (ON / OFF / STATUS)
3) Upload schedule CSV  →  type: schedule.csv
4) Exit
5) Create (start) simulated device
6) View usage stats (all devices — all-time ON + today ON)
7) Query ON-time for a device on a specific day (YYYY-MM-DD)
```

---

## Device ON-Time Tracking (main feature)

The server records every state change (ON / OFF) in a local SQLite database (`smarthome.db`).
This allows you to ask: **"how long was LIGHT1 ON on Feb 23?"** — even after a server restart.

### How it works
- Every `STATUS` message a device sends is stored in `device_state_events`.
- When a device **disconnects while still ON**, a synthetic OFF event is inserted at the disconnect time (session counted up to disconnect, not beyond — "stop at disconnect" rule).
- "Today" is defined by the **server's local timezone** (`ZoneId.systemDefault()`).

### JavaFX GUI — Usage tab
Open the **Usage** tab to see a table of all known devices:

| Column      | Meaning                                               |
|-------------|-------------------------------------------------------|
| Device      | Device ID                                             |
| Live        | Whether currently connected                           |
| State       | Last known state (ON / OFF / …)                       |
| All-time    | Total ON duration since first recorded state change   |
| Today       | ON duration since local midnight (server timezone)    |
| Current ON  | Duration of the currently open ON session             |
| Last Seen   | Timestamp of the last STATUS received                 |

Click **Refresh Usage Stats** (or **Refresh** on the left panel) to reload the table.

### JavaFX GUI — Schedule tab (per-day query)
1. Select a device from the left panel.
2. Choose a date using the calendar date-picker in the Schedule tab.
3. Click **Get Usage for Selected Date**.

The label below the button will show e.g. `LIGHT1 was ON for 1h 4m 30s on 2026-02-23 (server timezone)`.

### CLI — options 6 and 7
```
6) View usage stats (all devices)
   → prints a table: Device | Live | State | All-time ON | Today ON

7) Query ON-time for device on a specific day
   Device Id: LIGHT1
   Date [YYYY-MM-DD, blank = today]: 2026-02-23
   → LIGHT1 was ON for 1h 4m 30s on 2026-02-23
```

### Schedule CSV format (`schedule.csv`)
```
# Timestamp, DEVICE_ID, ACTION
# Recommended timestamp formats:
# - Local datetime (server time): 2026-02-22 18:30   (or with seconds: 2026-02-22 18:30:00)
# - Relative from now: +60   or   30s / 5m / 2h
# (Other formats like ISO-UTC and epoch are also accepted.)

+30,LIGHT1,ON
5m,LIGHT1,OFF
2026-02-22 18:30,FAN1,ON
2026-06-01T08:00:00Z,FAN1,OFF
1740258600,LIGHT1,STATUS
```

## Project structure
```
smarthome-sockets/
├── pom.xml
├── schedule.csv
└── src/main/
    ├── java/smarthome/
    │   ├── common/     (FrameIO, Json, Message, MessageType)
    │   ├── server/     (HomeServer, ClientSession, DeviceRegistry,
    │   │                CommandScheduler, ScheduleParser)
    │   ├── owner/      (OwnerClient)
    │   └── device/     (DeviceClient, SimulatedDevice)
    └── resources/
        └── logging.properties
```
cd ~/OS_Project/smarthome-sockets

# Rebuild once after this change:
mvn -q -DskipTests package

# Terminal 1 – Server
java -cp target/smarthome-sockets-1.0.0-jar-with-dependencies.jar smarthome.server.HomeServer 5000

# Terminal 2 – Device
java -cp target/smarthome-sockets-1.0.0-jar-with-dependencies.jar smarthome.device.DeviceClient LIGHT_01

# Terminal 3 – CLI owner
java -cp target/smarthome-sockets-1.0.0-jar-with-dependencies.jar smarthome.owner.OwnerClient

# GUI owner (JavaFX – uses Maven classpath, not the fat jar)
mvn -q javafx:run