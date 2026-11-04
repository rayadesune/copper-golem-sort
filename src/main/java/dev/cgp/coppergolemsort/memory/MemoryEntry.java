package dev.cgp.coppergolemsort.memory;

import org.bukkit.Material;

import java.util.Set;

/**
 * What a golem last observed in one chest. {@code firstItemType} is whichever
 * item occupies the first non-empty slot (what a golem would actually pick up
 * from a copper chest); {@code allItemTypes} is every distinct item type
 * present anywhere in the chest, since vanilla matches a deposit target by
 * "empty, or contains this item type" against the whole chest, not just its
 * first slot.
 *
 * <p>{@code hasFreeSlot} is what makes "can this chest take one more item" answerable
 * without opening it again: a shared category only qualifies as a deposit target while
 * the chest still has an empty slot, because a different item type cannot merge into an
 * existing stack.
 *
 * <p>{@code copperChest} is recorded because a copper chest is the golem's own storage,
 * never a deposit target. Without that flag, two copper chests holding the same item
 * would each vouch for the other and the item would look deliverable forever.
 */
public record MemoryEntry(ChestKey key, Material firstItemType, Set<Material> allItemTypes,
                          boolean hasFreeSlot, boolean copperChest, long lastSeenEpochMillis) {

    public MemoryEntry(ChestKey key, Material firstItemType, Set<Material> allItemTypes,
                       boolean hasFreeSlot, boolean copperChest, long lastSeenEpochMillis) {
        this.key = key;
        this.firstItemType = firstItemType;
        this.allItemTypes = Set.copyOf(allItemTypes);
        this.hasFreeSlot = hasFreeSlot;
        this.copperChest = copperChest;
        this.lastSeenEpochMillis = lastSeenEpochMillis;
    }

    /** Older call sites (and entries persisted before 0.7.0) know nothing about
     * free slots or chest kinds; those defaults keep them permissive, which is
     * exactly how pre-0.7.0 behaved. */
    public MemoryEntry(ChestKey key, Material firstItemType, Set<Material> allItemTypes, long lastSeenEpochMillis) {
        this(key, firstItemType, allItemTypes, true, false, lastSeenEpochMillis);
    }

    public boolean isDepositTarget() {
        return !copperChest;
    }

    public boolean isEmpty() {
        return firstItemType == Material.AIR;
    }

    public boolean contains(Material type) {
        return allItemTypes.contains(type);
    }

    public boolean isStale(long ttlSeconds, long nowEpochMillis) {
        return nowEpochMillis - lastSeenEpochMillis > ttlSeconds * 1000L;
    }
}
