import sys, time
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])
r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")

BX, BY, BZ = 4800, 100, 4800
N = 8


def fill(pos, i, item, n):
    r.cmd(f"item replace block {pos} container.{i} with minecraft:{item} {n}")


print("在线傀儡数:", r.cmd("execute if entity @e[type=copper_golem] run say X"))
for i in range(N):
    cx = BX + (i % 4) * 12
    cz = BZ + (i // 4) * 12
    for s in range(8):
        fill(f"{cx} {BY} {cz}", s, "cobblestone", 64)
print("铜箱已补满 8 格×64")
