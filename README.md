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
