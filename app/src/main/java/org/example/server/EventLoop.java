package org.example.server;

import org.example.command.CommandRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EventLoop implements Runnable {
    public static final int CRON_INTERVAL_MS = 100; // 10Hz

    private final ServerContext serverContext;
    private final CommandRegistry registry;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private long lastCronMs = System.currentTimeMillis();
    private long clientTimeoutMs = 0; // 0 = disabled

    public EventLoop(ServerContext serverContext) {
        this.serverContext = serverContext;
        this.registry = serverContext.getRegistry();
    }

    public void setClientTimeoutMs(long timeoutMs) {
        this.clientTimeoutMs = timeoutMs;
    }

    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(serverContext.getBindAddress(), serverContext.getPort()));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running.set(true);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void stop() {
        running.set(false);
        if (selector != null) {
            selector.wakeup();
            try {
                selector.close();
            } catch (IOException ignored) {}
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {}
        }
        for (Connection conn : serverContext.getActiveConnections()) {
            conn.close();
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                int selected = selector.select(CRON_INTERVAL_MS);
                long now = System.currentTimeMillis();

                if (selected > 0) {
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> it = keys.iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();

                        if (!key.isValid()) {
                            continue;
                        }

                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else {
                            Connection conn = (Connection) key.attachment();
                            if (conn != null) {
                                if (key.isReadable()) {
                                    boolean active = conn.handleRead(key);
                                    if (!active) {
                                        serverContext.removeConnection(conn);
                                        serverContext.getPubSubManager().removeConnection(conn);
                                        key.cancel();
                                        continue;
                                    }
                                }
                                if (key.isValid() && key.isWritable()) {
                                    conn.flush(key);
                                }
                            }
                        }
                    }
                }

                // Periodic cron tick (10Hz)
                if (now - lastCronMs >= CRON_INTERVAL_MS) {
                    serverCron();
                    lastCronMs = now;
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("EventLoop error: " + e.getMessage());
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel sc = ssc.accept();
        if (sc != null) {
            sc.configureBlocking(false);
            Connection conn = new Connection(sc, registry);
            SelectionKey clientKey = sc.register(selector, SelectionKey.OP_READ, conn);
            serverContext.addConnection(conn);
        }
    }

    private void serverCron() {
        // Active key expiry
        if (serverContext.getKeyspace() != null) {
            serverContext.getKeyspace().getExpiryManager().activeExpireCycle();
        }

        // Memory eviction check
        if (serverContext.getMemoryTracker() != null && serverContext.getKeyspace() != null) {
            serverContext.getMemoryTracker().checkAndEvict(serverContext.getKeyspace());
        }

        // AOF periodic fsync
        if (serverContext.getAofWriter() != null) {
            serverContext.getAofWriter().cronFsync();
        }

        // RDB auto-save check
        if (serverContext.getRdbSnapshotter() != null) {
            serverContext.getRdbSnapshotter().cronCheck();
        }

        // Client idle timeout check
        if (clientTimeoutMs > 0) {
            long now = System.currentTimeMillis();
            for (Connection conn : serverContext.getActiveConnections()) {
                if (now - conn.getContext().lastActivityMs > clientTimeoutMs) {
                    conn.close();
                    serverContext.removeConnection(conn);
                    serverContext.getPubSubManager().removeConnection(conn);
                }
            }
        }
    }
}
