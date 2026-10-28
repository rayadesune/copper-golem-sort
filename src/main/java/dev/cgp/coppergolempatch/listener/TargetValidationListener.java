package dev.cgp.coppergolempatch.listener;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.cgp.coppergolempatch.config.PluginConfig;
import dev.cgp.coppergolempatch.memory.ChestKey;
import dev.cgp.coppergolempatch.memory.ChestMemory;
import dev.cgp.coppergolempatch.memory.GolemMemoryCache;
import dev.cgp.coppergolempatch.memory.MemoryEntry;
import dev.cgp.coppergolempatch.memory.UnplaceableRegistry;
import dev.cgp.coppergolempatch.similar.SimilarItems;
import dev.cgp.coppergolempatch.tidy.ContainerTidy;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Learns chest contents from every candidate the vanilla search evaluates,
 * and uses that memory to skip chests already known to be wrong so the
 * golem doesn't waste a trip + 3s examine on them. A deposit chest matches
 * if it's empty OR contains the held item type ANYWHERE among its slots
 * (vanilla's own rule - not just whatever happens to occupy the first slot).
 * Also tracks, per golem, whether the item it is currently carrying has been
 * accepted anywhere. A carry that no chest accepted, and that has stopped being
 * evaluated for {@code hold-timeout-ticks}, is handed back to a copper chest -
 * see {@link #watchHoldTimeout()}, which is the single place that decision is
 * made. The item type is then marked "unplaceable" (shared via gossip) so the
 * golem leaves it alone until a spot opens up.
 */
public final class TargetValidationListener implements Listener {

    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final Supplier<SimilarItems> similarItems;
    private final Supplier<ContainerTidy> tidy;
    private final GolemMemoryCache cache;
    private final Map<UUID, CarrySession> sessions = new HashMap<>();

    public TargetValidationListener(Plugin plugin, Supplier<PluginConfig> config,
                                    Supplier<SimilarItems> similarItems, Supplier<ContainerTidy> tidy,
                                    GolemMemoryCache cache) {
        this.plugin = plugin;
        this.config = config;
        this.similarItems = similarItems;
        this.tidy = tidy;
        this.cache = cache;
    }

    /**
     * A carry session only ever describes one golem's current trip, so it must
     * not outlive the golem. Without this, a busy world that keeps hatching and
     * killing golems grows the map forever - a few hundred bytes each, but
     * there is no other code path that ever removes an entry.
     */
    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            sessions.remove(golem.getUniqueId());
        }
    }

    @EventHandler
    public void onValidate(ItemTransportingEntityValidateTargetEvent event) {
        if (!config.get().enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof CopperGolem golem)) {
            return;
        }

        Block block = event.getBlock();
        long now = System.currentTimeMillis();

        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            return;
        }
        ItemStack[] contents = container.getInventory().getContents();

        if (isCopperChest(block.getType())) {
            // Vanilla only ever picks up whatever occupies the first
            // non-empty slot. If that happens to be an item we've already
            // learned has nowhere to go, every other (possibly perfectly
            // sortable) item behind it would be stuck forever - so bump the
            // blocked stack out of the way for the first sortable one we find.
            UnplaceableRegistry unplaceableForReorder = cache.getUnplaceable(golem);
            promoteSortableItem(container, contents, unplaceableForReorder, now);
        }

        ObservedContents observed = observeContents(contents);

        ChestKey key = ChestKey.of(block);
        ChestMemory memory = cache.getMemory(golem);
        memory.recordObservation(key, observed.firstItemType(), observed.allItemTypes(), now, config.get().maxEntriesPerGolem());
        cache.markMemoryDirty(golem);

        if (observed.firstItemType() == Material.AIR && !isCopperChest(block.getType())) {
            // A previously-unviable item could now fit here - give every
            // give-up mark a fresh chance instead of waiting out its TTL.
            UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
            if (unplaceable.clearAll()) {
                cache.markUnplaceableDirty(golem);
            }
        }

        decide(event, golem, key, memory, now, hasEmptySlot(contents));
    }

    /** A same-category deposit can only land in a free slot (different item
     * type, so it can't merge into an existing stack), which is why the
     * similar-items rule needs this and the exact-match rule does not. */
    private boolean hasEmptySlot(ItemStack[] contents) {
        for (ItemStack item : contents) {
            if (item == null || item.getType() == Material.AIR) {
                return true;
            }
        }
        return false;
    }

    private void decide(ItemTransportingEntityValidateTargetEvent event, CopperGolem golem,
                         ChestKey key, ChestMemory memory, long now, boolean hasEmptySlot) {
        Optional<MemoryEntry> entryOpt = memory.get(key);
        if (entryOpt.isEmpty() || entryOpt.get().isStale(config.get().entryTtlSeconds(), now)) {
            return;
        }
        MemoryEntry entry = entryOpt.get();

        ItemStack heldItem = golem.getEquipment().getItemInMainHand();
        Material heldType = heldItem.getType();
        boolean depositPhase = heldType != Material.AIR;

        CarrySession session = sessions.computeIfAbsent(golem.getUniqueId(), id -> new CarrySession());

        if (depositPhase) {
            if (isCopperChest(event.getBlock().getType())) {
                return;
            }
            boolean matches = entry.isEmpty() || entry.contains(heldType);
            if (!matches) {
                matches = matchesSimilarItem(event, golem, entry, heldType, hasEmptySlot);
            }
            event.setAllowed(matches);

            if (matches) {
                // Vanilla caps a trip at 16 items and always uses the
                // frontmost slot, so queue a post-deposit tidy to collapse the
                // fragments back into full stacks.
                tidy.get().schedule(event.getBlock());

                // Tell the watchdog this carry has somewhere to go. This is the
                // only fact it cannot learn by polling the hand every second; it
                // owns all the timing.
                if (session.heldItem != heldType) {
                    session.heldItem = heldType;
                    session.heldSinceEpochMillis = now;
                }
                session.hasValidTarget = true;

                UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
                if (unplaceable.clear(heldType)) {
                    cache.markUnplaceableDirty(golem);
                }
            }
        } else {
            if (!isCopperChest(event.getBlock().getType())) {
                return;
            }
            if (entry.isEmpty()) {
                event.setAllowed(false);
                return;
            }
            UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
            event.setAllowed(!isUnplaceable(unplaceable, entry.firstItemType(), now));
        }
    }

    /**
     * Vanilla accepts a chest only when it is empty or already holds the exact
     * item type. When the configured categories group that type together with
     * something the chest already holds (all wool colours, all planks, every
     * sword...), the deposit is accepted too - but only if the chest still has
     * a free slot, because a different item type cannot merge into an existing
     * stack. Without that check the golem would walk over, fail to insert,
     * walk back and repeat forever, which is worse than staying idle.
     */
    private boolean matchesSimilarItem(ItemTransportingEntityValidateTargetEvent event, CopperGolem golem,
                                       MemoryEntry entry, Material heldType, boolean hasEmptySlot) {
        SimilarItems similarity = similarItems.get();
        if (similarity == null || !similarity.enabled()) {
            return false;
        }
        if (similarity.requireEmptySlot() && !hasEmptySlot) {
            return false;
        }
        String group = similarity.findSharedGroup(entry.allItemTypes(), heldType);
        if (group == null) {
            return false;
        }
        if (similarity.debug()) {
            Block block = event.getBlock();
            plugin.getLogger().info(String.format("[similar-items] %s -> (%d,%d,%d) 命中分组“%s”",
                    heldType, block.getX(), block.getY(), block.getZ(), group));
        }
        return true;
    }

    /**
     * Ends a carry attempt that has nowhere legal to deposit.
     *
     * <p>Order matters, because the fallback is a real outcome and not a
     * theoretical one: a dropped item despawns after five minutes, so "give up
     * and drop it" is the last resort, never the first. The hand-off walks the
     * containers around the golem - copper chests first, then anything else that
     * accepts at least one unit - and only whatever still will not fit ends up on
     * the ground.
     */
    private void giveUp(CopperGolem golem, Material itemType) {
        if (!golem.isValid()) {
            return;
        }
        ItemStack currentlyHeld = golem.getEquipment().getItemInMainHand();
        if (currentlyHeld.getType() != itemType) {
            // Something else already resolved this (deposited, despawned, etc).
            return;
        }

        // addItem reports whatever it could not fit, so no separate "has this
        // container got room" check is needed: a full chest hands the stack
        // straight back and the walk moves on. Whatever survives every
        // candidate is dropped at the golem's feet - loudly, because a dropped
        // item despawns after five minutes.
        ItemStack remaining = currentlyHeld.clone();
        for (Container target : stowCandidates(golem)) {
            if (isEmpty(remaining)) {
                break;
            }
            Inventory inventory = target.getInventory();
            // Same rule the tidy pass uses: never write into a container a
            // player currently has open.
            if (!inventory.getViewers().isEmpty()) {
                continue;
            }
            Map<Integer, ItemStack> leftover = inventory.addItem(remaining.clone());
            remaining = leftover.isEmpty()
                    ? new ItemStack(Material.AIR)
                    : leftover.values().iterator().next().clone();
        }

        if (!isEmpty(remaining)) {
            // Worth a log line: the whole point of the container walk above is
            // that this should be rare, and a dropped item despawns after five
            // minutes. If this ever fires, the setup genuinely has nowhere left
            // to put the item.
            plugin.getLogger().info("[give-up] 无任何容器可容纳 " + remaining.getType()
                    + " x" + remaining.getAmount() + "，已掉落在傀儡脚下 " + golem.getLocation().getBlockX()
                    + "," + golem.getLocation().getBlockY() + "," + golem.getLocation().getBlockZ());
            golem.getWorld().dropItemNaturally(golem.getLocation(), remaining);
        }
        golem.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));

        CarrySession session = sessions.remove(golem.getUniqueId());
        if (session != null) {
            session.heldItem = null;
            session.hasValidTarget = false;
        }

        long now = System.currentTimeMillis();
        UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
        unplaceable.markUnplaceable(itemType, now, config.get().maxEntriesPerGolem());
        cache.markUnplaceableDirty(golem);
    }

    /**
     * The one judge of "this carry is going nowhere", called once a second from
     * the plugin's watchdog task.
     *
     * <p>It reads the hand directly instead of trusting the event stream,
     * because the case it exists for is precisely the one that produces no
     * events: once no chest can take the item, vanilla stops validating
     * candidates and the golem stands there holding it for good.
     *
     * <p>Two deadlines, both measured from the moment the hand picked the item
     * up. The soft one fires only while no chest has accepted it - the ordinary
     * "nowhere to put this" case. The hard one fires regardless, four times
     * later, for a golem that accepted a chest it can never actually reach
     * (blocked path, chest removed mid-trip, container it can see but not walk
     * to). Below those two lines there is nothing to track: no rejected set, no
     * quiet-period, no per-candidate clock.
     */
    public void watchHoldTimeout() {
        if (!config.get().enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long softMillis = Math.max(2000L, config.get().giveUpHoldTimeoutTicks() * 50L);
        long hardMillis = softMillis * 4;

        for (World world : Bukkit.getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                Material held = golem.getEquipment().getItemInMainHand().getType();
                CarrySession session = sessions.get(golem.getUniqueId());

                if (held == Material.AIR) {
                    if (session != null) {
                        session.heldItem = null;
                        session.hasValidTarget = false;
                    }
                    continue;
                }
                if (session == null) {
                    session = new CarrySession();
                    sessions.put(golem.getUniqueId(), session);
                }
                if (session.heldItem != held) {
                    session.heldItem = held;
                    session.hasValidTarget = false;
                    session.heldSinceEpochMillis = now;
                }

                long age = now - session.heldSinceEpochMillis;
                if (age < (session.hasValidTarget ? hardMillis : softMillis)) {
                    continue;
                }
                giveUp(golem, held);
            }
        }
    }

    /**
     * Containers to try when a golem gives up, best first: copper chests (the
     * golem's own storage, so the item goes back where it came from and player
     * sorted chests stay untouched), then everything else by distance.
     *
     * <p>Only chests, trapped chests, copper chests and barrels qualify.
     * Furnaces, hoppers, dispensers, brewing stands and shulker boxes are
     * deliberately excluded - they are machines or carried storage, and quietly
     * inserting a stack into one would break whatever the player built.
     *
     * <p>Scans the 3x3 chunks around the golem, skipping any chunk that is not
     * already loaded: touching a Block in an unloaded chunk makes Bukkit load it
     * synchronously on the server thread, inside an AI tick. The chunk's
     * tile-entity list is used purely to learn *where* containers are - each
     * position is re-resolved through {@code Block#getState()}, because a
     * BlockState taken straight out of that list is not guaranteed to be a
     * placed state, and writing into a detached copy would make the item vanish.
     */
    private List<Container> stowCandidates(CopperGolem golem) {
        World world = golem.getWorld();
        int centerX = golem.getLocation().getBlockX() >> 4;
        int centerZ = golem.getLocation().getBlockZ() >> 4;
        boolean anyContainer = config.get().giveUpFallbackToAnyContainer();

        List<Container> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!world.isChunkLoaded(centerX + dx, centerZ + dz)) {
                    continue;
                }
                for (BlockState listed : world.getChunkAt(centerX + dx, centerZ + dz).getTileEntities()) {
                    BlockState live = world.getBlockAt(listed.getX(), listed.getY(), listed.getZ()).getState();
                    if (!(live instanceof Container container)) {
                        continue;
                    }
                    boolean usable = isCopperChest(live.getType())
                            || (anyContainer && (live instanceof Chest || live instanceof Barrel));
                    if (usable && seen.add(positionKey(container))) {
                        found.add(container);
                    }
                }
            }
        }
        found.sort(Comparator
                .comparingInt((Container c) -> isCopperChest(c.getType()) ? 0 : 1)
                .thenComparingDouble(c -> c.getLocation().distanceSquared(golem.getLocation())));
        return found;
    }

    private static String positionKey(Container container) {
        return container.getWorld().getName() + ':' + container.getX() + ',' + container.getY() + ',' + container.getZ();
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
    }

    /** Swaps the first slot's stack with the first later stack of a different,
     * not-currently-unplaceable type, so vanilla's "grab the first item"
     * pickup naturally lands on something the golem can actually deliver.
     * Mutates contents in lockstep with the live inventory so callers don't
     * need to re-fetch it to see the post-swap state. */
    private void promoteSortableItem(Container container, ItemStack[] contents, UnplaceableRegistry unplaceable, long now) {
        // Swapping two slots under an open window desyncs the client's view of
        // the container - the player can then pick up a stack that isn't there
        // or drop one into the wrong slot. Never reorder a container someone is
        // looking at; leaving the blocked stack in front just means this golem
        // sits idle for one more cycle, which the give-up TTL already covers.
        if (!container.getInventory().getViewers().isEmpty()) {
            return;
        }
        int firstIndex = -1;
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].getType() != Material.AIR) {
                firstIndex = i;
                break;
            }
        }
        if (firstIndex == -1) {
            return;
        }
        Material firstType = contents[firstIndex].getType();
        if (!isUnplaceable(unplaceable, firstType, now)) {
            return;
        }

        for (int i = firstIndex + 1; i < contents.length; i++) {
            ItemStack candidate = contents[i];
            if (candidate == null || candidate.getType() == Material.AIR || candidate.getType() == firstType) {
                continue;
            }
            if (isUnplaceable(unplaceable, candidate.getType(), now)) {
                continue;
            }
            ItemStack blocked = contents[firstIndex];
            container.getInventory().setItem(firstIndex, candidate);
            container.getInventory().setItem(i, blocked);
            contents[firstIndex] = candidate;
            contents[i] = blocked;
            return;
        }
    }

    private boolean isUnplaceable(UnplaceableRegistry unplaceable, Material type, long now) {
        return unplaceable.get(type)
                .filter(mark -> !mark.isStale(config.get().unplaceableTtlSeconds(), now))
                .isPresent();
    }

    private boolean isCopperChest(Material material) {
        return material.name().endsWith("COPPER_CHEST");
    }

    private ObservedContents observeContents(ItemStack[] contents) {
        Material first = Material.AIR;
        Set<Material> all = new LinkedHashSet<>();
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                if (first == Material.AIR) {
                    first = item.getType();
                }
                all.add(item.getType());
            }
        }
        return new ObservedContents(first, all);
    }

    private record ObservedContents(Material firstItemType, Set<Material> allItemTypes) {
    }

    /**
     * Per-golem carry state, deliberately three fields wide. What the give-up
     * watchdog needs is exactly: what is in hand, since when it has been there
     * without a single chest being evaluated, and whether any chest has
     * accepted it. Tracking individual rejections adds nothing the clock does
     * not already cover.
     */
    private static final class CarrySession {
        private Material heldItem;
        private long heldSinceEpochMillis;
        private boolean hasValidTarget;
    }
}
