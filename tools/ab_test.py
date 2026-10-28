"""A/B 对照：8 个傀儡持续搬运，插件开 vs 关，比 /mspt 与 /tick query。"""
import sys, time, re, statistics
sys.path.insert(0, "/root")
exec(open("/root/rcon.py").read().split("r = Rcon")[0])

r = Rcon(password="SvKzjiTtWZ9D8mcNntCt2fm7Ntn0ph4Q")


def clean(s):
    return re.sub(r"\u00a7.", "", s)


def sample():
    """返回 (5s 平均 mspt, tick query 的平均 tick 时间)"""
    out = clean(r.cmd("mspt"))
    m = re.findall(r"(\d+\.\d+)/(\d+\.\d+)/(\d+\.\d+)", out)
    avg5 = float(m[0][0]) if m else None
    q = clean(r.cmd("tick query"))
    mt = re.search(r"Average time per tick:\s*([\d.]+)\s*ms", q)
    return avg5, float(mt.group(1)) if mt else None


def collect(label, n=6, gap=3):
    vals5, valst = [], []
    for i in range(n):
        a, b = sample()
        if a is not None:
            vals5.append(a)
        if b is not None:
            valst.append(b)
        time.sleep(gap)
    print(f"{label}: mspt5s 中位数 {statistics.median(vals5):.2f} ms {vals5}")
    print(f"{label}: tick query 平均 {statistics.median(valst):.2f} ms {valst}")
    return statistics.median(vals5), statistics.median(valst)


print("=== 插件开启 ===")
on5, ont = collect("ON ", 6, 3)

print("\n=== 关闭插件（/cgp toggle）===")
print(clean(r.cmd("cgp toggle")))
time.sleep(10)
off5, offt = collect("OFF", 6, 3)

print("\n=== 恢复开启 ===")
print(clean(r.cmd("cgp toggle")))

print(f"\n差值: mspt5s {on5 - off5:+.2f} ms，tick query {ont - offt:+.2f} ms")
print(f"占 50ms tick 预算: ON {on5/50*100:.2f}% / OFF {off5/50*100:.2f}%")
