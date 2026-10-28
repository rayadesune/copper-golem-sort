# Copper Golem Sort

Copper Golems are supposed to sort your storage. In vanilla they don't: every trip they re-walk to every chest, examine it for three seconds, and leave if the contents don't match — and if nothing accepts the item they are carrying, they hold it **forever** and the whole operation stalls.

This plugin fixes that. It is a Paper plugin, server-side only, no client mod needed.

## What it does

| | |
|---|---|
| **Persistent memory** | Each golem remembers what it saw in each chest, stored in its own PDC — survives restarts and chunk unloads. |
| **Shared knowledge** | Two golems passing within 12 blocks exchange memories, so one golem knows about chests it never visited. Newer knowledge overwrites older. |
| **No wasted trips** | A chest known not to match is rejected without walking over and examining it. |
| **Same-category deposits** | A chest holding any wool colour accepts any other wool colour; a chest of oak planks accepts spruce. One destination chest per *category* instead of one per item type — 76 groups covering 986 items out of the box. |
| **Post-deposit tidying** | Two seconds after a deposit the chest is compacted: partial stacks of the same item merged into full ones, then stacks ordered by category. Chests a player has open are skipped. |
| **Bounded stall breaker** | An item nothing accepts goes back to a copper chest, or to a nearby chest/barrel, or is dropped at the golem's feet with a log line. Soft deadline 30 s, hard cap 120 s — a golem can no longer hold an item forever. |

## Compatibility

| Minecraft | Java |
|---|---|
| 1.21.9 · 1.21.10 · 1.21.11 | 21 |
| 26.1.2 · 26.2 · 26.3 | 25 |

One jar covers the whole range: compiled against the 1.21.9 API, Java 21 bytecode. **Paper only** — the plugin hooks `ItemTransportingEntityValidateTargetEvent`, so Spigot and Bukkit will not load it. Not Folia-compatible.

## Installation

1. Drop the jar into `plugins/`.
2. Restart. `plugins/CopperGolemSort/config.yml` is generated with working defaults.
3. Build a storage wall. One chest per *category* is enough.

## Commands

`/cgp` — permission `coppergolemsort.admin` (op by default).

- `/cgp reload` — reload the configuration
- `/cgp info` — memory state of the nearest golem
- `/cgp toggle` — flip the master switch

## Upgrading from Copper Golem Patch

0.5.0 renamed the plugin. The data folder, the permission node and the PDC namespace all changed:

```bash
rm plugins/copper-golem-patch-*.jar
mkdir -p plugins/CopperGolemSort
mv plugins/CopperGolemPatch/config.yml plugins/CopperGolemSort/config.yml
```

Golem memory resets once (old PDC keys are ignored, not deleted). **No items are lost** — memory is only a cache.

## Notes

Vanilla accepts an item only into an empty chest or one already holding that exact type. This plugin widens that single rule to "same category" and leaves everything else alone. Set `similar-items.enabled: false` for upstream behaviour.

Every setting is documented inline in `config.yml`. The ones people actually touch: `similar-items.require-empty-slot` (keep it on — two different items cannot stack, so a full chest means a wasted trip) and `tidy.skip-viewed` (keep it on — otherwise it races with dragging items).

## License

MIT. Original design and code by [Craftekture](https://github.com/Craftekture/Copper-Golem-Patch); maintained since 0.3.0 by rayadesune. Upstream has not been updated since 2026-08-04.
