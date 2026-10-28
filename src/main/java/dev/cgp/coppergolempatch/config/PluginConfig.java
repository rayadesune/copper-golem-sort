package dev.cgp.coppergolempatch.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record PluginConfig(
        boolean enabled,
        boolean gossipEnabled,
        double gossipRadius,
        long gossipIntervalTicks,
        int maxEntriesPerGolem,
        long entryTtlSeconds,
        long unplaceableTtlSeconds,
        boolean giveUpFallbackToAnyContainer,
        long giveUpHoldTimeoutTicks
) {

    public static PluginConfig load(JavaPlugin plugin) {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        double gossipRadius = config.getDouble("gossip.radius", 12.0);
        if (gossipRadius <= 0) {
            plugin.getLogger().warning("gossip.radius must be > 0, using default 12.0");
            gossipRadius = 12.0;
        }

        long gossipIntervalTicks = config.getLong("gossip.interval-ticks", 30);
        if (gossipIntervalTicks <= 0) {
            plugin.getLogger().warning("gossip.interval-ticks must be > 0, using default 30");
            gossipIntervalTicks = 30;
        }

        int maxEntriesPerGolem = config.getInt("memory.max-entries-per-golem", 32);
        if (maxEntriesPerGolem <= 0) {
            plugin.getLogger().warning("memory.max-entries-per-golem must be > 0, using default 32");
            maxEntriesPerGolem = 32;
        }

        long entryTtlSeconds = config.getLong("memory.entry-ttl-seconds", 1800);
        if (entryTtlSeconds <= 0) {
            plugin.getLogger().warning("memory.entry-ttl-seconds must be > 0, using default 1800");
            entryTtlSeconds = 1800;
        }

        // This doubles as the retry clock, so it has to stay short. A golem
        // that has given up on every type it knows stops picking anything up;
        // with nothing in hand vanilla only ever offers it copper chests, so
        // it never runs a deposit search, never observes a newly placed empty
        // chest, and never reaches the code that clears these marks. Mark
        // expiry is the only way out of that cycle - at 300s it left golems
        // idle for five minutes, ignoring chests placed right next to them.
        long unplaceableTtlSeconds = config.getLong("give-up.unplaceable-ttl-seconds", 20);
        if (unplaceableTtlSeconds <= 0) {
            plugin.getLogger().warning("give-up.unplaceable-ttl-seconds must be > 0, using default 20");
            unplaceableTtlSeconds = 20;
        }

        // The soft stuck-carry deadline, in ticks. Measured from the moment the
        // hand picked the item up and never refreshed afterwards - refreshing it
        // on every evaluation is what used to make a golem that kept re-checking
        // one rejected chest hold the item forever.
        //
        // It only applies while no chest has accepted this carry. As soon as one
        // does, the deadline becomes this value x4 (the hard cap in
        // watchHoldTimeout), which covers "chest accepted but unreachable".
        // 600 ticks = 30s, comfortably longer than the trip to any chest within
        // vanilla's 65-block search radius; the hard cap is then 120s.
        long giveUpHoldTimeoutTicks = config.getLong("give-up.hold-timeout-ticks", 600);
        if (giveUpHoldTimeoutTicks < 40) {
            plugin.getLogger().warning("give-up.hold-timeout-ticks must be >= 40, using default 600");
            giveUpHoldTimeoutTicks = 600;
        }

        return new PluginConfig(
                config.getBoolean("enabled", true),
                config.getBoolean("gossip.enabled", true),
                gossipRadius,
                gossipIntervalTicks,
                maxEntriesPerGolem,
                entryTtlSeconds,
                unplaceableTtlSeconds,
                config.getBoolean("give-up.fallback-to-any-container", true),
                giveUpHoldTimeoutTicks
        );
    }
}
