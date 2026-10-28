import sys
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])
r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")
for c in ["spark", "spark tps", "tick query", "tick", "mspt", "paper", "plugins"]:
    print("$ " + c)
    print(r.cmd(c)[:400])
    print()
