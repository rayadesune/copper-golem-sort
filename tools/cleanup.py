import sys
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])
r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")

SEL = "x=5000,y=101,z=5000,distance=..40"
print(r.cmd(f"kill @e[type=copper_golem,{SEL}]"))
print(r.cmd(f"kill @e[type=item,{SEL}]"))
print(r.cmd("fill 4996 99 4996 5008 112 5004 minecraft:air"))
print(r.cmd("forceload remove all"))
print(r.cmd("forceload query"))
print(r.cmd("tps"))
print(r.cmd("list"))
print(r.cmd("execute if entity @e[type=copper_golem] run say GOLEM_EXISTS"))
print(r.cmd("execute if entity @e[type=item,x=4900,y=0,z=4900,dx=200,dy=256,dz=200] run say ITEM_EXISTS"))
