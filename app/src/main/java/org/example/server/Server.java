package org.example.server;

import org.example.command.*;
import org.example.command.pubsub.PubSubCommands;
import org.example.command.server.ConfigCommand;
import org.example.command.server.DbCommands;
import org.example.command.server.PersistenceCommands;
import org.example.command.server.SlowLogCommand;
import org.example.command.string.ExpiryCommands;
import org.example.command.string.StringCommands;
import org.example.command.txn.TransactionCommands;
import org.example.command.types.HashCommands;
import org.example.command.types.ListCommands;
import org.example.command.types.SetCommands;
import org.example.command.types.ZSetCommands;
import org.example.evict.MemoryTracker;
import org.example.obs.SlowLog;
import org.example.persist.AofLoader;
import org.example.persist.AofWriter;
import org.example.persist.RdbLoader;
import org.example.persist.RdbSnapshotter;
import org.example.pubsub.PubSubManager;
import org.example.store.Keyspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Server implements AutoCloseable {
    public record Config(
            int port,
            String bindAddress,
            int dbCount,
            long maxMemory,
            String maxMemoryPolicy,
            boolean aofEnabled,
            AofWriter.FsyncPolicy aofFsyncPolicy,
            String aofFileName,
            boolean rdbEnabled,
            String rdbFileName,
            String dataDir
    ) {
        public static Config defaultConfig() {
            return new Config(
                    6379,
                    "127.0.0.1",
                    16,
                    0,
                    "noeviction",
                    false,
                    AofWriter.FsyncPolicy.EVERYSEC,
                    "appendonly.aof",
                    true,
                    "dump.rdb",
                    "."
            );
        }
    }

    private final Config config;
    private final ServerContext serverContext;
    private final CommandRegistry registry;
    private final Keyspace keyspace;
    private final EventLoop eventLoop;
    private Thread serverThread;

    public Server() {
        this(Config.defaultConfig());
    }

    public Server(Config config) {
        this.config = config;
        this.keyspace = new Keyspace(config.dbCount());
        this.registry = new CommandRegistry();
        PubSubManager pubSubManager = new PubSubManager();
        SlowLog slowLog = new SlowLog();
        MemoryTracker memoryTracker = new MemoryTracker();
        memoryTracker.setMaxMemory(config.maxMemory());
        memoryTracker.setPolicyByName(config.maxMemoryPolicy());
        this.keyspace.setMemoryTracker(memoryTracker);
        this.registry.setSlowLog(slowLog);

        this.serverContext = new ServerContext(keyspace, registry, pubSubManager, slowLog, memoryTracker);
        this.serverContext.setPort(config.port());
        this.serverContext.setBindAddress(config.bindAddress());

        registerAllCommands();

        // Setup persistence
        Path dir = Paths.get(config.dataDir());
        try {
            Files.createDirectories(dir);
            if (config.rdbEnabled()) {
                Path rdbPath = dir.resolve(config.rdbFileName());
                RdbSnapshotter snapshotter = new RdbSnapshotter(keyspace, rdbPath);
                this.serverContext.setRdbSnapshotter(snapshotter);
            }
            if (config.aofEnabled()) {
                Path aofPath = dir.resolve(config.aofFileName());
                AofWriter writer = new AofWriter(aofPath, config.aofFsyncPolicy());
                this.serverContext.setAofWriter(writer);
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to initialize persistence: " + e.getMessage());
        }

        // Restore state (prefer AOF if enabled/exists, then RDB)
        restorePersistedData(dir);

        this.eventLoop = new EventLoop(serverContext);
    }

    private void registerAllCommands() {
        registry.register(new PingCommand());
        registry.register(new EchoCommand());
        registry.register(new QuitCommand());
        registry.register(new CommandCommand(registry));
        registry.register(new InfoCommand(serverContext));
        registry.register(new ClientCommand(serverContext));

        StringCommands.registerAll(registry, serverContext);
        ExpiryCommands.registerAll(registry, serverContext);
        DbCommands.registerAll(registry, serverContext);
        ListCommands.registerAll(registry, serverContext);
        HashCommands.registerAll(registry, serverContext);
        SetCommands.registerAll(registry, serverContext);
        ZSetCommands.registerAll(registry, serverContext);
        TransactionCommands.registerAll(registry, serverContext);
        PubSubCommands.registerAll(registry, serverContext);
        PersistenceCommands.registerAll(registry, serverContext);
        SlowLogCommand slowLogCommand = new SlowLogCommand(serverContext);
        registry.register(slowLogCommand);
        ConfigCommand.registerAll(registry, serverContext);
    }

    private void restorePersistedData(Path dir) {
        Path aofPath = dir.resolve(config.aofFileName());
        Path rdbPath = dir.resolve(config.rdbFileName());

        if (config.aofEnabled() && Files.exists(aofPath)) {
            try {
                int count = AofLoader.load(aofPath, registry);
                System.out.println("Restored " + count + " commands from AOF log: " + aofPath);
                return;
            } catch (Exception e) {
                System.err.println("Failed to load AOF: " + e.getMessage());
            }
        }

        if (config.rdbEnabled() && Files.exists(rdbPath)) {
            try {
                int count = RdbLoader.load(rdbPath, keyspace);
                System.out.println("Restored " + count + " keys from RDB snapshot: " + rdbPath);
            } catch (Exception e) {
                System.err.println("Failed to load RDB: " + e.getMessage());
            }
        }
    }

    public synchronized void start() throws IOException {
        eventLoop.start();
        serverThread = new Thread(eventLoop, "jredis-eventloop");
        serverThread.start();
    }

    public ServerContext getServerContext() {
        return serverContext;
    }

    public CommandRegistry getRegistry() {
        return registry;
    }

    public Keyspace getKeyspace() {
        return keyspace;
    }

    public EventLoop getEventLoop() {
        return eventLoop;
    }

    @Override
    public synchronized void close() {
        if (eventLoop != null) {
            eventLoop.stop();
        }
        if (serverContext.getAofWriter() != null) {
            try {
                serverContext.getAofWriter().close();
            } catch (Exception ignored) {}
        }
        if (serverThread != null) {
            try {
                serverThread.join(1000);
            } catch (InterruptedException ignored) {}
        }
    }
}
