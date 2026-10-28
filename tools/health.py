import sys
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])
r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")
for c in ["tps", "list", "forceload query",
          "execute if entity @e[type=copper_golem] run say GOLEM_EXISTS",
          "execute if entity @e[type=item,x=4900,y=0,z=4900,dx=200,dy=256,dz=200] run say TEST_ITEM_EXISTS"]:
    print("$ " + c)
    print(r.cmd(c))
    print()
