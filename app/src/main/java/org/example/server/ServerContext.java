package org.example.server;

import org.example.command.CommandRegistry;
import org.example.evict.MemoryTracker;
import org.example.obs.SlowLog;
import org.example.persist.AofWriter;
import org.example.persist.RdbSnapshotter;
import org.example.pubsub.PubSubManager;
import org.example.store.Keyspace;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerContext {
    public static final String VERSION = "1.0.0-jredis";
    public final long startTimeMs = System.currentTimeMillis();

    private int port = 6379;
    private String bindAddress = "127.0.0.1";
    private final Keyspace keyspace;
    private final CommandRegistry registry;
    private final PubSubManager pubSubManager;
    private final SlowLog slowLog;
    private final MemoryTracker memoryTracker;
    private AofWriter aofWriter;
    private RdbSnapshotter rdbSnapshotter;
    private final Set<Connection> activeConnections = ConcurrentHashMap.newKeySet();

    public ServerContext(Keyspace keyspace, CommandRegistry registry, PubSubManager pubSubManager,
                         SlowLog slowLog, MemoryTracker memoryTracker) {
        this.keyspace = keyspace;
        this.registry = registry;
        this.pubSubManager = pubSubManager;
        this.slowLog = slowLog;
        this.memoryTracker = memoryTracker;
    }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }

    public Keyspace getKeyspace() { return keyspace; }
    public CommandRegistry getRegistry() { return registry; }
    public PubSubManager getPubSubManager() { return pubSubManager; }
    public SlowLog getSlowLog() { return slowLog; }
    public MemoryTracker getMemoryTracker() { return memoryTracker; }

    public AofWriter getAofWriter() { return aofWriter; }
    public void setAofWriter(AofWriter aofWriter) { this.aofWriter = aofWriter; }

    public RdbSnapshotter getRdbSnapshotter() { return rdbSnapshotter; }
    public void setRdbSnapshotter(RdbSnapshotter rdbSnapshotter) { this.rdbSnapshotter = rdbSnapshotter; }

    public void addConnection(Connection conn) {
        activeConnections.add(conn);
    }

    public void removeConnection(Connection conn) {
        activeConnections.remove(conn);
    }

    public Set<Connection> getActiveConnections() {
        return Collections.unmodifiableSet(activeConnections);
    }

    public int getClientCount() {
        return activeConnections.size();
    }
}
