package smarthome.owner;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import smarthome.common.FrameIO;
import smarthome.common.Json;
import smarthome.common.Message;
import smarthome.common.MessageType;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;

public class OwnerFxApp extends Application {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ownerfx-io");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService logPoller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ownerfx-device-log");
        t.setDaemon(true);
        return t;
    });

    private final Object ioLock = new Object();
    private final Object outLock = new Object();
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    private final BlockingQueue<Message> responseQueue = new LinkedBlockingQueue<>();
    private volatile boolean readerRunning;
    private Thread readerThread;

    private final Map<String, String> deviceState = new ConcurrentHashMap<>();

    private final List<Process> startedDevices = new ArrayList<>();
    private final Set<String> startedDeviceIds = ConcurrentHashMap.newKeySet();

    private final Map<String, Deque<String>> deviceConsoleLines = new ConcurrentHashMap<>();
    private final Map<String, Long> deviceConsoleOffsets = new ConcurrentHashMap<>();

    private TextField hostField;
    private TextField portField;
    private Button connectBtn;
    private Button disconnectBtn;

    private ListView<String> deviceList;
    private Button refreshBtn;

    private ComboBox<String> actionBox;
    private Button sendBtn;

    private TextField csvPathField;
    private Button browseCsvBtn;
    private Button uploadCsvBtn;

    private DatePicker datePicker;
    private TextField timeField;
    private TextField relativeField;
    private ComboBox<String> scheduleDeviceBox;
    private ComboBox<String> scheduleActionBox;
    private Button addScheduleLineBtn;
    private Button addRelativeLineBtn;
    private Button clearScheduleBtn;
    private TextArea schedulePreview;

    private TextField newDeviceField;
    private Button startDeviceBtn;

    private ComboBox<String> consoleDeviceBox;
    private TextArea deviceConsoleArea;

    private TextArea logArea;

    // Usage tab
    private TextArea usageArea;
    private Button  refreshStatsBtn;

    // Schedule tab – usage summary for selected device+date
    private Label  usageSummaryLabel;
    private Button getUsageBtn;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Smart Home - Owner UI");

        hostField = new TextField("127.0.0.1");
        hostField.setPrefColumnCount(14);
        portField = new TextField("5000");
        portField.setPrefColumnCount(6);

        connectBtn = new Button("Connect");
        disconnectBtn = new Button("Disconnect");
        disconnectBtn.setDisable(true);

        HBox topBar = new HBox(10,
                new Label("Host"), hostField,
                new Label("Port"), portField,
                connectBtn, disconnectBtn
        );
        topBar.setPadding(new Insets(12));

        deviceList = new ListView<>();
        deviceList.setPrefWidth(240);
        deviceList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String state = deviceState.get(item);
                setText(state == null ? item : (item + "  [" + state + "]"));
            }
        });

        // Right-click context menu on the device list
        ContextMenu deviceMenu = new ContextMenu();
        MenuItem menuClearHistory = new MenuItem("Clear usage history");
        MenuItem menuDelete       = new MenuItem("Delete from records");
        deviceMenu.getItems().addAll(menuClearHistory, new SeparatorMenuItem(), menuDelete);
        deviceList.setContextMenu(deviceMenu);
        menuClearHistory.setOnAction(e -> {
            String sel = deviceList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            deviceManageRequest(MessageType.CLEAR_DEVICE_HISTORY, sel,
                    "Clear usage history of '" + sel + "'?");
        });
        menuDelete.setOnAction(e -> {
            String sel = deviceList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            deviceManageRequest(MessageType.DELETE_DEVICE, sel,
                    "Delete '" + sel + "' from all records? This cannot be undone.");
        });
        refreshBtn = new Button("Refresh");
        refreshBtn.setMaxWidth(Double.MAX_VALUE);

        VBox left = new VBox(10,
                new Label("Devices"),
                deviceList,
                refreshBtn
        );
        left.setPadding(new Insets(12));
        VBox.setVgrow(deviceList, Priority.ALWAYS);

        actionBox = new ComboBox<>();
        actionBox.getItems().addAll("ON", "OFF", "STATUS");
        actionBox.getSelectionModel().select("STATUS");
        actionBox.setMaxWidth(Double.MAX_VALUE);

        sendBtn = new Button("Send Command");
        sendBtn.setMaxWidth(Double.MAX_VALUE);

        VBox commandTab = new VBox(10,
                new Label("Selected device"),
                new HBox(10, new Label("Device"), new Label("(choose from list on left)")),
                new Label("Action"),
                actionBox,
                sendBtn
        );
        commandTab.setPadding(new Insets(12));

        csvPathField = new TextField("schedule.csv");
        browseCsvBtn = new Button("Browse...");
        uploadCsvBtn = new Button("Upload Schedule");
        uploadCsvBtn.setMaxWidth(Double.MAX_VALUE);

        HBox csvRow = new HBox(10, csvPathField, browseCsvBtn);
        HBox.setHgrow(csvPathField, Priority.ALWAYS);

        // Calendar-based builder (writes lines into a CSV preview)
        datePicker = new DatePicker(LocalDate.now());
        timeField = new TextField("18:30");
        timeField.setPromptText("HH:mm or HH:mm:ss");

        relativeField = new TextField("5m");
        relativeField.setPromptText("+60 / 30s / 5m / 2h");

        scheduleDeviceBox = new ComboBox<>();
        scheduleDeviceBox.setEditable(true);
        scheduleDeviceBox.setPromptText("Device Id");

        scheduleActionBox = new ComboBox<>();
        scheduleActionBox.getItems().addAll("ON", "OFF", "STATUS");
        scheduleActionBox.getSelectionModel().select("ON");

        addScheduleLineBtn = new Button("Add Entry");
        addRelativeLineBtn = new Button("Add Relative");
        clearScheduleBtn = new Button("Clear");

        HBox builderRow1 = new HBox(10,
            new Label("Date"), datePicker,
            new Label("Time"), timeField
        );
        HBox builderRow2 = new HBox(10,
            new Label("Device"), scheduleDeviceBox,
            new Label("Action"), scheduleActionBox,
            addScheduleLineBtn,
            addRelativeLineBtn,
            clearScheduleBtn
        );
        HBox.setHgrow(scheduleDeviceBox, Priority.ALWAYS);

        HBox builderRow3 = new HBox(10,
            new Label("Relative"), relativeField,
            new Label("Example"), new Label("5m")
        );
        HBox.setHgrow(relativeField, Priority.ALWAYS);

        schedulePreview = new TextArea();
        schedulePreview.setPromptText(
            "Schedule CSV preview will appear here...\n" +
            "Examples:\n" +
            "2026-02-22 18:30,LIGHT1,ON\n" +
            "5m,LIGHT1,OFF\n"
        );
        schedulePreview.setPrefRowCount(10);

        usageSummaryLabel = new Label("Select a device from the left panel and a date here, then click 'Get Usage'.");
        usageSummaryLabel.setWrapText(true);
        getUsageBtn = new Button("Get Usage for Selected Date");
        getUsageBtn.setMaxWidth(Double.MAX_VALUE);

        VBox scheduleTab = new VBox(10,
            new Label("Schedule CSV"),
            csvRow,
            uploadCsvBtn,
            new Separator(),
            new Label("Or build with calendar"),
            builderRow1,
            builderRow2,
            builderRow3,
            schedulePreview,
            new Separator(),
            new Label("Device ON-Time for Selected Date"),
            usageSummaryLabel,
            getUsageBtn
        );
        scheduleTab.setPadding(new Insets(12));
        VBox.setVgrow(schedulePreview, Priority.ALWAYS);

        newDeviceField = new TextField();
        newDeviceField.setPromptText("e.g. LIGHT2");
        startDeviceBtn = new Button("Start Device");
        startDeviceBtn.setMaxWidth(Double.MAX_VALUE);

        VBox createTab = new VBox(10,
                new Label("Create (start) simulated device"),
                new Label("New Device Id"),
                newDeviceField,
                startDeviceBtn
        );
        createTab.setPadding(new Insets(12));

        consoleDeviceBox = new ComboBox<>();
        consoleDeviceBox.setPromptText("Select device...");
        consoleDeviceBox.setMaxWidth(Double.MAX_VALUE);
        deviceConsoleArea = new TextArea();
        deviceConsoleArea.setEditable(false);
        deviceConsoleArea.setWrapText(false);
        VBox consoleTab = new VBox(10,
            new Label("Device Console (live logs via server)"),
            consoleDeviceBox,
            deviceConsoleArea,
            new Label("Tip: devices can push logs to the server; UI-started devices also write server-logs/device-<ID>.log")
        );
        consoleTab.setPadding(new Insets(12));
        VBox.setVgrow(deviceConsoleArea, Priority.ALWAYS);

        usageArea = new TextArea();
        usageArea.setEditable(false);
        usageArea.setWrapText(false);
        usageArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        refreshStatsBtn = new Button("Refresh Usage Stats");
        refreshStatsBtn.setMaxWidth(Double.MAX_VALUE);
        VBox usageTab = new VBox(10,
            new Label("Device Usage Statistics  (all-time ON  |  today ON  |  current session)"),
            refreshStatsBtn,
            usageArea
        );
        usageTab.setPadding(new Insets(12));
        VBox.setVgrow(usageArea, Priority.ALWAYS);

        TabPane tabs = new TabPane();
        tabs.getTabs().add(tab("Command", commandTab));
        tabs.getTabs().add(tab("Schedule", scheduleTab));
        tabs.getTabs().add(tab("Create Device", createTab));
        tabs.getTabs().add(tab("Device Console", consoleTab));
        tabs.getTabs().add(tab("Usage", usageTab));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);

        VBox right = new VBox(10, new Label("Logs"), logArea);
        right.setPadding(new Insets(12));
        right.setPrefWidth(420);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setLeft(left);
        root.setCenter(tabs);
        root.setRight(right);

        Scene scene = new Scene(root, 1400, 850);
        String css = getClass().getResource("/ownerfx.css") == null ? null : getClass().getResource("/ownerfx.css").toExternalForm();
        if (css != null) scene.getStylesheets().add(css);
        stage.setScene(scene);
        stage.setMinWidth(1200);
        stage.setMinHeight(750);

        wireActions(stage);
        startDeviceConsolePolling();
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        log("Tip: Start server first: HomeServer 5000");
    }

    private static Tab tab(String title, Region content) {
        Tab t = new Tab(title);
        t.setContent(content);
        return t;
    }

    private void wireActions(Stage stage) {
        connectBtn.setOnAction(e -> connect());
        disconnectBtn.setOnAction(e -> disconnect());

        refreshBtn.setOnAction(e -> refreshDevices());

        sendBtn.setOnAction(e -> sendCommand());

        browseCsvBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose schedule CSV");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            File file = fc.showOpenDialog(stage);
            if (file != null) {
                csvPathField.setText(file.getPath());
            }
        });
        uploadCsvBtn.setOnAction(e -> uploadSchedule());

        addScheduleLineBtn.setOnAction(e -> addScheduleLine());
        addRelativeLineBtn.setOnAction(e -> addRelativeLine());
        clearScheduleBtn.setOnAction(e -> schedulePreview.clear());

        startDeviceBtn.setOnAction(e -> startDevice());

        consoleDeviceBox.setOnAction(e -> refreshDeviceConsoleView());
        refreshStatsBtn.setOnAction(e -> refreshDevices());
        getUsageBtn.setOnAction(e -> getUsageForDate());

        // Small usability: Enter to connect / send
        hostField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) connect();
        });
        portField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) connect();
        });
        newDeviceField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) startDevice();
        });
    }

    private void connect() {
        if (isConnected()) {
            log("Already connected");
            return;
        }

        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            log("Invalid port");
            return;
        }

        connectBtn.setDisable(true);
        CompletableFuture
                .supplyAsync(() -> doConnect(host, port), ioExecutor)
                .whenComplete((ok, err) -> Platform.runLater(() -> {
                    connectBtn.setDisable(false);
                    if (err != null) {
                        log("Connect failed: " + err.getMessage());
                        return;
                    }
                    if (ok) {
                        disconnectBtn.setDisable(false);
                        log("Connected to " + host + ":" + port);
                        refreshDevices();
                    }
                }));
    }

    private boolean doConnect(String host, int port) {
        synchronized (ioLock) {
            try {
                socket = new Socket(host, port);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                Message reg = Message.of(MessageType.REGISTER);
                reg.role = "owner";
                reg.payload = Map.of("subscribeStatus", true, "subscribeDeviceLogs", true);
                FrameIO.writeJsonFrame(out, reg);
                String respJson = FrameIO.readJsonFrame(in);
                Message resp = Json.fromJson(respJson, Message.class);
                Platform.runLater(() -> log("Server: " + Json.toJson(resp)));
                boolean ok = resp.type == MessageType.ACK;
                if (ok) {
                    startReader();
                }
                return ok;
            } catch (Exception e) {
                safeClose();
                throw new RuntimeException(e);
            }
        }
    }

    private void disconnect() {
        if (!isConnected()) return;
        disconnectBtn.setDisable(true);
        CompletableFuture.runAsync(() -> {
            stopReader();
            synchronized (ioLock) {
                safeClose();
            }
        }, ioExecutor).whenComplete((v, err) -> Platform.runLater(() -> {
            log("Disconnected");
            deviceList.getItems().clear();
            deviceState.clear();
        }));
    }

    private void refreshDevices() {
        if (!isConnected()) {
            log("Not connected");
            return;
        }

        refreshBtn.setDisable(true);
        request(Message.of(MessageType.GET_DEVICE_STATS))
                .whenComplete((resp, err) -> Platform.runLater(() -> {
                    refreshBtn.setDisable(false);
                    if (err != null) {
                        log("Refresh failed: " + err.getMessage());
                        return;
                    }

                    Object statsObj = resp.payload == null ? null : resp.payload.get("stats");
                    List<String> ids = new ArrayList<>();
                    List<Map<String, Object>> statsList = new ArrayList<>();

                    if (statsObj instanceof List<?> rawList) {
                        for (Object item : rawList) {
                            if (item instanceof Map<?, ?> rawMap) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                rawMap.forEach((k, v) -> row.put(String.valueOf(k), v));
                                statsList.add(row);
                                Object idObj = rawMap.get("deviceId");
                                if (idObj != null) {
                                    String id = String.valueOf(idObj);
                                    ids.add(id);
                                    Object stObj = rawMap.get("state");
                                    if (stObj != null) deviceState.put(id, String.valueOf(stObj));
                                }
                            }
                        }
                    }

                    deviceList.getItems().setAll(ids);
                    deviceList.refresh();

                    // Keep schedule device dropdown in sync
                    Set<String> all = new LinkedHashSet<>(ids);
                    String current = scheduleDeviceBox.getEditor().getText();
                    scheduleDeviceBox.getItems().setAll(all);
                    if (current != null && !current.isBlank()) {
                        scheduleDeviceBox.getEditor().setText(current);
                    }

                    refreshUsageTab(statsList);
                }));
    }

    private void addScheduleLine() {
        LocalDate d = datePicker.getValue();
        if (d == null) {
            log("Pick a date");
            return;
        }
        String timeRaw = timeField.getText().trim();
        LocalTime t;
        try {
            t = parseTime(timeRaw);
        } catch (DateTimeParseException ex) {
            log("Invalid time. Use HH:mm or HH:mm:ss");
            return;
        }

        String dev = scheduleDeviceBox.isEditable() ? scheduleDeviceBox.getEditor().getText().trim() : scheduleDeviceBox.getValue();
        if (dev == null || dev.isBlank()) {
            log("Enter a device id");
            return;
        }
        String act = scheduleActionBox.getSelectionModel().getSelectedItem();
        if (act == null || act.isBlank()) act = "ON";

        String line = String.format("%s %s,%s,%s",
                d,
                t.toString(),
                dev,
                act
        );
        if (!schedulePreview.getText().isBlank() && !schedulePreview.getText().endsWith("\n")) {
            schedulePreview.appendText("\n");
        }
        schedulePreview.appendText(line + "\n");
    }

    private void addRelativeLine() {
        String rel = relativeField.getText() == null ? "" : relativeField.getText().trim();
        if (rel.isEmpty()) {
            log("Enter a relative time like +60 or 5m");
            return;
        }

        String dev = scheduleDeviceBox.isEditable() ? scheduleDeviceBox.getEditor().getText().trim() : scheduleDeviceBox.getValue();
        if (dev == null || dev.isBlank()) {
            log("Enter a device id");
            return;
        }
        String act = scheduleActionBox.getSelectionModel().getSelectedItem();
        if (act == null || act.isBlank()) act = "ON";

        String line = String.format("%s,%s,%s", rel, dev, act);
        if (!schedulePreview.getText().isBlank() && !schedulePreview.getText().endsWith("\n")) {
            schedulePreview.appendText("\n");
        }
        schedulePreview.appendText(line + "\n");
    }

    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return LocalTime.of(0, 0);
        // Support HH:mm or HH:mm:ss
        if (s.length() == 5) return LocalTime.parse(s);
        return LocalTime.parse(s);
    }

    private void sendCommand() {
        if (!isConnected()) {
            log("Not connected");
            return;
        }

        String dev = deviceList.getSelectionModel().getSelectedItem();
        if (dev == null || dev.isBlank()) {
            log("Select a device from the list first");
            return;
        }

        String act = actionBox.getSelectionModel().getSelectedItem();
        if (act == null || act.isBlank()) act = "STATUS";

        Message cmd = Message.of(MessageType.COMMAND);
        cmd.payload = Map.of("deviceId", dev, "action", act);

        sendBtn.setDisable(true);
        request(cmd).whenComplete((resp, err) -> Platform.runLater(() -> {
            sendBtn.setDisable(false);
            if (err != null) {
                log("Command failed: " + err.getMessage());
                return;
            }
            log("Server: " + Json.toJson(resp));
        }));
    }

    private void uploadSchedule() {
        if (!isConnected()) {
            log("Not connected");
            return;
        }

        // If user has built a schedule in the preview, prefer that content.
        String built = schedulePreview.getText();
        if (built != null && !built.isBlank()) {
            doUploadSchedule(built);
            return;
        }

        Path p = parseUserPath(csvPathField.getText());
        String content;
        try {
            content = Files.readString(p);
        } catch (IOException e) {
            log("Could not read CSV: " + p);
            log("Tip: If the file is in this folder, use: schedule.csv");
            return;
        }

        doUploadSchedule(content);
    }

    private void doUploadSchedule(String csvContent) {
        Message up = Message.of(MessageType.UPLOAD_SCHEDULE);
        up.payload = Map.of("content", csvContent);

        uploadCsvBtn.setDisable(true);
        request(up).whenComplete((resp, err) -> Platform.runLater(() -> {
            uploadCsvBtn.setDisable(false);
            if (err != null) {
                log("Upload failed: " + err.getMessage());
                return;
            }
            log("Server: " + Json.toJson(resp));
        }));
    }

    private void startDevice() {
        String devId = newDeviceField.getText().trim();
        if (devId.isEmpty()) {
            log("Device Id cannot be empty");
            return;
        }
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            log("Invalid port");
            return;
        }

        startDeviceBtn.setDisable(true);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return startLocalDevice(devId, host, port);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, ioExecutor)
                .whenComplete((proc, err) -> Platform.runLater(() -> {
                    startDeviceBtn.setDisable(false);
                    if (err != null) {
                        log("Failed to start device: " + err.getMessage());
                        return;
                    }
                    startedDevices.add(proc);
                    startedDeviceIds.add(devId);
                    deviceConsoleLines.putIfAbsent(devId, new ArrayDeque<>());
                    consoleDeviceBox.getItems().setAll(sorted(startedDeviceIds));
                    if (consoleDeviceBox.getValue() == null) {
                        consoleDeviceBox.setValue(devId);
                    }
                    log("Started device '" + devId + "' (pid=" + proc.pid() + ")");
                    log("Logs: server-logs/device-" + devId + ".log");
                    newDeviceField.clear();
                    // Optionally refresh list after a brief delay (device needs time to register)
                    ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "ownerfx-refresh");
                        t.setDaemon(true);
                        return t;
                    });
                    s.schedule(() -> Platform.runLater(this::refreshDevices), 500, TimeUnit.MILLISECONDS);
                    s.shutdown();
                }));
    }

    private static List<String> sorted(Collection<String> ids) {
        List<String> out = new ArrayList<>(ids);
        out.sort(String::compareTo);
        return out;
    }

    private void startDeviceConsolePolling() {
        logPoller.scheduleAtFixedRate(() -> {
            try {
                pollDeviceConsoles();
            } catch (Exception ignored) {
            }
        }, 300, 500, TimeUnit.MILLISECONDS);
    }

    private void pollDeviceConsoles() {
        if (startedDeviceIds.isEmpty()) return;
        for (String devId : startedDeviceIds) {
            Path logFile = Path.of("server-logs", "device-" + devId + ".log");
            if (!Files.exists(logFile)) continue;

            long offset = deviceConsoleOffsets.getOrDefault(devId, 0L);
            try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
                long len = raf.length();
                if (len < offset) offset = 0L;
                raf.seek(offset);

                boolean anyNew = false;
                String line;
                while ((line = raf.readLine()) != null) {
                    anyNew = true;
                    appendConsoleLine(devId, line);
                }

                deviceConsoleOffsets.put(devId, raf.getFilePointer());
                if (anyNew && devId.equals(consoleDeviceBox.getValue())) {
                    Platform.runLater(this::refreshDeviceConsoleView);
                }
            } catch (IOException ignored) {
                // best-effort tailing
            }
        }
    }

    private void appendConsoleLine(String devId, String line) {
        Deque<String> dq = deviceConsoleLines.computeIfAbsent(devId, k -> new ArrayDeque<>());
        synchronized (dq) {
            dq.addLast(line);
            while (dq.size() > 500) dq.removeFirst();
        }
    }

    private void refreshDeviceConsoleView() {
        String devId = consoleDeviceBox.getValue();
        if (devId == null || devId.isBlank()) {
            deviceConsoleArea.clear();
            return;
        }
        Deque<String> dq = deviceConsoleLines.get(devId);
        if (dq == null) {
            deviceConsoleArea.clear();
            return;
        }
        StringBuilder sb = new StringBuilder();
        synchronized (dq) {
            for (String line : dq) {
                sb.append(line).append('\n');
            }
        }
        deviceConsoleArea.setText(sb.toString());
        deviceConsoleArea.positionCaret(deviceConsoleArea.getLength());
    }

    private CompletableFuture<Message> request(Message m) {
        return CompletableFuture.supplyAsync(() -> {
            if (!isConnected()) throw new IllegalStateException("Not connected");
            try {
                synchronized (outLock) {
                    FrameIO.writeJsonFrame(out, m);
                }
                Message resp = responseQueue.poll(5, TimeUnit.SECONDS);
                if (resp == null) throw new RuntimeException("Timed out waiting for server response");
                return resp;
            } catch (IOException e) {
                safeClose();
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted");
            }
        }, ioExecutor);
    }

    private boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    private void safeClose() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        } finally {
            socket = null;
            in = null;
            out = null;
        }
        Platform.runLater(() -> disconnectBtn.setDisable(true));
    }

    private void stopReader() {
        readerRunning = false;
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
        }
        readerThread = null;
        responseQueue.clear();
    }

    private void startReader() {
        if (readerRunning) return;
        responseQueue.clear();
        readerRunning = true;
        readerThread = new Thread(() -> {
            try {
                while (readerRunning && isConnected()) {
                    String json = FrameIO.readJsonFrame(in);
                    Message msg = Json.fromJson(json, Message.class);
                    if (msg.type == MessageType.STATUS) {
                        handleStatusPush(msg);
                    } else if (msg.type == MessageType.DEVICE_LOG) {
                        handleDeviceLogPush(msg);
                    } else {
                        responseQueue.offer(msg);
                    }
                }
            } catch (Exception e) {
                // socket closed or error
            } finally {
                readerRunning = false;
            }
        }, "ownerfx-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void handleStatusPush(Message msg) {
        String dev = msg.deviceId;
        if (dev == null && msg.payload != null && msg.payload.get("deviceId") != null) {
            dev = String.valueOf(msg.payload.get("deviceId"));
        }
        String state = msg.payload == null ? null : (msg.payload.get("state") == null ? null : String.valueOf(msg.payload.get("state")));
        if (dev == null) return;
        if (state != null) deviceState.put(dev, state);

        final String deviceIdFinal = dev;
        Platform.runLater(() -> {
            if (!deviceList.getItems().contains(deviceIdFinal)) {
                deviceList.getItems().add(deviceIdFinal);
                deviceList.getItems().sort(String::compareTo);
            }
            deviceList.refresh();
        });
    }

    private void handleDeviceLogPush(Message msg) {
        String dev = msg.deviceId;
        if (dev == null && msg.payload != null && msg.payload.get("deviceId") != null) {
            dev = String.valueOf(msg.payload.get("deviceId"));
        }
        String line = msg.payload == null ? null : (msg.payload.get("line") == null ? null : String.valueOf(msg.payload.get("line")));
        if (dev == null || line == null) return;

        appendConsoleLine(dev, line);

        final String deviceIdFinal = dev;
        Platform.runLater(() -> {
            // Ensure device appears in console selector even if not started from UI
            if (!startedDeviceIds.contains(deviceIdFinal)) {
                startedDeviceIds.add(deviceIdFinal);
                consoleDeviceBox.getItems().setAll(sorted(startedDeviceIds));
            }
            if (consoleDeviceBox.getValue() == null) {
                consoleDeviceBox.setValue(deviceIdFinal);
            }
            if (deviceIdFinal.equals(consoleDeviceBox.getValue())) {
                refreshDeviceConsoleView();
            }
        });
    }

    private void shutdown() {
        disconnect();
        for (Process p : startedDevices) {
            if (p != null && p.isAlive()) p.destroy();
        }
        logPoller.shutdownNow();
        ioExecutor.shutdownNow();
    }

    private void log(String msg) {
        String line = "[" + TS.format(LocalDateTime.now()) + "] " + msg;
        if (Platform.isFxApplicationThread()) {
            logArea.appendText(line + "\n");
        } else {
            Platform.runLater(() -> logArea.appendText(line + "\n"));
        }
    }

    private static Path parseUserPath(String raw) {
        String s = (raw == null) ? "" : raw.trim();
        if (s.isEmpty()) return Path.of("schedule.csv");

        String wslPrefix = "\\\\wsl.localhost\\";
        if (s.startsWith(wslPrefix)) {
            String remainder = s.substring(wslPrefix.length());
            int distroSep = remainder.indexOf('\\');
            if (distroSep >= 0) remainder = remainder.substring(distroSep);
            remainder = remainder.replace('\\', '/');
            if (remainder.startsWith("/")) return Path.of(remainder);
        }

        if (s.length() >= 3
                && Character.isLetter(s.charAt(0))
                && s.charAt(1) == ':'
                && s.charAt(2) == '\\') {
            char drive = Character.toLowerCase(s.charAt(0));
            String rest = s.substring(2).replace('\\', '/');
            return Path.of("/mnt/" + drive + rest);
        }

        if (s.indexOf('\\') >= 0 && s.indexOf('/') < 0) s = s.replace('\\', '/');
        return Path.of(s);
    }

    private static Process startLocalDevice(String deviceId, String host, int port) throws IOException {
        String classpath = System.getProperty("java.class.path");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        Path logDir = Path.of("server-logs");
        try {
            Files.createDirectories(logDir);
        } catch (IOException ignored) {
        }
        Path logFile = logDir.resolve("device-" + deviceId + ".log");
        if (!Files.exists(logFile)) {
            try {
                Files.writeString(logFile, "", StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            } catch (IOException ignored) {
            }
        }

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classpath,
                "smarthome.device.DeviceClient",
                deviceId,
                host,
                String.valueOf(port)
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return pb.start();
    }

    // ── Device delete / clear history ─────────────────────────────────────────────

    /**
     * Shows a confirmation dialog, then sends DELETE_DEVICE or CLEAR_DEVICE_HISTORY
     * and refreshes the device list on success.
     */
    private void deviceManageRequest(MessageType type, String deviceId, String confirmText) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, confirmText,
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.setTitle(type == MessageType.DELETE_DEVICE ? "Delete device" : "Clear history");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.OK) return;
            if (!isConnected()) { log("Not connected"); return; }
            Message m = Message.of(type);
            m.payload = Map.of("deviceId", deviceId);
            request(m).whenComplete((resp, err) -> Platform.runLater(() -> {
                if (err != null) {
                    log("Request failed: " + err.getMessage());
                    return;
                }
                if (resp.type == MessageType.ACK) {
                    if (type == MessageType.DELETE_DEVICE) {
                        deviceList.getItems().remove(deviceId);
                        deviceState.remove(deviceId);
                        log("Deleted device: " + deviceId);
                    } else {
                        log("Cleared usage history for: " + deviceId);
                    }
                    refreshDevices(); // reload stats
                } else {
                    log("Server: " + Json.toJson(resp));
                }
            }));
        });
    }

    // ── Device usage queries ────────────────────────────────────────────────

    /**
     * Requests ON-time for the currently selected device on the date chosen in
     * the schedule's DatePicker, then shows the result in the schedule tab.
     */
    private void getUsageForDate() {
        if (!isConnected()) { log("Not connected"); return; }
        String dev = deviceList.getSelectionModel().getSelectedItem();
        if (dev == null || dev.isBlank()) {
            log("Select a device from the left panel first");
            return;
        }
        LocalDate date = datePicker.getValue();
        if (date == null) { log("Pick a date in the Schedule tab"); return; }

        Message m = Message.of(MessageType.GET_DEVICE_USAGE);
        m.payload = Map.of("deviceId", dev, "date", date.toString());
        request(m).whenComplete((resp, err) -> Platform.runLater(() -> {
            if (err != null) { log("Usage query failed: " + err.getMessage()); return; }
            if (resp.type == MessageType.DEVICE_USAGE && resp.payload != null) {
                Object onMsObj = resp.payload.get("onMs");
                long onMs = onMsObj instanceof Number n ? n.longValue() : 0L;
                String formatted = formatDuration(onMs);
                usageSummaryLabel.setText(dev + " was ON for " + formatted + " on " + date
                        + "  (server timezone)");
                log("Usage: " + dev + " on " + date + " = " + formatted);
            }
        }));
    }

    /** Formats a millisecond duration as e.g. "1h 4m 30s". */
    private static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long secs  = ms / 1000;
        long mins  = secs / 60;  secs %= 60;
        long hours = mins / 60;  mins %= 60;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("h ");
        if (mins  > 0) sb.append(mins).append("m ");
        sb.append(secs).append("s");
        return sb.toString().trim();
    }

    /** Fills the Usage tab text area from the stats list returned by GET_DEVICE_STATS. */
    private void refreshUsageTab(List<Map<String, Object>> statsList) {
        if (statsList == null || statsList.isEmpty()) {
            usageArea.setText("No devices recorded yet. Connect a device first.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-14s %-5s %-7s %10s %10s %12s  %s%n",
                "Device", "Live", "State", "All-time", "Today", "Current ON", "Last Seen"));
        sb.append("-".repeat(80)).append("\n");
        for (Map<String, Object> row : statsList) {
            String  id        = mapStr(row, "deviceId");
            boolean conn      = Boolean.TRUE.equals(row.get("connected"));
            String  state     = mapStr(row, "state");
            long    totalOnMs = mapLong(row, "totalOnMs");
            long    todayOnMs = mapLong(row, "todayOnMs");
            long    curOnMs   = mapLong(row, "currentOnMs");
            long    lastMs    = mapLong(row, "lastSeenMs");
            String  lastStr   = lastMs == 0 ? "never"
                    : new java.util.Date(lastMs).toString().substring(4, 19);
            sb.append(String.format("%-14s %-5s %-7s %10s %10s %12s  %s%n",
                    id,
                    conn  ? "ON" : "off",
                    state == null ? "?" : state,
                    formatDuration(totalOnMs),
                    formatDuration(todayOnMs),
                    curOnMs > 0 ? formatDuration(curOnMs) : "-",
                    lastStr));
        }
        usageArea.setText(sb.toString());
    }

    private static String mapStr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static long mapLong(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
