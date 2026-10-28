package dev.cgp.coppergolempatch.similar;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * "Similar items" matching: lets a golem deposit item X into a chest that
 * already holds item Y when X and Y belong to the same configured category
 * (all colours of wool, all planks, all stairs, every sword, ...).
 *
 * <p>Vanilla only accepts a chest that is completely empty or already
 * contains the exact same item type, which is why a "wool chest" holding
 * red and white wool rejects cyan wool. This class supplies the extra
 * "same category" rule on top of that.
 *
 * <p>Categories are declared in config.yml as
 * <pre>
 * similar-items:
 *   enabled: true
 *   require-empty-slot: true
 *   groups:
 *     - name: "Wool"
 *       items: ["#minecraft:wool"]
 * </pre>
 * Entries starting with '#' are vanilla item tags resolved at runtime via the
 * item tag registry (so items added by future versions join automatically);
 * anything else is a concrete item id.
 *
 * <p>All resolution happens once at load time and is turned into a
 * {@code Material -> group ids} index, so per-decision cost is a handful of
 * small int-array comparisons. Groups are identified by int ids rather than a
 * bitmask because the configured group count can exceed 64.
 */
public final class SimilarItems {

    private static final SimilarItems DISABLED =
            new SimilarItems(false, true, false, Map.of(), new String[0]);

    private final boolean enabled;
    private final boolean requireEmptySlot;
    private final boolean debug;
    private final Map<Material, int[]> groupsByMaterial;
    private final String[] groupNames;

    private SimilarItems(boolean enabled, boolean requireEmptySlot, boolean debug,
                         Map<Material, int[]> groupsByMaterial, String[] groupNames) {
        this.enabled = enabled;
        this.requireEmptySlot = requireEmptySlot;
        this.debug = debug;
        this.groupsByMaterial = groupsByMaterial;
        this.groupNames = groupNames;
    }

    public static SimilarItems disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean requireEmptySlot() {
        return requireEmptySlot;
    }

    public boolean debug() {
        return debug;
    }

    public int groupCount() {
        return groupNames.length;
    }

    public int coveredMaterialCount() {
        return groupsByMaterial.size();
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    public static SimilarItems load(JavaPlugin plugin, Logger logger) {
        FileConfiguration config = plugin.getConfig();

        boolean enabled = config.getBoolean("similar-items.enabled", false);
        boolean requireEmptySlot = config.getBoolean("similar-items.require-empty-slot", true);
        boolean debug = config.getBoolean("similar-items.debug", false);

        if (!enabled) {
            logger.info("similar-items: disabled, golems only accept empty chests or exact item matches (vanilla rule).");
            return new SimilarItems(false, requireEmptySlot, debug, Map.of(), new String[0]);
        }

        List<Map<?, ?>> rawGroups = config.getMapList("similar-items.groups");
        if (rawGroups.isEmpty()) {
            logger.warning("similar-items.enabled is true but similar-items.groups is empty - falling back to vanilla matching.");
            return new SimilarItems(false, requireEmptySlot, debug, Map.of(), new String[0]);
        }

        Map<Material, List<Integer>> builder = new HashMap<>();
        List<String> names = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int emptyGroups = 0;

        for (Map<?, ?> raw : rawGroups) {
            Object rawName = raw.get("name");
            String name = rawName == null ? "(unnamed)" : String.valueOf(rawName);

            Object rawItems = raw.get("items");
            if (!(rawItems instanceof List<?> itemList) || itemList.isEmpty()) {
                failures.add(name + ": 没有 items 列表");
                continue;
            }

            Set<Material> resolved = new java.util.LinkedHashSet<>();
            List<String> groupFailures = new ArrayList<>();

            for (Object item : itemList) {
                String token = String.valueOf(item).trim();
                if (token.isEmpty()) {
                    continue;
                }
                if (token.startsWith("#")) {
                    resolveTag(token.substring(1), resolved, groupFailures);
                } else {
                    resolveMaterial(token, resolved, groupFailures);
                }
            }

            if (resolved.isEmpty()) {
                emptyGroups++;
                failures.add(name + ": 全部条目解析失败 -> " + groupFailures);
                continue;
            }
            if (!groupFailures.isEmpty()) {
                // Partial success is still useful, but make the gap loud.
                logger.warning("similar-items: 组 \"" + name + "\" 有 " + groupFailures.size()
                        + " 项未解析: " + groupFailures);
            }

            int id = names.size();
            names.add(name);
            for (Material material : resolved) {
                builder.computeIfAbsent(material, k -> new ArrayList<>()).add(id);
            }

            logger.info(String.format("similar-items:  #%02d %-12s -> %d 项", id, name, resolved.size()));
        }

        Map<Material, int[]> index = new HashMap<>(builder.size() * 2);
        for (Map.Entry<Material, List<Integer>> entry : builder.entrySet()) {
            index.put(entry.getKey(), entry.getValue().stream().mapToInt(Integer::intValue).toArray());
        }

        logger.info(String.format("similar-items: 已加载 %d 组 / 覆盖 %d 种物品 / 空槽要求 %s",
                names.size(), index.size(), requireEmptySlot ? "开启" : "关闭"));

        if (!failures.isEmpty()) {
            logger.warning("similar-items: 有 " + failures.size() + " 个组完全无法使用：");
            for (String failure : failures) {
                logger.warning("  - " + failure);
            }
        }
        if (emptyGroups > 0) {
            logger.warning("similar-items: " + emptyGroups + " 个组因解析失败被跳过，请检查上面的组名。");
        }

        if (names.isEmpty()) {
            logger.warning("similar-items: 没有任何可用分组 - 退回原版匹配规则。");
            return new SimilarItems(false, requireEmptySlot, debug, Map.of(), new String[0]);
        }

        return new SimilarItems(true, requireEmptySlot, debug, index, names.toArray(new String[0]));
    }

    private static void resolveTag(String rawTag, Set<Material> out, List<String> failures) {
        String path = rawTag;
        String namespace = "minecraft";
        int colon = rawTag.indexOf(':');
        if (colon >= 0) {
            namespace = rawTag.substring(0, colon);
            path = rawTag.substring(colon + 1);
        }
        if (!"minecraft".equals(namespace)) {
            failures.add("#" + rawTag + "（只支持 minecraft 命名空间）");
            return;
        }

        Tag<Material> tag;
        try {
            tag = Bukkit.getServer().getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft(path), Material.class);
        } catch (RuntimeException e) {
            failures.add("#" + rawTag + "（" + e.getClass().getSimpleName() + "）");
            return;
        }
        if (tag == null) {
            failures.add("#" + rawTag + "（tag 不存在）");
            return;
        }
        Set<Material> values = tag.getValues();
        if (values.isEmpty()) {
            failures.add("#" + rawTag + "（tag 为空）");
            return;
        }
        out.addAll(values);
    }

