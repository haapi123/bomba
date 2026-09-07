package com.betterloka.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LruCacheTest {
    @Test
    void keepsWhatFitsAndDropsTheOldest() {
        LruCache<String, String> cache = new LruCache<>(3, 0);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        cache.put("d", "4");

        assertEquals(3, cache.size());
        assertNull(cache.get("a"), "the least recently used should be the one to go");
        assertEquals("4", cache.get("d"));
    }

    /** Reading an entry has to count as using it, or the cache evicts what is being looked at. */
    @Test
    void readingKeepsAnEntryAlive() {
        LruCache<String, String> cache = new LruCache<>(2, 0);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.get("a");
        cache.put("c", "3");

        assertEquals("1", cache.get("a"));
        assertNull(cache.get("b"));
    }

    @Test
    void forgetsAnEntryPastItsAge() throws Exception {
        LruCache<String, String> cache = new LruCache<>(10, 40);
        cache.put("seller", "haapi");
        assertEquals("haapi", cache.get("seller"));

        Thread.sleep(80);
        assertNull(cache.get("seller"));
        assertFalse(cache.has("seller"));
    }

    @Test
    void zeroTtlMeansNoExpiry() throws Exception {
        LruCache<String, String> cache = new LruCache<>(10, 0);
        cache.put("seller", "haapi");
        Thread.sleep(40);
        assertTrue(cache.has("seller"));
    }

    /** The size cap is the whole point: this is what an unbounded map failed to do. */
    @Test
    void staysBoundedUnderAFloodOfDistinctKeys() {
        LruCache<Integer, Integer> cache = new LruCache<>(100, 0);
        for (int i = 0; i < 100_000; i++) {
            cache.put(i, i);
        }
        assertEquals(100, cache.size());
    }
}
