"""搭 8 个独立搬运单元，让傀儡持续工作，用于测量插件开销。

每个单元：铜箱(8 格×64 圆石) -- 傀儡 -- 目标箱(20 格×64 圆石，永远装得下)
傀儡会不停来回搬，每次投放都会触发插件的事件处理 + tidy 排程。
"""
import sys, time
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])

r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")

BX, BY, BZ = 4800, 100, 4800
N = 8
SEL = f"x={BX-10},y={BY},z={BZ-10},distance=..80"


def fill(pos, i, item, n):
    r.cmd(f"item replace block {pos} container.{i} with minecraft:{item} {n}")


r.cmd(f"forceload add {BX-16} {BZ-16} {BX+56} {BZ+32}")
r.cmd(f"kill @e[type=copper_golem,{SEL}]")
r.cmd(f"kill @e[type=item,{SEL}]")
r.cmd(f"fill {BX-4} {BY-1} {BZ-4} {BX+56} {BY+8} {BZ+32} minecraft:air")
r.cmd(f"fill {BX-4} {BY-1} {BZ-4} {BX+56} {BY-1} {BZ+32} minecraft:stone")

cells = []
for i in range(N):
    cx = BX + (i % 4) * 12
    cz = BZ + (i // 4) * 12
    copper = (cx, BY, cz)
    target = (cx + 3, BY, cz)
    r.cmd(f"setblock {copper[0]} {copper[1]} {copper[2]} minecraft:copper_chest")
    r.cmd(f"setblock {target[0]} {target[1]} {target[2]} minecraft:chest")
    for s in range(8):
        fill(f"{copper[0]} {copper[1]} {copper[2]}", s, "cobblestone", 64)
    for s in range(20):
        fill(f"{target[0]} {target[1]} {target[2]}", s, "cobblestone", 64)
    cells.append((cx, cz))

for cx, cz in cells:
    r.cmd(f"summon minecraft:copper_golem {cx+1} {BY+0.5} {cz}")

print(f"已搭建 {N} 个单元，铜箱各 512 圆石，目标箱各 20 格×64")
print("等待 25 秒让傀儡起活...")
time.sleep(25)

working = 0
for cx, cz in cells:
    out = r.cmd(f"data get entity @e[type=copper_golem,x={cx-2},y={BY},z={cz-2},distance=..8,limit=1] equipment")
    if "count: " in out:
        working += 1
print(f"当前有 {working}/{N} 个傀儡手上有物品（正在搬运）")
print("READY")
