#!/usr/bin/env python3
"""Publish the freshly built release APK as the in-app update.

Copies app/build/outputs/apk/release/app-arm64-v8a-release.apk to updates/ and writes
updates/latest.json (version, file, SHA-256, size, release notes). Commit + push the
updates/ folder: installed apps find it at
https://raw.githubusercontent.com/<owner>/<repo>/<UPDATE_BRANCH>/updates/latest.json

usage: tools/publish_update.py "notes in English" "ملاحظات بالعربية"
"""
import hashlib, json, re, shutil, sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
gradle = (root / "app/build.gradle.kts").read_text()
code = int(re.search(r"versionCode = (\d+)", gradle).group(1))
name = re.search(r'versionName = "([^"]+)"', gradle).group(1)
out = root / "updates"
out.mkdir(exist_ok=True)
apks = {}
for abi in ["arm64-v8a"]:
    src = root / f"app/build/outputs/apk/release/app-{abi}-release.apk"
    dst = out / f"Zura-{abi}.apk"
    shutil.copyfile(src, dst)
    data = dst.read_bytes()
    apks[abi] = {"file": dst.name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
notes = {"en": sys.argv[1] if len(sys.argv) > 1 else "", "ar": sys.argv[2] if len(sys.argv) > 2 else ""}
latest = {"versionCode": code, "versionName": name, "apk": apks, "notes": notes}
(out / "latest.json").write_text(json.dumps(latest, ensure_ascii=False, indent=2) + "\n")
print(f"published {name} ({code}): {apks}")
