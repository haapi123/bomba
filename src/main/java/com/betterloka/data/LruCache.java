package com.betterloka.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A cache that forgets: bounded in size, and with an age past which an entry is no longer trusted.
 *
 * <p>Plain {@link java.util.concurrent.ConcurrentHashMap}s were used for this before. In practice
 * they stayed small — the number of market sellers or of players whose stats have been looked at is
 * bounded by the server, not by how long the game has been running — but nothing in the code said
 * so, and a name cached at login was still being shown hours later. Both are fixed by saying the
 * limit out loud.
 *
 * <p>Synchronised rather than lock-free: entries are read on the render thread and written from
 * worker threads, and the critical section is a map lookup. A lock-free structure with an eviction
 * order is a great deal of machinery for a few hundred strings.
 */
public final class LruCache<K, V> {
    private record Entry<V>(V value, long storedAtMillis) {
    }

    private final int maxEntries;
    private final long ttlMillis;
    private final Map<K, Entry<V>> entries;

    /**
     * @param maxEntries how many to keep; the least recently used is dropped past this
     * @param ttlMillis  how long an entry stays good, or {@code 0} for no expiry
     */
    public LruCache(int maxEntries, long ttlMillis) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlMillis = ttlMillis;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > LruCache.this.maxEntries;
            }
        };
    }

    /** @return the value, or {@code null} if it was never stored or has expired. */
    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (expired(entry)) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    public synchronized void put(K key, V value) {
        entries.put(key, new Entry<>(value, System.currentTimeMillis()));
    }

    /** @return true if a live entry is held, without disturbing the eviction order's usefulness. */
    public synchronized boolean has(K key) {
        return get(key) != null;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private boolean expired(Entry<V> entry) {
        return ttlMillis > 0 && System.currentTimeMillis() - entry.storedAtMillis() > ttlMillis;
    }
}
