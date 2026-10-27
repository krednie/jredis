package org.example.pubsub;

import org.example.protocol.Reply;
import org.example.server.Connection;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PubSubManager {
    private final Map<String, Set<Connection>> channelSubscribers = new ConcurrentHashMap<>();
    private final Map<String, Set<Connection>> patternSubscribers = new ConcurrentHashMap<>();

    public synchronized int subscribe(Connection conn, String channel) {
        channelSubscribers.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(conn);
        conn.getContext().subscribedChannels.add(channel);
        conn.getContext().pubSubMode = true;
        return totalSubscriptions(conn);
    }

    public synchronized int unsubscribe(Connection conn, String channel) {
        Set<Connection> subs = channelSubscribers.get(channel);
        if (subs != null) {
            subs.remove(conn);
            if (subs.isEmpty()) {
                channelSubscribers.remove(channel);
            }
        }
        conn.getContext().subscribedChannels.remove(channel);
        if (totalSubscriptions(conn) == 0) {
            conn.getContext().pubSubMode = false;
        }
        return totalSubscriptions(conn);
    }

    public synchronized int publish(String channel, byte[] message) {
        int delivered = 0;
        Set<Connection> subs = channelSubscribers.get(channel);
        if (subs != null) {
            for (Connection conn : subs) {
                if (!conn.isClosed()) {
                    Reply reply = new Reply();
                    reply.arrayHeader(3);
                    reply.bulk("message");
                    reply.bulk(channel);
                    reply.bulk(message);
                    conn.queueReply(reply.toBytes(), null);
                    delivered++;
                }
            }
        }

        // Patterns
        for (Map.Entry<String, Set<Connection>> entry : patternSubscribers.entrySet()) {
            String pattern = entry.getKey();
            if (matchesPattern(pattern, channel)) {
                for (Connection conn : entry.getValue()) {
                    if (!conn.isClosed()) {
                        Reply reply = new Reply();
                        reply.arrayHeader(4);
                        reply.bulk("pmessage");
                        reply.bulk(pattern);
                        reply.bulk(channel);
                        reply.bulk(message);
                        conn.queueReply(reply.toBytes(), null);
                        delivered++;
                    }
                }
            }
        }

        return delivered;
    }

    public synchronized void removeConnection(Connection conn) {
        for (String ch : new ArrayList<>(conn.getContext().subscribedChannels)) {
            unsubscribe(conn, ch);
        }
        for (String pat : new ArrayList<>(conn.getContext().subscribedPatterns)) {
            Set<Connection> set = patternSubscribers.get(pat);
            if (set != null) {
                set.remove(conn);
                if (set.isEmpty()) {
                    patternSubscribers.remove(pat);
                }
            }
        }
        conn.getContext().subscribedChannels.clear();
        conn.getContext().subscribedPatterns.clear();
        conn.getContext().pubSubMode = false;
    }

    public int totalSubscriptions(Connection conn) {
        return conn.getContext().subscribedChannels.size() + conn.getContext().subscribedPatterns.size();
    }

    public static boolean matchesPattern(String pattern, String str) {
        // Simple glob pattern matcher (* and ?)
        return str.matches(pattern.replace(".", "\\.").replace("*", ".*").replace("?", "."));
    }
}
