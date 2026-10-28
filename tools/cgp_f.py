"""场景 F：0.4.2 加的 isChunkLoaded 过滤不能把"发现有人腾空箱子"这条路堵死。

校验的是 hasReachableSpotFor 仍然生效，而且是在 TTL(20s) 之前就把标记清掉 ——
清理由 gossip 扫到"可达且能装的箱子"触发，和 E 场景的 TTL 到期清理是两条不同的路径。
"""
import sys, time, re
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])

r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")
X, Y, Z = 5000, 100, 5000
COPPER = f"{X} {Y+1} {Z}"
TARGET = f"{X+3} {Y+1} {Z}"
SEL = f"x={X},y={Y+1},z={Z},distance=..40"
GOLEM = f"@e[type=copper_golem,{SEL},limit=1]"
PDC_PATH = 'BukkitValues."coppergolemsort:unplaceable_items"'


def hand():
    return bool(re.search(r'count: (\d+)', r.cmd(f"data get entity {GOLEM} equipment")))


def ground(item):
    out = r.cmd(f"execute as @e[type=item,{SEL}] run data get entity @s Item")
    return sum(int(m.group(2)) for m in re.finditer(r'id: "minecraft:(\w+)", count: (\d+)', out)
               if m.group(1) == item)


def fill(pos, i, item, n):
    r.cmd(f"item replace block {pos} container.{i} with minecraft:{item} {n}")


def marks():
    out = r.cmd(f"data get entity {GOLEM} {PDC_PATH}")
    return out.count("marked_at")


print("=== 场景 F：可达空箱能否在 TTL 之前解除标记 ===")
r.cmd(f"forceload add {X-10} {Z-10} {X+10} {Z+10}")
r.cmd(f"fill {X-4} {Y} {Z-4} {X+8} {Y} {Z+4} minecraft:stone")
r.cmd(f"kill @e[type=copper_golem,{SEL}]")
r.cmd(f"kill @e[type=item,{SEL}]")
r.cmd(f"fill {X-4} {Y+1} {Z-4} {X+8} {Y+12} {Z+4} minecraft:air")
for p in (COPPER, TARGET):
    r.cmd(f"setblock {p} minecraft:chest")
r.cmd(f"setblock {COPPER} minecraft:copper_chest")
fill(COPPER, 0, "cobblestone", 16)
for i in range(1, 27):
    fill(COPPER, i, "dirt", 64)
for i in range(27):
    fill(TARGET, i, "dirt", 64)
print("起始: 铜箱 slot0=16 圆石 + 26 格土，目标箱整箱土（不可装）")
r.cmd(f"summon minecraft:copper_golem {X+1} {Y+1.5} {Z}")

t0 = time.time()
while time.time() - t0 < 40:
    time.sleep(0.3)
    if hand():
        r.cmd(f"item replace block {COPPER} container.0 with minecraft:dirt 64")
        print(f"  已取货 t={int(time.time()-t0)}s")
        break

while time.time() - t0 < 120:
    time.sleep(1)
    if not hand() and ground("cobblestone") >= 16:
        print(f"  giveUp 完成 t={int(time.time()-t0)}s")
        break

time.sleep(3)
early = marks()
print(f"giveUp 后标记数 = {early}（应为 1）")

# 手动腾空目标箱：整箱土清掉 -> 变成可达的空箱
r.cmd(f"fill {X+3} {Y+1} {Z} {X+3} {Y+1} {Z} minecraft:air")
r.cmd(f"setblock {TARGET} minecraft:chest")
print("  已把目标箱清空（现在它是可达的空箱）")

cleared_by = None
t2 = time.time()
while time.time() - t2 < 16:
    time.sleep(1)
    if marks() == 0:
        cleared_by = time.time() - t2
        break
elapsed = int(time.time() - t0)
print(f"标记数 = {marks()}，清空耗时 {('%.0f' % cleared_by) if cleared_by is not None else '>16'} 秒"
      f"，距 giveUp 共 {elapsed}s（TTL 到期应在 giveUp 后 20s）")

ok = early == 1 and cleared_by is not None and (39 + cleared_by) < (39 + 20)
print(("PASS" if ok else "FAIL"), f"(early={early} cleared={cleared_by is not None})")

print("清理中...")
r.cmd(f"kill @e[type=copper_golem,{SEL}]")
r.cmd(f"kill @e[type=item,{SEL}]")
