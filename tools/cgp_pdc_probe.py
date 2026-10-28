"""探查：1.21.9 的 /data get entity 到底会不会暴露 PDC。"""
import sys, time
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])

r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")
X, Y, Z = 5000, 100, 5000
COPPER = f"{X} {Y+1} {Z}"
SEL = f"x={X},y={Y+1},z={Z},distance=..40"
GOLEM = f"@e[type=copper_golem,{SEL},limit=1]"

r.cmd(f"forceload add {X-10} {Z-10} {X+10} {Z+10}")
r.cmd(f"fill {X-4} {Y} {Z-4} {X+8} {Y} {Z+4} minecraft:stone")
r.cmd(f"kill @e[type=copper_golem,{SEL}]")
r.cmd(f"fill {X-4} {Y+1} {Z-4} {X+8} {Y+12} {Z+4} minecraft:air")
r.cmd(f"setblock {COPPER} minecraft:copper_chest")
r.cmd(f"item replace block {COPPER} container.0 with minecraft:cobblestone 16")
r.cmd(f"setblock {X+3} {Y+1} {Z} minecraft:chest")
r.cmd(f"summon minecraft:copper_golem {X+1} {Y+1.5} {Z}")
time.sleep(20)   # 足够记录+flush

raw = r.cmd(f"data get entity {GOLEM}")
print("全量 NBT 长度:", len(raw))
for kw in ("PublicBukkitValues", "chest_memory", "coppergolempatch", "copper_golem"):
    print(f"  含 {kw}:", kw in raw, "| 次数", raw.count(kw))
print("前 500 字:", raw[:500].replace("\n", " "))

print("\n--- 试各种路径 ---")
for path in ('PublicBukkitValues',
             'PublicBukkitValues."coppergolempatch:chest_memory"',
             'BukkitValues'):
    out = r.cmd(f"data get entity {GOLEM} {path}")
    print(f"{path} -> {out[:200]}")
