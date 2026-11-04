package dev.cgp.coppergolemsort.command;

import dev.cgp.coppergolemsort.CopperGolemSortPlugin;
import dev.cgp.coppergolemsort.memory.ChestMemory;
import dev.cgp.coppergolemsort.memory.GolemMemoryCache;
import dev.cgp.coppergolemsort.memory.MemoryEntry;
import dev.cgp.coppergolemsort.tidy.ContainerTidy;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class CgpCommand implements BasicCommand {

    private static final String USAGE = "Usage: /cgp <reload|info|toggle|tidy [radius]>";

    private final CopperGolemSortPlugin plugin;
    private final GolemMemoryCache cache;
    private final Supplier<ContainerTidy> tidy;

    public CgpCommand(CopperGolemSortPlugin plugin, GolemMemoryCache cache, Supplier<ContainerTidy> tidy) {
        this.plugin = plugin;
        this.cache = cache;
        this.tidy = tidy;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        var sender = stack.getSender();
        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage("[Copper Golem Sort] Configuration reloaded.");
            }
            case "info" -> handleInfo(stack);
            case "toggle" -> handleToggle(stack);
            case "tidy" -> handleTidy(stack, args);
            default -> sender.sendMessage(USAGE);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "info", "toggle", "tidy");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tidy")) {
            return List.of("16", "32", "48", "64");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "coppergolemsort.admin";
    }

    private void handleInfo(CommandSourceStack stack) {
        var sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Copper Golem Sort] This command must be run by a player.");
            return;
        }
        Optional<CopperGolem> nearest = findNearestGolem(player);
        if (nearest.isEmpty()) {
            sender.sendMessage("[Copper Golem Sort] No copper golem within 10 blocks.");
            return;
        }
        ChestMemory memory = cache.getMemory(nearest.get());
        Collection<MemoryEntry> entries = memory.asMap().values();
        if (entries.isEmpty()) {
            sender.sendMessage("[Copper Golem Sort] That golem has no chest memory yet.");
            return;
        }
        long oldest = entries.stream().mapToLong(MemoryEntry::lastSeenEpochMillis).min().orElseThrow();
        long newest = entries.stream().mapToLong(MemoryEntry::lastSeenEpochMillis).max().orElseThrow();
        int unplaceableCount = cache.getUnplaceable(nearest.get()).size();
        sender.sendMessage(String.format(
                "[Copper Golem Sort] %d known chest(s) - oldest seen %s, newest seen %s - %d unplaceable item type(s)",
                entries.size(), Instant.ofEpochMilli(oldest), Instant.ofEpochMilli(newest), unplaceableCount));
    }

    private void handleToggle(CommandSourceStack stack) {
        boolean newValue = !plugin.getPluginConfig().enabled();
        plugin.getConfig().set("enabled", newValue);
        plugin.saveConfig();
        plugin.reloadPluginConfig();
        stack.getSender().sendMessage("[Copper Golem Sort] enabled = " + newValue);
    }

    /**
     * One-shot sweep for a base that is already messy.
     *
     * <p>The automatic pass only ever reaches containers a golem walks up to,
     * which by definition excludes every chest the golems never touch - and
     * those are exactly the ones that pile up. This lets an operator clean the
     * whole area once; afterwards the golems keep their own chests in shape.
     */
    private void handleTidy(CommandSourceStack stack, String[] args) {
        var sender = stack.getSender();
        ContainerTidy containerTidy = tidy.get();
        if (containerTidy == null || !containerTidy.enabled()) {
            sender.sendMessage("[Copper Golem Sort] tidy.enabled is false in config.yml - nothing to tidy.");
            return;
        }

        int radius = 32;
        if (args.length > 1) {
            try {
                radius = Integer.parseInt(args[1]);
            } catch (NumberFormatException notANumber) {
                sender.sendMessage("[Copper Golem Sort] Radius must be a number, e.g. /cgp tidy 32");
                return;
            }
        }
        radius = Math.max(1, Math.min(radius, 64));

        Location center = stack.getLocation();
        if (center == null || center.getWorld() == null) {
            sender.sendMessage("[Copper Golem Sort] Nowhere to tidy from - run this in-game or from a command block.");
            return;
        }

        sender.sendMessage("[Copper Golem Sort] Tidying containers within " + radius + " blocks...");
        containerTidy.tidyArea(center, radius, changed -> sender.sendMessage(
                "[Copper Golem Sort] Done: " + changed + " container(s) reorganised."));
    }

    private Optional<CopperGolem> findNearestGolem(Player player) {
        return player.getNearbyEntities(10, 10, 10).stream()
                .filter(CopperGolem.class::isInstance)
                .map(CopperGolem.class::cast)
                .min(Comparator.comparingDouble(g -> g.getLocation().distanceSquared(player.getLocation())));
    }
}
