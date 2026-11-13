#!/usr/bin/env bash
# Regenerates android/app/src/main/assets/autoeq.zip from the AutoEq repo.
# Blobless sparse clone keeps the download to roughly the ParametricEQ text
# files (~4 MB) instead of the full multi-GB measurement repository.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Cloning AutoEq (blobless sparse)..."
git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/jaakkopasanen/AutoEq.git "$WORK/AutoEq"
cd "$WORK/AutoEq"
git sparse-checkout set --no-cone '/results/**/* ParametricEQ.txt' '/LICENSE.txt' '/LICENSE'
SHA="$(git rev-parse --short HEAD)"

echo "Packing asset..."
python3 - "$WORK/AutoEq" "$WORK/out" "$SHA" << 'PY'
import json, os, sys, zipfile
from datetime import date, timezone, datetime

repo, out, sha = sys.argv[1], sys.argv[2], sys.argv[3]
results = os.path.join(repo, "results")
profiles = []
paths = []
for dirpath, _, files in os.walk(results):
    for f in sorted(files):
        if not f.endswith(" ParametricEQ.txt"):
            continue
        rel = os.path.relpath(dirpath, results)
        parts = rel.split(os.sep)
        # results/<Source>/<Rig>/<Model>/<Model> ParametricEQ.txt
        source = parts[0] if len(parts) > 0 else ""
        rig = parts[1] if len(parts) > 1 else ""
        name = f[: -len(" ParametricEQ.txt")]
        profiles.append({"name": name, "source": source, "rig": rig})
        paths.append(os.path.join(dirpath, f))

order = sorted(range(len(profiles)), key=lambda i: profiles[i]["name"].lower())
profiles = [profiles[i] for i in order]
paths = [paths[i] for i in order]

os.makedirs(out, exist_ok=True)
index = {
    "generated": datetime.now(timezone.utc).date().isoformat(),
    "source": f"jaakkopasanen/AutoEq@{sha}",
    "profiles": profiles,
}
with zipfile.ZipFile(os.path.join(out, "autoeq.zip"), "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr("index.json", json.dumps(index, separators=(",", ":")))
    for i, p in enumerate(paths):
        with open(p, "rb") as fh:
            z.writestr(f"profiles/{i}.txt", fh.read())
print(f"{len(profiles)} profiles")
PY

mkdir -p "$ASSETS"
cp "$WORK/out/autoeq.zip" "$ASSETS/autoeq.zip"
LICENSE_SRC="$WORK/AutoEq/LICENSE.txt"
[ -f "$LICENSE_SRC" ] || LICENSE_SRC="$WORK/AutoEq/LICENSE"
cp "$LICENSE_SRC" "$ASSETS/autoeq-LICENSE.txt"
ls -la "$ASSETS/autoeq.zip"
echo "Done — regenerate any time with android/scripts/generate-autoeq-asset.sh"
