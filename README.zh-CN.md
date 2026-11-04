# Copper Golem Sort

[English](README.md) | **简体中文** | [![Release](https://img.shields.io/github/v/release/rayadesune/copper-golem-sort)](https://github.com/rayadesune/copper-golem-sort/releases)

一个让铜傀儡**记住每个箱子装什么**、**互相共享这份记忆**、并且**按同类物品投放 + 投放后自动整理**的 Paper 插件。

> **本仓库是独立维护的分支。** 起点是 [Craftekture/Copper-Golem-Patch](https://github.com/Craftekture/Copper-Golem-Patch) 0.2.0（MIT）。上游自 2026-08-04 之后没有更新，所以这个仓库现在是活跃主线：修 bug、加功能、跟进新版本，都在这里。原始设计归 Craftekture。

![Copper Golem Sort](Images/copper-golem-sort.png)

## 原版的问题

原版铜傀儡每一趟都会重新评估每一个箱子：走过去、看 3 秒、发现不匹配就离开。它什么都没学到。更糟的是，**一旦它拿起一件所有箱子都不接受的物品，就会永远抱着不放手**，整个分类作业就此卡死。

## 插件做了什么

| 机制 | 效果 |
|---|---|
| **单傀儡记忆** | 每个傀儡记住它在每个箱子里见过什么，存在实体 PDC 里，重启后仍在。 |
| **记忆共享（gossip）** | 两个傀儡靠近时互相交换记忆 —— 一个傀儡会知道它从没去过的箱子。 |
| **跳过已知无效目标** | 记忆里明确不匹配的箱子直接拒绝，不再走过去白看一遍。 |
| **同类投放** | 装着任意颜色羊毛的箱子，可以接受任何颜色的羊毛；橡木板的箱子可以接受云杉木板。**一个类目一个箱子**，而不是一种物品一个箱子。内置 76 组，覆盖 986 种物品。 |
| **投放后整理** | 投放 2 秒后整理该箱子：先把同物品的零散堆合并成满堆，再按类目排序。玩家正在打开的箱子会跳过。 |
| **有上限的卡死恢复** | 没有任何箱子接受的物品，会依次尝试：放回铜箱 → 附近箱子/木桶 → 掉在傀儡脚下（并打日志）。软超时 30 秒，硬上限 120 秒。 |
| **堵路物品降级** | 铜箱只会被拿走第一个非空槽，所以一件没去处的物品会挡住后面所有东西。现在它会被换到后面去，把**有去处**的物品顶到前排 —— 判断依据是记忆里的箱子内容，而不是 20 秒就过期的失败标记。整箱都没去处的箱子直接拒绝，不再让傀儡白跑一趟。 |
| **退货回原箱** | 放弃投放的物品会还回它被取出的那口铜箱，而不是就近乱塞 —— 否则一口箱子会悄悄收下另一口放不下的所有东西。 |

![Memory](Images/MEMORY.png)
![Gossip](Images/GOSSIP.png)

## 兼容性

| Minecraft | 所需 Java | 状态 |
|---|---|---|
| 26.3 | 25 | ✅（已在 26.3 实机服务器验证） |

从 0.6.0 起**只支持 26.3 及以上**。下限上移的原因不是图省事：同类物品投放必须依赖 `ItemTransportingEntitySortEvent` —— 真正决定"手里这件东西属不属于这个箱子"的事件，它是 26.3 才加入的（[PaperMC/Paper#13811](https://github.com/PaperMC/Paper/pull/13811)）。在它之前，插件只能影响傀儡"选哪个箱子"，于是会出现手持木楼梯走过去、放不进去、再走回来的循环。

jar 以 26.3 的 API 编译、输出 Java 25 字节码。每次提交都会对受支持版本重新编译 + 跑单测（见 [CI](../../actions)）。

只支持 **Paper** —— 依赖 Paper 专有事件 `ItemTransportingEntitySortEvent` / `ItemTransportingEntityValidateTargetEvent`，Spigot / Bukkit 不可用。不支持 Folia。

## 安装

1. 从 [Releases](../../releases) 下载 `copper-golem-sort-x.y.z.jar`。
2. 丢进 `plugins/`。
3. 重启服务器。会自动生成 `plugins/CopperGolemSort/config.yml`，默认值即可直接用。

## 命令

`/cgp` —— 权限 `coppergolemsort.admin`（默认仅 OP）。

| 子命令 | 作用 |
|---|---|
| `/cgp reload` | 运行时重载 `config.yml`。 |
| `/cgp info` | 查看最近一个傀儡（10 格内）的记忆状态：已知箱子数、时间、无处可放的物品。 |
| `/cgp toggle` | 反转 `enabled` 并保存。 |
| `/cgp tidy [半径]` | 把半径内（默认 32，上限 64）所有容器合并碎片并按分组重排。只处理已加载区块，分 tick 执行，有人打开的箱子跳过。 |

## 配置

每个配置项都在 [config.yml](src/main/resources/config.yml) 里带注释说明。主要几项：

| 键 | 默认 | 作用 |
|---|---|---|
| `enabled` | `true` | 总开关：`false` 即恢复原版行为，不用卸载插件。 |
| `gossip.enabled` | `true` | 傀儡之间共享记忆。 |
| `gossip.radius` | `12.0` | 交换记忆的最大距离（格）。 |
| `gossip.interval-ticks` | `30` | 检查频率（20 tick = 1 秒）。 |
| `memory.max-entries-per-golem` | `32` | 每个傀儡记忆的箱子数上限。 |
| `memory.entry-ttl-seconds` | `1800` | 多久没再访问就忘掉这个箱子。 |
| `similar-items.enabled` | `true` | 同类投放开关。`groups` 是类目定义：箱子接受与它已有内容同组的物品。 |
| `similar-items.require-empty-slot` | `true` | 只投进还有空槽的箱子 —— 不同物品无法叠加，箱子满了那一趟就是白跑。 |
| `tidy.enabled` | `true` | 投放后整理。`merge` 合并零散堆，`sort` 按类目排序。 |
| `tidy.delay-ticks` | `40` | 整理前等待，让原版自己的插入先完成。 |
| `tidy.skip-viewed` | `true` | 跳过玩家打开的箱子。**不要设为 `false`** —— 会和拖拽操作打架。 |
| `give-up.unplaceable-ttl-seconds` | `20` | 「无处可放」标记保留多久 —— 它同时是重试时钟，也是傀儡能发现「你后来新放了个箱子」的唯一途径。 |
| `give-up.fallback-to-any-container` | `true` | 铜箱放不回去时，先尝试附近箱子/木桶，再考虑掉落。 |
| `give-up.hold-timeout-ticks` | `600` | 拿着无人接受的物品时的软超时（30 秒）。硬上限是它的 4 倍。 |

## 要知道的几件事

原版规则：一个箱子接受某物品，只有当它**是空的**，或**已经装着同一种物品**。光有空槽不够 —— 装了一块石头的箱子不会接受泥土。

这个插件只放宽这一条，其余不动：箱子也接受与它已有内容**属于同一类目**的物品（`similar-items.groups`）。把 `similar-items.enabled` 设为 `false` 就回到上游的原始行为，这时大致需要「一种物品一个目标箱」。开启后，一个类目一个箱子就够，通常能少四五倍的箱子。

## 从 Copper Golem Patch 升级

0.5.0 起插件改名，这是**破坏性变更**，涉及三处标识：

| 内容 | 旧 | 新 |
|---|---|---|
| 插件名 / 数据目录 | `CopperGolemPatch` | `CopperGolemSort` |
| 权限节点 | `coppergolempatch.admin` | `coppergolemsort.admin`（旧节点仍声明为别名，权限组配置不会失效） |
| PDC 键命名空间 | `coppergolempatch` | `coppergolemsort` |

升级步骤：

```bash
# 1. 停服
# 2. 删掉旧 jar
rm plugins/copper-golem-patch-*.jar
# 3. 迁移配置（保留你改过的值）
mkdir -p plugins/CopperGolemSort
mv plugins/CopperGolemPatch/config.yml plugins/CopperGolemSort/config.yml
# 4. 放入新 jar，启动
```

**傀儡记忆会重置**：旧命名空间下的 PDC 数据不会被读取，傀儡会当作全新的重新学习。**不会丢任何物品** —— 记忆只是缓存，最坏情况是头几分钟多走几趟。

## 开发

默认构建就是 floor 构建，也是分发的那个产物：

```bash
./gradlew build
```

针对矩阵中其它版本编译并测试（矩阵里重新有第二个条目之后）：

```bash
./gradlew build -PmcVersion=26.3
```

启动测试服（世界目录隔离在 `run/<version>/`，永不提交）：

```bash
./gradlew runServer -PserverVersion=26.3
```

> 世界不向后兼容：新版本打开过的世界无法在旧版本重新打开，所以每个版本一个 run 目录。

### 把新版本加进矩阵

在 [build.gradle.kts](build.gradle.kts) 的 `supportedVersions` 加一行，再在 [ci.yml](.github/workflows/ci.yml) 的矩阵里加对应项。CI 通过即代表该版本已覆盖，不用改代码。

> CI 的 job 名同时是分支保护规则里的必需检查名。改名会让规则静默失效，要同步更新。

### 分支

`main` 始终可发布且受保护。所有改动走短生命周期分支（`feat/…`、`fix/…`）+ PR，由 CI 验证。

不做按 Minecraft 版本分叉：单一源码覆盖整个矩阵，CI 每次提交都证明这一点。

### 发版

```bash
git tag v0.5.0 && git push origin v0.5.0
```

[release.yml](.github/workflows/release.yml) 会重新验证全矩阵、构建 floor jar 并创建 GitHub Release。

发版前先在 [build.gradle.kts](build.gradle.kts) 里改 `version` —— tag 不会设置它，否则 jar 会带着上一个版本号发出去。

### 端到端测试脚本

`tools/` 里是一套基于 RCON 的验证工具，用来测单元测试看不见的行为：

| 脚本 | 用途 |
|---|---|
| `tools/cgp_m.py` | 四场景矩阵：正常投放+整理、全部拒收、落到附近箱子、无处可放。全程断言物品守恒。 |
| `tools/cgp_e.py` / `tools/cgp_f.py` | 「无处可放」标记生命周期：TTL 过期、以及可达箱子腾空后的提前解锁。 |
| `tools/cleanup.py`、`tools/health.py` | 清理测试场地，再检查无残留实体、掉落物、强制加载区块。 |
| `tools/pack.py` | 备用打包器：没有 Gradle、只有 JDK 的裸服务器上，从编译好的 class 重新打 jar。正常路径是 Gradle。 |

它们通过 RCON 连接服务器，并假定测试场地远离出生点；运行前先看脚本顶部的常量。

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md)。

## 许可

MIT —— 见 [LICENSE](LICENSE)。原始作品 © Craftekture（[upstream](https://github.com/Craftekture/Copper-Golem-Patch)），后续修改 © rayadesune。