    private static void resolveMaterial(String token, Set<Material> out, List<String> failures) {
        Material material = Material.matchMaterial(token);
        if (material == null) {
            failures.add(token + "（物品不存在）");
            return;
        }
        if (!material.isItem()) {
            failures.add(token + "（不是物品）");
            return;
        }
        out.add(material);
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * @return the name of a category shared by {@code held} and something in
     *         {@code chestTypes}, or {@code null} if there is no overlap.
     */
    public String findSharedGroup(Set<Material> chestTypes, Material held) {
        if (!enabled || held == null || held == Material.AIR) {
            return null;
        }
        int[] heldGroups = groupsByMaterial.get(held);
        if (heldGroups == null || heldGroups.length == 0) {
            return null;
        }
        for (Material chestType : chestTypes) {
            if (chestType == null || chestType == Material.AIR || chestType == held) {
                continue;
            }
            int[] otherGroups = groupsByMaterial.get(chestType);
            if (otherGroups == null) {
                continue;
            }
            for (int heldGroup : heldGroups) {
                for (int otherGroup : otherGroups) {
                    if (heldGroup == otherGroup) {
                        return groupNames[heldGroup];
                    }
                }
            }
        }
        return null;
    }

    /** Convenience wrapper for callers that only need a boolean. */
    public boolean matches(Set<Material> chestTypes, Material held) {
        return findSharedGroup(chestTypes, held) != null;
    }

    /**
     * Lowest category index this material belongs to, in config order, used as
     * a sort key so one category lands in contiguous slots.
     *
     * @return {@link Integer#MAX_VALUE} when the material is in no category,
     *         which sorts those items after every categorised one.
     */
    public int firstGroupIndex(Material material) {
        if (!enabled || material == null || material == Material.AIR) {
            return Integer.MAX_VALUE;
        }
        int[] groups = groupsByMaterial.get(material);
        if (groups == null || groups.length == 0) {
            return Integer.MAX_VALUE;
        }
        int lowest = Integer.MAX_VALUE;
        for (int group : groups) {
            if (group < lowest) {
                lowest = group;
            }
        }
        return lowest;
    }

    /** Human-readable dump used by admin commands and debug logging. */
    public Map<String, Integer> describeGroups() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < groupNames.length; i++) {
            int n = 0;
            for (int[] ids : groupsByMaterial.values()) {
                for (int id : ids) {
                    if (id == i) {
                        n++;
                        break;
                    }
                }
            }
            counts.put(groupNames[i], n);
        }
        return counts;
    }
}
