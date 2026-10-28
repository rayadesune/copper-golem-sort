import sys
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])
r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")
for c in ["spark profiler", "spark profiler start --help", "spark help"]:
    print("$ " + c)
    print(r.cmd(c)[:1200])
    print()
