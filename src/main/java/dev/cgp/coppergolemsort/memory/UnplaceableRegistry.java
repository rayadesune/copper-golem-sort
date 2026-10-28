package dev.cgp.coppergolemsort.memory;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks item types a golem has given up trying to store anywhere known,
 * so it (and, via gossip, other golems) stop picking that item back up
 * until either the mark expires or a matching/empty chest is observed.
 */
public final class UnplaceableRegistry {

    private final Map<Material, UnplaceableEntry> entries = new LinkedHashMap<>();

    public static UnplaceableRegistry of(Map<Material, UnplaceableEntry> initial) {
        UnplaceableRegistry registry = new UnplaceableRegistry();
        registry.entries.putAll(initial);
        return registry;
    }

    public void markUnplaceable(Material itemType, long nowEpochMillis, int maxEntries) {
        entries.put(itemType, new UnplaceableEntry(itemType, nowEpochMillis));
        evictIfOverCapacity(maxEntries);
    }

    /** Returns true if a mark existed and was removed. */
    public boolean clear(Material itemType) {
        return entries.remove(itemType) != null;
    }

    /** Returns true if there was at least one mark to remove. */
    public boolean clearAll() {
        if (entries.isEmpty()) {
            return false;
        }
        entries.clear();
        return true;
    }

    public Optional<UnplaceableEntry> get(Material itemType) {
        return Optional.ofNullable(entries.get(itemType));
    }

    /**
     * Drops marks that have already expired and reports whether anything went.
     *
     * <p>Expiry is judged at read time, so a stale mark never affects a
     * decision - but it does keep this map non-empty forever, and the periodic
     * sweep uses "map is empty" as its early exit. Left unpruned, a golem that
     * gave up once pays for a full chest re-check on every gossip pass for the
     * rest of its life.
     */
    public boolean prune(long ttlSeconds, long nowEpochMillis) {
        if (entries.isEmpty()) {
            return false;
        }
        return entries.values().removeIf(entry -> entry.isStale(ttlSeconds, nowEpochMillis));
    }

    public Map<Material, UnplaceableEntry> asMap() {
        return Map.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public static UnplaceableRegistry mergeFreshestWins(UnplaceableRegistry a, UnplaceableRegistry b, int maxEntries) {
        Map<Material, UnplaceableEntry> merged = new LinkedHashMap<>(a.entries);
        for (Map.Entry<Material, UnplaceableEntry> e : b.entries.entrySet()) {
            UnplaceableEntry existing = merged.get(e.getKey());
            if (existing == null || e.getValue().markedAtEpochMillis() > existing.markedAtEpochMillis()) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        UnplaceableRegistry result = UnplaceableRegistry.of(merged);
        result.evictIfOverCapacity(maxEntries);
        return result;
    }

    private void evictIfOverCapacity(int maxEntries) {
        while (entries.size() > maxEntries) {
            Material stalest = null;
            long oldest = Long.MAX_VALUE;
            for (UnplaceableEntry entry : entries.values()) {
                if (entry.markedAtEpochMillis() < oldest) {
                    oldest = entry.markedAtEpochMillis();
                    stalest = entry.itemType();
                }
            }
            if (stalest == null) {
                break;
            }
            entries.remove(stalest);
        }
    }
}
