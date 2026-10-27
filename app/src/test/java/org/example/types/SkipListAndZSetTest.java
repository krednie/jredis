package org.example.types;

import org.example.oracle.InMemoryTarget;
import org.example.server.Server;
import org.example.store.ByteKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SkipListAndZSetTest {

    private Server server;
    private InMemoryTarget target;

    @BeforeEach
    void setUp() {
        server = new Server();
        target = new InMemoryTarget(server.getRegistry());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    private String exec(String... args) throws Exception {
        byte[] reply = target.send(args);
        return new String(reply, StandardCharsets.UTF_8);
    }

    @Test
    void testSkipListPropertyVsTreeMap() {
        SkipList sl = new SkipList();
        // Model with sorted list
        List<Map.Entry<Double, ByteKey>> model = new ArrayList<>();
        Random rand = new Random(42);

        for (int i = 0; i < 500; i++) {
            double score = rand.nextInt(100);
            ByteKey member = ByteKey.of("mem:" + i);
            sl.insert(score, member);
            model.add(new AbstractMap.SimpleEntry<>(score, member));
        }

        assertEquals(500, sl.getLength());

        // Sort model according to Redis rules: ascending score, tie-break by member
        model.sort((e1, e2) -> {
            int cmp = Double.compare(e1.getKey(), e2.getKey());
            if (cmp != 0) return cmp;
            return e1.getValue().compareTo(e2.getValue());
        });

        // Verify ranks and getNodeByRank match model exactly
        for (int i = 0; i < model.size(); i++) {
            Map.Entry<Double, ByteKey> expected = model.get(i);
            int rank = sl.getRank(expected.getKey(), expected.getValue());
            assertEquals(i + 1, rank, "Rank mismatch for element at index " + i);

            SkipList.Node node = sl.getNodeByRank(i + 1);
            assertNotNull(node);
            assertEquals(expected.getValue(), node.member);
            assertEquals(expected.getKey(), node.score);
        }

        // Test deletes
        for (int i = 0; i < 100; i++) {
            Map.Entry<Double, ByteKey> target = model.remove(0);
            boolean deleted = sl.delete(target.getKey(), target.getValue());
            assertTrue(deleted);
        }
        assertEquals(400, sl.getLength());
    }

    @Test
    void testZSetCommands() throws Exception {
        assertEquals(":3\r\n", exec("ZADD", "leaderboard", "100", "alice", "200", "bob", "150", "charlie"));
        assertEquals(":3\r\n", exec("ZCARD", "leaderboard"));

        // ZSCORE
        assertEquals("$3\r\n100\r\n", exec("ZSCORE", "leaderboard", "alice"));
        assertEquals("$3\r\n200\r\n", exec("ZSCORE", "leaderboard", "bob"));

        // ZRANK (0-based)
        assertEquals(":0\r\n", exec("ZRANK", "leaderboard", "alice"));
        assertEquals(":1\r\n", exec("ZRANK", "leaderboard", "charlie"));
        assertEquals(":2\r\n", exec("ZRANK", "leaderboard", "bob"));

        // ZREVRANK
        assertEquals(":0\r\n", exec("ZREVRANK", "leaderboard", "bob"));
        assertEquals(":2\r\n", exec("ZREVRANK", "leaderboard", "alice"));

        // ZRANGE
        assertEquals("*3\r\n$5\r\nalice\r\n$7\r\ncharlie\r\n$3\r\nbob\r\n", exec("ZRANGE", "leaderboard", "0", "-1"));

        // ZREVRANGE with scores
        String revWithScores = exec("ZREVRANGE", "leaderboard", "0", "1", "WITHSCORES");
        assertTrue(revWithScores.contains("bob") && revWithScores.contains("200"));

        // ZINCRBY
        assertEquals("$3\r\n160\r\n", exec("ZINCRBY", "leaderboard", "60", "alice"));
        assertEquals(":1\r\n", exec("ZRANK", "leaderboard", "alice")); // moved above charlie

        // ZCOUNT
        assertEquals(":3\r\n", exec("ZCOUNT", "leaderboard", "150", "200"));

        // ZREM
        assertEquals(":1\r\n", exec("ZREM", "leaderboard", "alice"));
        assertEquals(":2\r\n", exec("ZCARD", "leaderboard"));
    }
}
