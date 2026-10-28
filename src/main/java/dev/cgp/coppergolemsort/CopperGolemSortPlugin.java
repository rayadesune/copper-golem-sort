package dev.cgp.coppergolemsort;

import dev.cgp.coppergolemsort.command.CgpCommand;
import dev.cgp.coppergolemsort.config.PluginConfig;
import dev.cgp.coppergolemsort.gossip.GossipTask;
import dev.cgp.coppergolemsort.listener.TargetValidationListener;
import dev.cgp.coppergolemsort.memory.ChestMemoryCodec;
import dev.cgp.coppergolemsort.memory.GolemMemoryCache;
import dev.cgp.coppergolemsort.similar.SimilarItems;
import dev.cgp.coppergolemsort.tidy.ContainerTidy;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

public final class CopperGolemSortPlugin extends JavaPlugin {

    private PluginConfig config;
    private SimilarItems similarItems = SimilarItems.disabled();
    private ContainerTidy tidy = ContainerTidy.disabled();
    private GolemMemoryCache cache;
    private BukkitTask gossipTask;
    private BukkitTask holdWatchdogTask;
    private TargetValidationListener targetListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = PluginConfig.load(this);
        similarItems = SimilarItems.load(this, getLogger());
        tidy = ContainerTidy.load(this, () -> similarItems);
        ChestMemoryCodec codec = new ChestMemoryCodec(this, getLogger());
        cache = new GolemMemoryCache(codec);
        getServer().getPluginManager().registerEvents(cache, this);

        targetListener = new TargetValidationListener(this, () -> config, () -> similarItems, () -> tidy, cache);
        getServer().getPluginManager().registerEvents(targetListener, this);

        // Once a second: ends a carry whose search dried up before any further
        // validation event could trigger the normal give-up path.
        holdWatchdogTask = getServer().getScheduler()
                .runTaskTimer(this, targetListener::watchHoldTimeout, 20L, 20L);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register("cgp", "Copper Golem Sort admin commands", new CgpCommand(this, cache));
        });

        scheduleGossipTask();

        getLogger().info("Copper Golem Sort enabled.");
    }

    @Override
    public void onDisable() {
        if (gossipTask != null) {
            gossipTask.cancel();
        }
        if (holdWatchdogTask != null) {
            holdWatchdogTask.cancel();
        }
        if (tidy != null) {
            tidy.shutdown();
        }
        if (cache != null) {
            List<CopperGolem> loaded = new ArrayList<>();
            for (World world : getServer().getWorlds()) {
                loaded.addAll(world.getEntitiesByClass(CopperGolem.class));
            }
            cache.flush(loaded);
        }
        getLogger().info("Copper Golem Sort disabled.");
    }

    public PluginConfig getPluginConfig() {
        return config;
    }

    public SimilarItems getSimilarItems() {
        return similarItems;
    }

    public void reloadPluginConfig() {
        config = PluginConfig.load(this);
        similarItems = SimilarItems.load(this, getLogger());
        if (tidy != null) {
            tidy.shutdown();
        }
        tidy = ContainerTidy.load(this, () -> similarItems);
        if (gossipTask != null) {
            gossipTask.cancel();
        }
        scheduleGossipTask();
    }

    private void scheduleGossipTask() {
        GossipTask task = new GossipTask(() -> config, cache);
        gossipTask = getServer().getScheduler().runTaskTimer(this, task, 0L, config.gossipIntervalTicks());
    }
}
