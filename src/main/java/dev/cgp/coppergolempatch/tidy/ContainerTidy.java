package dev.cgp.coppergolempatch.tidy;

import dev.cgp.coppergolempatch.similar.SimilarItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Merges partial stacks inside a container a golem has just deposited into,
 * then optionally groups the contents by category.
 *
 * <p>Vanilla moves at most 16 items per trip and drops them into the
 * frontmost usable slot, so four trips of 64 cobblestone produce four slots
 * of 16 instead of one slot of 64. Paper's events expose no slot control, so
 * the only place this can be corrected is after the fact: let vanilla deposit,
 * then consolidate.
 *
 * <p>Deliberately conservative: only containers a golem actually targeted are
 * ever touched, and a container someone is currently viewing is skipped so a
 * live drag cannot be raced.
 */
public final class ContainerTidy {

    private static final ContainerTidy DISABLED =
            new ContainerTidy(null, null, null, false, false, false, 40, true, false);

    private final Plugin plugin;
    private final Supplier<SimilarItems> similarItems;
    private final Logger logger;
    private final boolean merge;
    private final boolean sort;
    private final int delayTicks;
    private final boolean skipViewed;
    private final boolean debug;

    /** Container key -> epoch millis until which a repeat request is ignored. */
    private final Map<String, Long> scheduled = new HashMap<>();

    private volatile boolean enabled;

    private ContainerTidy(Plugin plugin, Supplier<SimilarItems> similarItems, Logger logger,
                          boolean enabled, boolean merge, boolean sort, int delayTicks,
                          boolean skipViewed, boolean debug) {
        this.plugin = plugin;
        this.similarItems = similarItems;
        this.logger = logger;
        this.enabled = enabled;
        this.merge = merge;
        this.sort = sort;
        this.delayTicks = delayTicks;
        this.skipViewed = skipViewed;
        this.debug = debug;
    }

    public static ContainerTidy disabled() {
        return DISABLED;
    }

    public static ContainerTidy load(Plugin plugin, Supplier<SimilarItems> similarItems) {
        FileConfiguration config = plugin.getConfig();
        boolean enabled = config.getBoolean("tidy.enabled", true);
        boolean merge = config.getBoolean("tidy.merge", true);
        boolean sort = config.getBoolean("tidy.sort", true);
        int delayTicks = Math.max(1, config.getInt("tidy.delay-ticks", 40));
        boolean skipViewed = config.getBoolean("tidy.skip-viewed", true);
        boolean debug = config.getBoolean("tidy.debug", false);

        ContainerTidy tidy = new ContainerTidy(plugin, similarItems, plugin.getLogger(),
                enabled, merge, sort, delayTicks, skipViewed, debug);

        if (enabled) {
            plugin.getLogger().info("tidy: 已启用 / 合并 " + (merge ? "开" : "关")
                    + " / 分类排序 " + (sort ? "开" : "关")
                    + " / 延迟 " + delayTicks + " tick"
                    + (skipViewed ? " / 跳过有人查看的箱子" : ""));
        }
        return tidy;
    }

