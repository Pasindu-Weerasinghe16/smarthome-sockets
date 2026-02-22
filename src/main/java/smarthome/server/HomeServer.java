package smarthome.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.ZoneId;
import java.util.concurrent.*;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HomeServer {
    public static final ZoneId SERVER_ZONE = ZoneId.systemDefault();
    private static final Logger log = Logger.getLogger(HomeServer.class.getName());

    private final int port;
    private final DeviceRegistry registry = new DeviceRegistry();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final ExecutorService writerPool = Executors.newFixedThreadPool(8);
    private final ScheduledExecutorService schedulerPool = Executors.newScheduledThreadPool(4);
    private final CommandScheduler scheduler = new CommandScheduler(schedulerPool, registry);

    public HomeServer(int port) { this.port = port; }

    public void start() throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            log.info(() -> "HomeServer listening on port " + port + " (" + SERVER_ZONE + ")");
            while (true) {
                Socket s = ss.accept();
                clientPool.submit(new ClientSession(s, registry, scheduler, writerPool));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try (var is = HomeServer.class.getResourceAsStream("/logging.properties")) {
            if (is != null) LogManager.getLogManager().readConfiguration(is);
        }
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 5000;
        new HomeServer(port).start();
    }
}
