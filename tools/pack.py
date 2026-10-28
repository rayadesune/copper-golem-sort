import zipfile, os, sys, yaml

src, out, outdir, newver = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
# Optional: directory whose config.yml replaces the one inside the base jar.
# Without it the shipped default config is whatever the previous release
# happened to bundle, which is how a dead key (after-rejections) kept
# reappearing in a release that no longer reads it.
resdir = sys.argv[5] if len(sys.argv) > 5 else None

desc = (
    "Golems remember which chests hold what, share it with nearby golems, "
    "deposit into chests holding a same-category item (all wool colours, all planks, "
    "every sword, ...), consolidate the partial stacks left behind, and hand an item "
    "back to a copper chest or nearby chest/barrel when nothing will accept it."
)

with zipfile.ZipFile(src) as zin:
    old_ver = yaml.safe_load(zin.read("paper-plugin.yml").decode())["version"]

with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename.endswith(".class"):
            continue
        data = zin.read(item.filename)
        if resdir and item.filename == "config.yml":
            override = os.path.join(resdir, "config.yml")
            if os.path.exists(override):
                data = open(override, "rb").read()
        if item.filename == "paper-plugin.yml":
            text = data.decode("utf-8").replace('"' + old_ver + '"', '"' + newver + '"')
            lines = [("description: " + '"' + desc + '"') if l.startswith("description:") else l
                     for l in text.split("\n")]
            data = "\n".join(lines).encode("utf-8")
        zout.writestr(item, data)
    for root, dirs, files in os.walk(outdir):
        for f in sorted(files):
            if not f.endswith(".class"):
                continue
            full = os.path.join(root, f)
            zout.write(full, os.path.relpath(full, outdir).replace(os.sep, "/"))

with zipfile.ZipFile(out) as z:
    parsed = yaml.safe_load(z.read("paper-plugin.yml").decode())
    cfg = yaml.safe_load(z.read("config.yml").decode())
    print("built", out, os.path.getsize(out), "bytes |", len([n for n in z.namelist() if n.endswith(".class")]), "classes")
    print("version:", parsed["version"], "| yaml ok")
    print("bundled config groups:", len(cfg.get("similar-items", {}).get("groups", [])),
          "| give-up keys:", sorted(cfg.get("give-up", {}).keys()))