    /** Stops a superseded instance from acting after a reload. */
    public void shutdown() {
        enabled = false;
        scheduled.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Queues a tidy pass for a container the golem just decided to deposit
     * into. Repeated calls for the same block within the delay window collapse
     * into one task, which matters because a golem re-validates a chest on
     * every pass.
     */
    public void schedule(Block block) {
        if (!enabled || plugin == null || block == null) {
            return;
        }
        if (!isLoaded(block)) {
            return;
        }
        String key = keyOf(block);
        long now = System.currentTimeMillis();
        Long until = scheduled.get(key);
        if (until != null && until > now) {
            return;
        }
        pruneExpired(now);
        scheduled.put(key, now + delayTicks * 50L + 1000L);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                tidy(block);
            } catch (Throwable failure) {
                if (logger != null) {
                    logger.warning("tidy failed at " + key + ": " + failure);
                }
            }
        }, delayTicks);
    }

    private void tidy(Block block) {
        if (!enabled || !isLoaded(block)) {
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            return;
        }
        Inventory inventory = container.getInventory();
        if (skipViewed && !inventory.getViewers().isEmpty()) {
            note("跳过 " + keyOf(block) + "：有人正在查看");
            return;
        }

        ItemStack[] before = inventory.getContents();
        int size = inventory.getSize();
        List<ItemStack> items = new ArrayList<>(size);
        for (ItemStack stack : before) {
            if (isEmpty(stack)) {
                continue;
            }
            items.add(stack.clone());
        }
        if (items.size() < 2) {
            return;
        }

        List<ItemStack> next = merge ? merged(items) : items;
        if (sort) {
            next.sort(orderByCategory());
        }

        int changed = 0;
        for (int slot = 0; slot < size; slot++) {
            ItemStack target = slot < next.size() ? next.get(slot) : null;
            if (!sameStack(before[slot], target)) {
                inventory.setItem(slot, target);
                changed++;
            }
        }

        int freed = items.size() - next.size();
        if (debug && (changed > 0 || freed > 0)) {
            note("整理 " + keyOf(block) + "：" + items.size() + " 堆 → " + next.size()
                    + " 堆（腾出 " + freed + " 格），写入 " + changed + " 格");
        }
    }

    /**
     * Fills existing stacks before opening new ones, so 4x16 becomes 1x64.
     * {@code isSimilar} is what keeps two differently-enchanted pickaxes from
     * being treated as the same item.
     */
    private List<ItemStack> merged(List<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            // Non-stackables never merge, and this is also the safety gate that
            // keeps anything carrying hidden payload (shulker boxes, bundles,
            // written books, filled maps) out of the merge path entirely: the
            // merge relies on isSimilar, which compares ItemMeta, and an item
            // whose contents live in a data component is one metadata-equality
            // regression away from having its contents silently overwritten.
            // Refusing to touch them costs nothing - they cannot be merged.
            if (item.getMaxStackSize() <= 1) {
                result.add(item.clone());
                continue;
            }
            ItemStack remaining = item.clone();
            int max = Math.max(1, remaining.getMaxStackSize());
            for (ItemStack target : result) {
                if (remaining.getAmount() <= 0) {
                    break;
                }
                if (!target.isSimilar(remaining)) {
                    continue;
                }
                int space = max - target.getAmount();
                if (space <= 0) {
                    continue;
                }
                int moved = Math.min(space, remaining.getAmount());
                target.setAmount(target.getAmount() + moved);
                remaining.setAmount(remaining.getAmount() - moved);
            }
            if (remaining.getAmount() > 0) {
                result.add(remaining);
            }
        }
        return result;
    }

    /** Category first (config order), then material name, then fullest first. */
    private Comparator<ItemStack> orderByCategory() {
        SimilarItems similar = similarItems == null ? null : similarItems.get();
        return (left, right) -> {
            int leftGroup = similar == null ? Integer.MAX_VALUE : similar.firstGroupIndex(left.getType());
            int rightGroup = similar == null ? Integer.MAX_VALUE : similar.firstGroupIndex(right.getType());
            if (leftGroup != rightGroup) {
                return Integer.compare(leftGroup, rightGroup);
            }
            int byName = left.getType().name().compareTo(right.getType().name());
            if (byName != 0) {
                return byName;
            }
            return Integer.compare(right.getAmount(), left.getAmount());
        };
    }

    private void pruneExpired(long now) {
        if (scheduled.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, Long>> iterator = scheduled.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    private static boolean isLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private static String keyOf(Block block) {
        return block.getWorld().getName() + ':' + block.getX() + ',' + block.getY() + ',' + block.getZ();
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        boolean leftEmpty = isEmpty(left);
        boolean rightEmpty = isEmpty(right);
        if (leftEmpty || rightEmpty) {
            return leftEmpty && rightEmpty;
        }
        return left.equals(right);
    }

    private void note(String message) {
        if (logger != null) {
            logger.info(message);
        }
    }
}
