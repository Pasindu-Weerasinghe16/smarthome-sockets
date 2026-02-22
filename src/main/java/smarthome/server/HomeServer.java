package smarthome.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.*;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HomeServer {
    public static final ZoneId SERVER_ZONE = ZoneId.systemDefault();
    private static final Logger log = Logger.getLogger(HomeServer.class.getName());

    private final int port;
    private final DeviceRegistry registry = new DeviceRegistry();
    private final OwnerHub ownerHub = new OwnerHub();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final ExecutorService writerPool = Executors.newFixedThreadPool(8);
    private final ScheduledExecutorService schedulerPool = Executors.newScheduledThreadPool(4);
    private final CommandScheduler scheduler = new CommandScheduler(schedulerPool, registry);
    private final Persistence persistence;

    public HomeServer(int port, Persistence persistence) {
        this.port = port;
        this.persistence = persistence;
    }

    public void start() throws IOException {
        // Best-effort cleanup and replay of persisted schedules
        try {
            long now = System.currentTimeMillis();
            persistence.cleanupScheduleOlderThan(now - 24L * 3600L * 1000L);
            scheduler.schedule(persistence.loadPendingScheduleEntries(now));
        } catch (RuntimeException e) {
            log.warning(() -> "Persistence not available: " + e.getMessage());
        }

        try (ServerSocket ss = new ServerSocket(port)) {
            log.info(() -> "HomeServer listening on port " + port + " (" + SERVER_ZONE + ")");
            while (true) {
                Socket s = ss.accept();
                clientPool.submit(new ClientSession(s, registry, scheduler, writerPool, persistence, ownerHub));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try (var is = HomeServer.class.getResourceAsStream("/logging.properties")) {
            if (is != null) LogManager.getLogManager().readConfiguration(is);
        }
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 5000;
        Persistence p = new Persistence(Path.of("smarthome.db"));
        p.init();
        new HomeServer(port, p).start();
    }
}
