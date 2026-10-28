import sys
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])
r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")

print(r.cmd("kill @e[type=copper_golem]"))
print(r.cmd("kill @e[type=item,x=4700,y=0,z=4700,dx=300,dy=256,dz=300]"))
print(r.cmd("fill 4796 99 4796 4856 108 4832 minecraft:air"))
print(r.cmd("forceload remove all"))
print(r.cmd("forceload query"))
print(r.cmd("execute if entity @e[type=copper_golem] run say GOLEM_EXISTS"))
print(r.cmd("execute if entity @e[type=item,x=4700,y=0,z=4700,dx=300,dy=256,dz=300] run say ITEM_EXISTS"))
print(r.cmd("tps"))
print(r.cmd("mspt").replace("\u00a7", ""))
