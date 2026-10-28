import yaml, sys, re

p = "/opt/mc/plugins/CopperGolemSort/config.yml"
raw = open(p, encoding="utf-8").read()
d = yaml.safe_load(raw)

print("top keys:", list(d.keys()))
si = d.get("similar-items", {})
groups = si.get("groups", [])
print("groups:", len(groups), "| enabled 组:", sum(1 for g in groups if g.get("enabled")))
print("similar-items 其他字段:", {k: v for k, v in si.items() if k != "groups"})
print("give-up:", d.get("give-up"))
print("tidy:", d.get("tidy"))
print("enabled:", d.get("enabled"), "| gossip:", d.get("gossip"), "| memory:", d.get("memory"))

# 重复组名 / 空组
names = [g.get("name") for g in groups]
dups = [n for n in set(names) if names.count(n) > 1]
print("重复组名:", dups)
empty = [g.get("name") for g in groups if not g.get("items")]
print("空 items 组:", empty)

# 跨组重复物品（统计）
seen = {}
cross = []
for g in groups:
    for it in g.get("items", []):
        it = str(it).strip()
        if it in seen and seen[it] != g.get("name"):
            cross.append((it, seen[it], g.get("name")))
        seen.setdefault(it, g.get("name"))
print("跨组重复物品数:", len(cross))
if cross[:5]:
    print("  例:", cross[:5])
print("物品条目总数:", len(seen))
