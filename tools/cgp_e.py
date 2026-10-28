"""场景 E：give-up 标记会不会被 TTL 清理掉（0.4.2 的 prune 修复）。

观察点：傀儡的 PDC 里 coppergolemsort:unplaceable_items。
  giveUp 之后应在 1~3 秒内被 flush 成 1 条；
  再过 TTL(20s)+一轮 gossip(1.5s) 后应该变成空列表。
不清理的话这个 map 永远非空，revalidateUnplaceable 会永久每 1.5 秒全量扫一遍。
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
# 1.21+ 的实体 NBT 里 PDC 叫 BukkitValues，不是老名字 PublicBukkitValues
PDC_PATH = 'BukkitValues."coppergolemsort:unplaceable_items"'


def slot(pos, i):
    out = r.cmd(f"data get block {pos} Items[{i}]")
    m = re.search(r'id: "minecraft:(\w+)", count: (\d+)', out)
    return (m.group(1), int(m.group(2))) if m else None


def chest(pos, limit=12):
    out, empty = [], 0
    for i in range(limit):
        s = slot(pos, i)
        out.append(s)
        empty = empty + 1 if s is None else 0
        if empty >= 3:
            break
    return out


def total(pos, item):
    return sum(s[1] for s in chest(pos) if s and s[0] == item)


def hand():
    out = r.cmd(f"data get entity {GOLEM} equipment")
    m = re.search(r'id: "minecraft:(\w+)", count: (\d+)', out)
    return (m.group(1), int(m.group(2))) if m else None


def ground(item):
    out = r.cmd(f"execute as @e[type=item,{SEL}] run data get entity @s Item")
    return sum(int(m.group(2)) for m in re.finditer(r'id: "minecraft:(\w+)", count: (\d+)', out)
               if m.group(1) == item)


def fill(pos, i, item, n):
    r.cmd(f"item replace block {pos} container.{i} with minecraft:{item} {n}")


def full_of(pos, item="dirt", free=None):
    for i in range(27):
        if i == free:
            continue
        fill(pos, i, item, 64)


def marks():
    out = r.cmd(f"data get entity {GOLEM} {PDC_PATH}")
    if "has no data" in out or "Found no element" in out:
        return 0
    n = out.count("marked_at")
    if n:
        return n
    # 空列表 -> data get 输出里没有 marked_at
    return 0


print("=== 场景 E：give-up 标记 TTL 清理 ===")
r.cmd(f"forceload add {X-10} {Z-10} {X+10} {Z+10}")
r.cmd(f"fill {X-4} {Y} {Z-4} {X+8} {Y} {Z+4} minecraft:stone")

r.cmd(f"kill @e[type=copper_golem,{SEL}]")
r.cmd(f"kill @e[type=item,{SEL}]")
r.cmd(f"fill {X-4} {Y+1} {Z-4} {X+8} {Y+12} {Z+4} minecraft:air")
for p in (COPPER, TARGET):
    r.cmd(f"setblock {p} minecraft:chest")
r.cmd(f"setblock {COPPER} minecraft:copper_chest")
fill(COPPER, 0, "cobblestone", 16)
full_of(COPPER, free=0)
full_of(TARGET)
print("起始: 铜箱 slot0=16 圆石 + 26 格土，目标箱满土，附近无其他容器")
r.cmd(f"summon minecraft:copper_golem {X+1} {Y+1.5} {Z}")

# 取货瞬间把铜箱 slot0 补满，制造"真的没地方放"
t0 = time.time()
while time.time() - t0 < 40:
    time.sleep(0.3)
    if hand() is not None:
        r.cmd(f"item replace block {COPPER} container.0 with minecraft:dirt 64")
        print(f"  已取货 {hand()}")
        break

# 等 giveUp 真正发生（地面出现 16 圆石、手上清空）
gave_up = False
t1 = time.time()
while time.time() - t1 < 90:
    time.sleep(1)
    if hand() is None and ground("cobblestone") >= 16:
        gave_up = True
        print(f"  t={int(time.time()-t0)}s giveUp 完成：地面 {ground('cobblestone')} 圆石，手上空")
        break

time.sleep(3)   # 等一轮 gossip flush（30 tick = 1.5s）
early = marks()
print(f"giveUp 后标记数 = {early}（应为 1）")

time.sleep(30)  # TTL 20s + 余量
late = marks()
print(f"TTL 过期后标记数 = {late}（应为 0）")

ok = gave_up and early == 1 and late == 0
print(("PASS" if ok else "FAIL"), f"(giveUp={gave_up} 标记 {early} -> {late})")

print("清理中...")
r.cmd(f"kill @e[type=copper_golem,{SEL}]")
r.cmd(f"kill @e[type=item,{SEL}]")
