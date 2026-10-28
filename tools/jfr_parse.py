"""解析 JFR：主线程（Server thread）的 CPU 采样里，有多少落在插件代码上。"""
import subprocess, sys, re
from collections import Counter

out = subprocess.run(["jfr", "print", "--events", "jdk.ExecutionSample", "/tmp/cgp.jfr"],
                     capture_output=True, text=True).stdout

blocks = out.split("jdk.ExecutionSample {")[1:]
total = len(blocks)
server = [b for b in blocks if '"Server thread"' in b]
hits = [b for b in server if "dev.cgp.coppergolempatch" in b]

frames = Counter()
for b in hits:
    for line in b.splitlines():
        s = line.strip()
        if "dev.cgp.coppergolempatch" in s:
            m = re.search(r"dev\.cgp\.coppergolempatch[\w.$]*\.([\w$<>]+)", s)
            if m:
                frames[m.group(0).split(".")[-1]] += 1

print(f"总采样数      : {total}")
print(f"Server thread : {len(server)}  ({len(server)*100.0/max(total,1):.1f}% of all)")
if server:
    print(f"命中插件代码  : {len(hits)}  ({len(hits)*100.0/len(server):.2f}% of server-thread samples)")
print("插件出现的方法:", dict(frames.most_common(10)) if frames else "无")
