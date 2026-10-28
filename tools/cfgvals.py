import sys, json, yaml

d = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
print(json.dumps(d["give-up"], ensure_ascii=False, sort_keys=True))
print("groups:", len(d.get("similar-items", {}).get("groups", [])))
print("tidy:", json.dumps(d.get("tidy"), ensure_ascii=False, sort_keys=True))
