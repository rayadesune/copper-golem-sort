package dev.cgp.coppergolemsort.listener;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.cgp.coppergolemsort.config.PluginConfig;
import dev.cgp.coppergolemsort.memory.ChestKey;
import dev.cgp.coppergolemsort.memory.ChestMemory;
import dev.cgp.coppergolemsort.memory.GolemMemoryCache;
import dev.cgp.coppergolemsort.memory.MemoryEntry;
import dev.cgp.coppergolemsort.memory.UnplaceableRegistry;
import dev.cgp.coppergolemsort.similar.SimilarItems;
import dev.cgp.coppergolemsort.tidy.ContainerTidy;
import io.papermc.paper.event.entity.ItemTransportingEntitySortEvent;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.Event;
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
 *
 * <p>Two Paper events are handled, and the split matters: {@link #onValidate}
 * decides which container the golem walks to, {@link #onSort} decides whether
 * the held item actually belongs in it. Only the second one can authorise a
 * same-category deposit; the first can merely steer the walk.
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

        ChestKey key = ChestKey.of(block);
        ChestMemory memory = cache.getMemory(golem);
        boolean freeSlot = hasEmptySlot(contents);
        boolean copperChest = isCopperChest(block.getType());

        if (copperChest) {
            // Vanilla only ever picks up whatever occupies the first
            // non-empty slot. If that happens to be an item with nowhere to
            // go, every other (possibly perfectly sortable) item behind it is
            // stuck forever - so bump the blocked stack out of the way for the
            // first item we do know a destination for.
            promoteDeliverableItem(container, contents, memory, key, cache.getUnplaceable(golem), now);
            freeSlot = hasEmptySlot(contents);
        }

        ObservedContents observed = observeContents(contents);

        memory.recordObservation(key, observed.firstItemType(), observed.allItemTypes(), freeSlot, copperChest, now,
                config.get().maxEntriesPerGolem());
        cache.markMemoryDirty(golem);

        if (observed.firstItemType() == Material.AIR && !copperChest) {
            // A previously-unviable item could now fit here - give every
            // give-up mark a fresh chance instead of waiting out its TTL.
            UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
            if (unplaceable.clearAll()) {
                cache.markUnplaceableDirty(golem);
            }
        }

        decide(event, golem, key, memory, now, freeSlot);
    }

    /**
     * The same-category deposit decision, made where vanilla actually makes it.
     *
     * <p>{@link ItemTransportingEntityValidateTargetEvent} only picks *which*
     * container the golem walks to; what happens on arrival is decided by the
     * sort check, and before 26.3 no plugin could see it. So a golem allowed
     * through with a chest that merely shared a category would walk over, fail
     * to insert (a different item type cannot merge into an existing stack),
     * and walk back - indefinitely. This event was added in 26.3
     * (PaperMC/Paper#13811) and is the only place an answer can be given
     * honestly, which is why the plugin now requires 26.3.
     *
     * <p>Narrow on purpose:
     * <ul>
     *   <li>Only copper golems, only when the feature is enabled.</li>
     *   <li>Never for copper chests - those are the golem's own storage and
     *       their take-out rules live in {@link #onValidate}.</li>
     *   <li>Never when the container already holds the exact item type: the
     *       vanilla rule already accepts that, so there is nothing to add.</li>
     *   <li>Never into a full container when {@code require-empty-slot} is on,
     *       for the same reason {@code matchesSimilarItem} checks it.</li>
     * </ul>
     * Everything else is left at {@link Event.Result#DEFAULT} so vanilla -
     * and any other plugin - keeps its say.
     */
    @EventHandler
    public void onSort(ItemTransportingEntitySortEvent event) {
        if (!config.get().enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof CopperGolem)) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (held == null || held.getType() == Material.AIR) {
            return;
        }
        Inventory container = event.getContainerInventory();
        if (isCopperChest(container)) {
            return;
        }
        SimilarItems similarity = similarItems.get();
        if (similarity == null || !similarity.enabled()) {
            return;
        }

        ItemStack[] contents = container.getContents();
        Set<Material> containerTypes = observeContents(contents).allItemTypes();
        if (containerTypes.contains(held.getType())) {
            // Vanilla already accepts an exact match; leave the decision alone.
            return;
        }
        if (similarity.requireEmptySlot() && !hasEmptySlot(contents)) {
            return;
        }

        Material heldType = held.getType();
        String group = similarity.findSharedGroup(containerTypes, heldType);
        if (group == null) {
            return;
        }
        event.setResult(Event.Result.ALLOW);

        if (similarity.debug()) {
            Location loc = container.getLocation();
            plugin.getLogger().info(String.format("[similar-items] %s -> %s 命中分组“%s”(sort)",
                    heldType,
                    loc == null ? "?" : String.format("(%d,%d,%d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                    group));
        }
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
            // The golem can only carry what the first non-empty slot holds, so
            // that one stack decides whether this chest is worth a trip at all.
            // Denial stays tied to the give-up registry - i.e. to a trip that
            // actually just failed - because refusing on memory alone can
            // deadlock: once nothing is picked up, no destination is ever
            // validated again and the memory can never learn otherwise.
            UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
            Material frontType = entry.firstItemType();
            boolean blocked = isUnplaceable(unplaceable, frontType, now);
            event.setAllowed(!blocked);
            if (blocked) {
                SimilarItems readability = similarItems.get();
                if (readability != null && readability.debug()) {
                    Block head = event.getBlock();
                    plugin.getLogger().info(String.format("[skip] %s 刚失败过，本口铜箱暂不取 (%d,%d,%d)",
                            frontType, head.getX(), head.getY(), head.getZ()));
                }
            } else {
                // Remember where this carry came from: a give-up should put the
                // item back where it was, not into whichever copper chest
                // happens to be closest - that is what made one chest
                // accumulate everything the other one could not get rid of.
                session.origin = key;

                // The golem has to walk here and stop anyway, so re-order the
                // chest while it is busy: fragments get recombined and items
                // with a known category float ahead of the ones with nowhere to
                // go. That is exactly the order the next pickup wants, and it
                // is the only chance a copper chest gets to be tidied at all -
                // golems never deposit into one.
                tidy.get().schedule(event.getBlock());
            }
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
        CarrySession carried = sessions.get(golem.getUniqueId());
        for (Container target : stowCandidates(golem, carried == null ? null : carried.origin)) {
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
     * Containers to try when a golem gives up, best first: the copper chest the
     * carry came from, then the golem's other storage by distance, then
     * everything else. Putting the item back where it was picked up is what
     * keeps one chest from silently collecting every item the other one could
     * not deliver - a migration that is invisible in game and reads as "this
     * chest is abandoned so it piles up".
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
    private List<Container> stowCandidates(CopperGolem golem, ChestKey origin) {
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
        // Origin first, then the golem's own storage by distance, then anything
        // else. Handing the item back where it came from is what stops one
        // copper chest from collecting every item the other one could not
        // deliver - that migration is invisible in game, but it is exactly what
        // makes a chest look "abandoned while piling up".
        found.sort(Comparator
                .comparingInt((Container c) -> isOrigin(c, origin) ? 0 : (isCopperChest(c.getType()) ? 1 : 2))
                .thenComparingDouble(c -> c.getLocation().distanceSquared(golem.getLocation())));
        return found;
    }

    private static boolean isOrigin(Container container, ChestKey origin) {
        return origin != null
                && origin.worldId().equals(container.getWorld().getUID())
                && origin.x() == container.getX()
                && origin.y() == container.getY()
                && origin.z() == container.getZ();
    }

    private static String positionKey(Container container) {
        return container.getWorld().getName() + ':' + container.getX() + ',' + container.getY() + ',' + container.getZ();
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
    }

    /**
     * Brings the first *deliverable* stack to the front of a copper chest.
     *
     * <p>This is the whole fix for "one copper chest never gets emptied": the
     * golem can only carry the first non-empty slot, so a single item with no
     * destination blocks every sortable item behind it, and the chest looks
     * abandoned while it piles up.
     *
     * <p>The old gate was the give-up registry, which only lives for
     * {@code unplaceable-ttl-seconds} (20s by default). Between two failures
     * the item looked fine, so promotion almost never ran. Deliverability is
     * now answered from the chest memory instead - it stays true for as long as
     * no chest is known to accept the item - and the registry is kept as a
     * second, immediate signal.
     */
    private void promoteDeliverableItem(Container container, ItemStack[] contents, ChestMemory memory,
                                        ChestKey self, UnplaceableRegistry unplaceable, long now) {
        // Swapping two slots under an open window desyncs the client's view of
        // the container - the player can then pick up a stack that isn't there
        // or drop one into the wrong slot. Never reorder a container someone is
        // looking at; the chest simply keeps its old order until it is closed.
        if (!container.getInventory().getViewers().isEmpty()) {
            return;
        }
        int firstIndex = firstNonEmptyIndex(contents);
        if (firstIndex < 0) {
            return;
        }
        Material frontType = contents[firstIndex].getType();
        if (!isBlockedElsewhere(memory, self, unplaceable, frontType, now)) {
            return;
        }

        for (int i = firstIndex + 1; i < contents.length; i++) {
            ItemStack candidate = contents[i];
            if (candidate == null || candidate.getType() == Material.AIR || candidate.getType() == frontType) {
                continue;
            }
            if (isBlockedElsewhere(memory, self, unplaceable, candidate.getType(), now)) {
                continue;
            }
            ItemStack blocked = contents[firstIndex];
            container.getInventory().setItem(firstIndex, candidate);
            container.getInventory().setItem(i, blocked);
            contents[firstIndex] = candidate;
            contents[i] = blocked;

            SimilarItems readability = similarItems.get();
            if (readability != null && readability.debug()) {
                Location loc = container.getLocation();
                plugin.getLogger().info(String.format("[pickup] %s 无处可放，把 %s 顶到前排 (%d,%d,%d)",
                        frontType, candidate.getType(),
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            }
            return;
        }
    }

    private static int firstNonEmptyIndex(ItemStack[] contents) {
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].getType() != Material.AIR) {
                return i;
            }
        }
        return -1;
    }

    /**
     * "Do we know of any chest that would take this?" - the inverse of what the
     * deposit rule accepts, asked against remembered contents so it can be
     * answered before the golem walks anywhere.
     *
     * <p>Exact matches count even in a full chest (an existing stack can still
     * grow), which mirrors vanilla's own leaving-items rule.
     */
    private boolean isDeliverable(ChestMemory memory, ChestKey self, Material type) {
        SimilarItems similarity = similarItems.get();
        for (MemoryEntry entry : memory.asMap().values()) {
            if (entry.key().equals(self) || !entry.isDepositTarget()) {
                // A copper chest is the golem's own storage; it never receives a
                // deposit, and letting one vouch for the other would make every
                // item they share look deliverable forever.
                continue;
            }
            if (entry.contains(type)) {
                return true;
            }
            if (!entry.hasFreeSlot()) {
                continue;
            }
            if (entry.isEmpty()) {
                return true;
            }
            if (similarity != null && similarity.enabled()
                    && similarity.findSharedGroup(entry.allItemTypes(), type) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when this item type should not be carried: it failed recently, or
     * nothing in the known chests accepts it.
     *
     * <p>The second half only fires once the golem has actually looked at some
     * other chest - with an empty or single-entry memory "no destination known"
     * is ignorance, not evidence, and reordering on ignorance would shuffle a
     * player's chest for nothing.
     */
    private boolean isBlockedElsewhere(ChestMemory memory, ChestKey self, UnplaceableRegistry unplaceable,
                                       Material type, long now) {
        if (isDeliverable(memory, self, type)) {
            return false;
        }
        return isUnplaceable(unplaceable, type, now) || knowsAnyDepositTarget(memory, self);
    }

    /** Has the golem actually looked at a container that could receive items?
     * Only then does "no destination known" mean anything. */
    private boolean knowsAnyDepositTarget(ChestMemory memory, ChestKey self) {
        for (MemoryEntry entry : memory.asMap().values()) {
            if (!entry.key().equals(self) && entry.isDepositTarget()) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnplaceable(UnplaceableRegistry unplaceable, Material type, long now) {
        return unplaceable.get(type)
                .filter(mark -> !mark.isStale(config.get().unplaceableTtlSeconds(), now))
                .isPresent();
    }

    private boolean isCopperChest(Material material) {
        return material.name().endsWith("COPPER_CHEST");
    }

    /**
     * Overload for the sort event, which hands over an {@link Inventory}
     * rather than a block. {@code getLocation()} is null for inventories with
     * no world position (crafting views, custom holders); those are never a
     * copper chest as far as this listener is concerned.
     */
    private boolean isCopperChest(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        Location location = inventory.getLocation();
        return location != null && isCopperChest(location.getBlock().getType());
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
     * Per-golem carry state, deliberately four fields wide. What the give-up
     * watchdog needs is exactly: what is in hand, since when it has been there
     * without a single chest being evaluated, whether any chest has accepted
     * it, and which chest it was taken from. Tracking individual rejections
     * adds nothing the clock does not already cover.
     */
    private static final class CarrySession {
        private Material heldItem;
        private long heldSinceEpochMillis;
        private boolean hasValidTarget;
        /** Copper chest this carry was picked from - the give-up puts it back
         * there instead of into whichever one happens to be nearest. */
        private ChestKey origin;
    }
}
