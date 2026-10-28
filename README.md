# Copper Golem Patch

A Paper plugin that gives Copper Golems a **persistent memory** of what each chest holds, and lets them **share it with each other**, so they stop re-checking chests they already know are wrong.

> **This is a fork.** It starts from [Craftekture/Copper-Golem-Patch](https://github.com/Craftekture/Copper-Golem-Patch) 0.2.0 (MIT) and adds what a sorted storage wall actually needs: **same-category deposits** (any wool colour into a chest holding wool), **post-deposit tidying** (partial stacks merged, chest sorted by category), and a **single give-up clock** that cannot leave a golem holding an item forever. See [CHANGELOG.md](CHANGELOG.md) for the full list.

![Copper Golem Patch](Images/Copper%20Golem%20Patch.png)

## The problem

In vanilla, a copper golem re-evaluates every chest on every pass: it walks over, examines it for ~3 s, and leaves if the contents don't match. It learns nothing. Worse, if it picks up an item no chest will accept, it holds onto it indefinitely and the whole sorting operation stalls.

## What the plugin does

| Mechanism | Effect |
|---|---|
| **Per-golem memory** | Each golem remembers what it saw in each chest, persisted in its PDC (survives restarts). |
| **Gossip** | Two golems passing each other exchange memories — a golem knows about chests it never visited. |
| **Informed skipping** | A chest known not to match is rejected without the walk and the examine. |
| **Same-category deposits** | A chest holding any wool colour accepts any other wool colour, a chest of oak planks accepts spruce — one destination chest per *category* instead of one per item type. 76 groups cover 986 items out of the box. |
| **Post-deposit tidying** | 2 s after a deposit the chest is compacted: partial stacks of the same item merged into full ones, then stacks ordered by category. Chests you have open are skipped. |
| **Bounded stall breaker** | An item nothing accepts goes back to a copper chest, or a nearby chest/barrel, or is dropped at the golem's feet (with a log line). Soft deadline 30 s, hard cap 120 s. |
| **Deprioritising** | If the first slot of a copper chest holds an unplaceable item, it is swapped with the first sortable one — otherwise it would block everything behind it. |

![Memory](Images/MEMORY.png)
![Gossip](Images/GOSSIP.png)

## Compatibility

| Minecraft | Java required | Status |
|---|---|---|
| 1.21.9 | 21 | ✅ |
| 1.21.10 | 21 | ✅ |
| 1.21.11 | 21 | ✅ |
| 26.1.2 | 25 | ✅ |
| 26.2 | 25 | ✅ |
| 26.3 | 25 | ✅ (verified on a live 26.3 server) |

**1.21.9 is the absolute floor**: it is the version that introduced Copper Golems, so there is nothing to patch before it.

A **single jar** covers the whole range. It is compiled against the 1.21.9 API and emits Java 21 bytecode, which makes it loadable on both Java 21 and Java 25 servers. Every version in the matrix is recompiled and tested on every commit ([CI](../../actions)).

Requires **Paper** — the plugin depends on `ItemTransportingEntityValidateTargetEvent`, a Paper API, so Spigot and Bukkit will not work. Not Folia-compatible.

## Installation

1. Grab `copper-golem-patch-x.y.z.jar` from the [releases](../../releases).
2. Drop it into `plugins/`.
3. Restart the server. `plugins/CopperGolemPatch/config.yml` is generated with defaults that work as-is.

## Commands

`/cgp` — permission `coppergolempatch.admin` (op by default).

| Subcommand | Effect |
|---|---|
| `/cgp reload` | Reloads `config.yml` at runtime. |
| `/cgp info` | Memory state of the nearest golem (within 10 blocks): known chests, age, unplaceable items. |
| `/cgp toggle` | Flips `enabled` and saves. |

## Configuration

Every setting is documented inline in [config.yml](src/main/resources/config.yml). The main ones:

| Key | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Kill switch: `false` restores vanilla behaviour without uninstalling. |
| `gossip.enabled` | `true` | Memory sharing between golems. |
| `gossip.radius` | `12.0` | Max distance (blocks) for an exchange. |
| `gossip.interval-ticks` | `30` | Check frequency (20 ticks = 1 s). |
| `memory.max-entries-per-golem` | `32` | Chests remembered per golem. |
| `memory.entry-ttl-seconds` | `1800` | Forget a chest that is never revisited. |
| `similar-items.enabled` | `true` | Same-category deposits. `groups` holds the categories; a chest accepts an item from the same group as what it already stores. |
| `similar-items.require-empty-slot` | `true` | Only deposit into a chest that still has a free slot — two different items cannot stack, so a full chest would mean a wasted trip. |
| `tidy.enabled` | `true` | Post-deposit compaction. `merge` folds partial stacks together, `sort` orders by category. |
| `tidy.delay-ticks` | `40` | Wait before tidying, so vanilla's own insertion finishes first. |
| `tidy.skip-viewed` | `true` | Skip chests a player has open. **Do not set this to `false`** — it races with dragging items around. |
| `give-up.unplaceable-ttl-seconds` | `20` | How long an item stays marked "unplaceable" — this doubles as the retry clock, and it is what lets a stuck golem discover a chest placed after the fact. |
| `give-up.fallback-to-any-container` | `true` | When no copper chest can take the item back, try nearby chests and barrels before dropping it. |
| `give-up.hold-timeout-ticks` | `600` | Soft deadline (30 s) for a golem holding an item no chest accepted. The hard cap is 4× this. |

## Good to know

Vanilla's rule: a storage chest accepts an item only if it is **empty**, or **already contains that item type**. Free slots alone are not enough — a chest holding a single stone will not accept dirt.

This fork widens that one rule and leaves the rest alone: a chest also accepts an item that belongs to the **same category** as something it already holds (`similar-items.groups`). With `similar-items.enabled: false` you get exact upstream behaviour back — plan roughly one destination chest per item type. With it on, one chest per category is enough, which is usually four or five times fewer chests.

## Development

The default build is the floor build, and it is the distributable artifact:

```bash
./gradlew build
```

Compile and test against another version in the matrix:

```bash
./gradlew build -PmcVersion=26.2
```

Run a test server (world isolated in `run/<version>/`, never committed):

```bash
./gradlew runServer -PserverVersion=26.2
```

Check that the floor jar really does load on a recent server — the two properties are independent:

```bash
./gradlew runServer -PmcVersion=1.21.9 -PserverVersion=26.2
```

> Worlds are not backward compatible: a world opened in 26.2 will not reopen in 1.21.9. Hence one run directory per version.

### Adding a version to the matrix

One line in `supportedVersions` in [build.gradle.kts](build.gradle.kts), plus the matching entry in the matrix in [ci.yml](.github/workflows/ci.yml). If CI passes, the version is covered with no code change.

> The CI job names double as the required status check names in the branch ruleset. Renaming one silently invalidates the configured requirement — update the ruleset alongside.

### Branches

`main` is always releasable and protected. Everything goes through a short-lived branch (`feat/…`, `fix/…`) and a pull request validated by CI.

No per-Minecraft-version branches: a single source covers the whole matrix, and CI proves it on every commit. Per-version branches would duplicate the code for no benefit.

### Cutting a release

```bash
git tag v0.2.0 && git push origin v0.2.0
```

[release.yml](.github/workflows/release.yml) revalidates the full matrix, builds the floor jar, and creates the GitHub release with the jar attached.

Bump `version` in [build.gradle.kts](build.gradle.kts) **before** tagging — the tag does not set it, and the jar would otherwise ship claiming the previous version.

### End-to-end scripts

`tools/` holds the RCON-driven harness used to validate behaviour on a real server — the kind of thing a unit test cannot see:

| Script | Purpose |
|---|---|
| `tools/cgp_m.py` | Four-scenario matrix: normal deposit + tidy, total rejection, fallback to a nearby chest, nowhere to go. Asserts item conservation across the whole run. |
| `tools/cgp_e.py` / `tools/cgp_f.py` | "Unplaceable" mark lifecycle: TTL expiry, and early release when a reachable chest frees up. |
| `tools/cleanup.py`, `tools/health.py` | Wipe the test area, then verify no leftover entities, dropped items or force-loaded chunks. |
| `tools/pack.py` | Legacy repacker: rebuilds a jar from compiled classes when Gradle is not available (a bare server with only a JDK). Gradle is the normal path. |

They talk to the server over RCON and assume a scratch area far from spawn; read the constants at the top before running.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

MIT — see [LICENSE](LICENSE). Original work © Craftekture ([upstream](https://github.com/Craftekture/Copper-Golem-Patch)).
