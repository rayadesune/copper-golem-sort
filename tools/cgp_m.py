import sys, time, re
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])

r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")

# 全新区域，避免旧测试残留的箱子被傀儡当成目标（之前 2000,106,2000 就踩过）
X, Y, Z = 5000, 100, 5000
COPPER = f"{X} {Y+1} {Z}"
TARGET = f"{X+3} {Y+1} {Z}"
EXTRA = f"{X+5} {Y+1} {Z}"
SEL = f"x={X},y={Y+1},z={Z},distance=..40"


def slot(pos, i):
    out = r.cmd(f"data get block {pos} Items[{i}]")
    m = re.search(r'id: "minecraft:(\w+)", count: (\d+)', out)
    return (m.group(1), int(m.group(2))) if m else None


def chest(pos, limit=12):
    """逐槽读；data get 的整包输出会被截断，不能一次拿全。"""
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
    out = r.cmd(f"data get entity @e[type=copper_golem,{SEL},limit=1] equipment")
    m = re.search(r'id: "minecraft:(\w+)", count: (\d+)', out)
    return (m.group(1), int(m.group(2))) if m else None


def ground(item):
    out = r.cmd(f"execute as @e[type=item,{SEL}] run data get entity @s Item")
    return sum(int(m.group(2)) for m in re.finditer(r'id: "minecraft:(\w+)", count: (\d+)', out)
               if m.group(1) == item)


def fill(pos, i, item, n):
    r.cmd(f"item replace block {pos} container.{i} with minecraft:{item} {n}")


def reset(*positions):
    r.cmd(f"kill @e[type=copper_golem,{SEL}]")
    r.cmd(f"kill @e[type=item,{SEL}]")
    r.cmd(f"fill {X-4} {Y+1} {Z-4} {X+8} {Y+12} {Z+4} minecraft:air")
    for p in positions:
        r.cmd(f"setblock {p} minecraft:chest")


def full_of(pos, item="dirt", free=None):
    for i in range(27):
        if i == free:
            continue
        fill(pos, i, item, 64)


def show(pos):
    return [f"{s[0]}x{s[1]}" if s else "-" for s in chest(pos, 6)]


scenario = sys.argv[1]
print(f"=== 场景 {scenario} ===")
r.cmd(f"forceload add {X-10} {Z-10} {X+10} {Z+10}")
r.cmd(f"fill {X-4} {Y} {Z-4} {X+8} {Y} {Z+4} minecraft:stone")

ok = False
if scenario == "A":
    reset(COPPER, TARGET)
    r.cmd(f"setblock {COPPER} minecraft:copper_chest")
    fill(COPPER, 0, "cobblestone", 16)
    for i in range(4):
        fill(TARGET, i, "cobblestone", 16)
    print("起始: 铜箱", show(COPPER), "目标", show(TARGET))
    r.cmd(f"summon minecraft:copper_golem {X+1} {Y+1.5} {Z}")
    time.sleep(50)
    t = total(TARGET, "cobblestone")
    print("结果: 铜箱", show(COPPER), "目标", show(TARGET), "傀儡", hand(), "地面", ground("cobblestone"))
    ok = t == 80 and total(COPPER, "cobblestone") == 0 and ground("cobblestone") == 0
    print(("PASS" if ok else "FAIL"), f"(目标 {t}，应 80)")

elif scenario == "B":
    reset(COPPER, TARGET)
    r.cmd(f"setblock {COPPER} minecraft:copper_chest")
    fill(COPPER, 0, "cobblestone", 60)
    full_of(COPPER, free=0)
    full_of(TARGET)
    print("起始: 铜箱 slot0=60 + 26格土，目标箱满土")
    r.cmd(f"summon minecraft:copper_golem {X+1} {Y+1.5} {Z}")
    series = []
    for _ in range(25):
        time.sleep(3)
        v = total(COPPER, "cobblestone")
        if not series or series[-1] != v:
            print(f"  t={len(series)*3+3:3d}s  铜箱 slot0={v:3d}  傀儡={hand()}")
        series.append(v)
    dip = [i for i, v in enumerate(series) if v < 60]
    back = any(v == 60 for v in series[dip[0]:]) if dip else False
    ok = bool(dip) and back and ground("cobblestone") == 0
    print(("PASS" if ok else "FAIL"), f"(取货={bool(dip)} 归还={back} 地面={ground('cobblestone')})")

elif scenario in ("C", "D"):
    reset(COPPER, TARGET, *( [EXTRA] if scenario == "C" else [] ))
    r.cmd(f"setblock {COPPER} minecraft:copper_chest")
    fill(COPPER, 0, "cobblestone", 16)
    full_of(COPPER, free=0)
    full_of(TARGET)
    if scenario == "C":
        full_of(EXTRA, free=0)      # 26 格土 + 1 空格：原版不会选它，兜底会用
        print("起始: 铜箱 slot0=16 + 26格土，目标满土，备用箱 26格土 + 1空格")
    else:
        print("起始: 铜箱 slot0=16 + 26格土，目标满土，附近无其他容器")
    r.cmd(f"summon minecraft:copper_golem {X+1} {Y+1.5} {Z}")

    # 关键：傀儡取货后铜箱的 slot0 会空出来，归还逻辑自然会优先放回那里，
    # 于是后备箱永远用不上。取到的瞬间把那一格补满，才是真的"塞不下"。
    t0 = time.time()
    while time.time() - t0 < 40:
        time.sleep(0.3)
        if hand() is not None:
            r.cmd(f"item replace block {COPPER} container.0 with minecraft:dirt 64")
            print(f"  已取货（{hand()}），铜箱 slot0 已补满 -> 现在真的没地方放")
            break

    time.sleep(70)
    if scenario == "C":
        e = total(EXTRA, "cobblestone")
        print("结果: 备用箱", show(EXTRA), "铜箱", show(COPPER), "地面", ground("cobblestone"))
        ok = e == 16 and ground("cobblestone") == 0
        print(("PASS" if ok else "FAIL"), f"(备用箱 {e}，应 16)")
    else:
        g = ground("cobblestone")
        print("结果: 地面圆石", g, "傀儡", hand(), "铜箱", show(COPPER))
        ok = g == 16
        print(("PASS" if ok else "FAIL"), f"(地面 {g}，应 16)")

print("清理中...")
r.cmd(f"kill @e[type=copper_golem,{SEL}]")
r.cmd(f"kill @e[type=item,{SEL}]")
