# Changelog

## 0.4.2

### Fixed

- **`/cgp` gossip scan could force-load unloaded chunks.** The unplaceable revalidation loop called `getBlockAt(...).getState()` on remembered chest positions without a `isChunkLoaded` check first, and `getState()` on an unloaded chunk loads it synchronously on the main thread. A golem standing 40 blocks from its owner can legitimately remember chests outside the 96-block view distance, so this was reachable in normal play. Now filtered out, same as the deposit path.
- **Unplaceable marks were never pruned, so the scan above ran forever.** TTL was only consulted on read; the map kept the entries, and the revalidation loop's early exit was "map is empty". A golem that had given up once paid a full 32×32 scan every 1.5 s for the rest of its life. Marks are now pruned before the scan.
- **The config bundled inside the jar was stale.** `saveDefaultConfig` wrote a template that still contained `give-up.after-rejections` (a key 0.4.0 stopped reading) and lacked `fallback-to-any-container` and `hold-timeout-ticks`. Fresh installs got a config that described a plugin which no longer existed.

### Added

- Minecraft **26.3** in the compatibility matrix.

## 0.4.0

The give-up logic had two independent judges: the upstream event-driven rejection counter, and a watchdog added in 0.3.7. They cleared each other's state and suppressed each other's timers. A missing judge fails loudly; two disagreeing judges fail silently, which is exactly what made the bug pattern "sometimes it works".

### Changed

- **One clock instead of two.** Deleted the rejection ledger (`after-rejections`, the rejection set, both timestamps, the scheduled flag) and kept a single time-based rule:
  - **soft** — 30 s (`give-up.hold-timeout-ticks`), only while no chest has ever accepted the item;
  - **hard** — 4× the soft value, unconditional, which catches "a chest accepted it but the golem cannot get there" (blocked path, chest removed, out of reach).
- The watchdog now polls the golem's main hand directly. The event it used to rely on **does not fire when there is no candidate chest at all**, so the old code was blind in precisely the case it existed to handle.
- The deadline is set at pickup and **never refreshed**. Refreshing it on every evaluation made a golem that kept retrying one full chest immortal.
- `Inventory#addItem`'s return value is the leftover; the separate `canAccept()` pre-check was redundant and is gone.

### Added

- Three-step fallback when nothing accepts the item: copper chest → nearby chest/barrel (`give-up.fallback-to-any-container`) → dropped at the golem's feet with a log line.

## 0.3.1

### Added

- **Post-deposit tidying** (`tidy`). Two seconds after a golem deposits into a chest: partial stacks of the same item are merged into full ones, then the stacks are ordered by category. Chests with a player viewing them are skipped. Manual chests are never touched — only ones a golem just deposited into.

## 0.3.0

### Added

- **Same-category deposits** (`similar-items`). Vanilla only accepts into a chest that is empty or already contains the exact same item type, which makes categorised storage useless: a chest of red wool refuses cyan wool. With this on, any item from the same group is accepted — 76 groups covering 986 items ship as defaults. `require-empty-slot: true` keeps this from walking to a chest that has nowhere to put the item.

### Fixed

- Deposit search no longer touches unloaded chunks: `isChunkLoaded` is checked before any block state is read.

## 0.2.0

First public release.

### Compatibility

- Supports Minecraft **1.21.9 through 26.2** on Paper (previously 26.1.2 only).
- **One jar covers the whole range.** It is built against the 1.21.9 API and targets Java 21 bytecode, so the same file loads on Java 21 servers (1.21.9–1.21.11) and Java 25 servers (26.x) alike.
- 1.21.9 is the floor by definition — it is the version that introduced Copper Golems.
- All five supported versions are compiled and unit-tested on every commit.

### Fixed

- **Golems could stand idle for up to 5 minutes, ignoring empty chests placed right next to them.**

  When a golem gave up on an item that no chest would accept, it marked that item type unsortable for 5 minutes and refused to pick anything up until the mark expired. An empty-handed golem is only ever offered copper chests to look at, so it could never notice a storage chest you added in the meantime — and noticing one was the only thing that would have cleared the mark. Adding chests to unblock a stalled setup simply did nothing.

  The retry window is now 20 seconds: a stuck golem retries quickly, discovers newly placed chests, and resumes sorting.

### Changed

- `give-up.unplaceable-ttl-seconds` default: `300` → `20`. This value doubles as the retry interval, which is why it has to stay short. Existing `config.yml` files are never overwritten on update — if you ran an earlier build, change this value by hand to get the fix.

### Notes

- **Paper only.** Will not load on Spigot or Bukkit (it uses a Paper-specific API), and is not Folia-compatible.
- A storage chest accepts an item only if it is **empty**, or **already contains that item type**. Free slots alone are not enough — a chest holding a single stone will not accept dirt. This is vanilla Minecraft's own rule, not something this plugin adds, so plan roughly one destination chest per item type you want sorted. With fewer chests than item types, the leftovers stay in the copper chests.

## 0.1.0

Internal build, never released publicly. Supported Minecraft 26.1.2 only.
