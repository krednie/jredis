package org.example.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalDictTest {

    @Test
    void testIncrementalRehashWorkflow() {
        IncrementalDict dict = new IncrementalDict();
        assertFalse(dict.isRehashing());

        // Insert 20 elements (initial capacity is 16, so threshold 16 is crossed)
        for (int i = 0; i < 20; i++) {
            ByteKey k = ByteKey.of("key:" + i);
            Value v = new Value(Value.Type.STRING, ("val:" + i).getBytes(StandardCharsets.US_ASCII));
            dict.put(k, v);
        }

        assertEquals(20, dict.size());

        // Verify all 20 elements are retrievable
        for (int i = 0; i < 20; i++) {
            ByteKey k = ByteKey.of("key:" + i);
            Value v = dict.get(k);
            assertNotNull(v, "Missing key:" + i);
            assertEquals("val:" + i, new String(v.asString(), StandardCharsets.US_ASCII));
        }

        // Run remaining rehash steps until complete
        while (dict.isRehashing()) {
            dict.rehash(1);
        }

        assertFalse(dict.isRehashing());
        assertEquals(20, dict.size());

        // Verify elements after rehashing finished
        for (int i = 0; i < 20; i++) {
            ByteKey k = ByteKey.of("key:" + i);
            Value v = dict.get(k);
            assertNotNull(v);
        }

        // Test deletes
        for (int i = 0; i < 10; i++) {
            ByteKey k = ByteKey.of("key:" + i);
            Value removed = dict.remove(k);
            assertNotNull(removed);
        }
        assertEquals(10, dict.size());
    }
}
