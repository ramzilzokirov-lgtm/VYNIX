#!/usr/bin/env python3
"""Prepare the pinned Riff Android source as a VYNIX-branded build."""
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: rebrand_riff.py <vynix-checkout> <riff-checkout>")

vynix = Path(sys.argv[1]).resolve()
riff = Path(sys.argv[2]).resolve()
android = riff / "android"
app = android / "app"

gradle = app / "build.gradle"
text = gradle.read_text(encoding="utf-8")
text = text.replace("applicationId 'io.github.rootscripts.riff'", "applicationId 'app.vynix'")
text = re.sub(r"versionCode\s+1", "versionCode 3", text, count=1)
text = re.sub(r"versionName\s+'[^']+'", "versionName '0.1.0-beta'", text, count=1)
gradle.write_text(text, encoding="utf-8")

manifest = app / "src/main/AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
text = text.replace('android:label="Riff"', 'android:label="VYNIX"')
text = text.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_vynix_logo"')
text = text.replace('android:roundIcon="@mipmap/ic_launcher"', 'android:roundIcon="@drawable/ic_vynix_logo"')
manifest.write_text(text, encoding="utf-8")

# Replace brand text inside quoted UI literals only. Keep class/package names,
# upstream URLs, copyright notices, and license text intact.
source = app / "src/main"
literal = re.compile(r'"(?:\\\\.|[^"\\\\])*"|\'(?:\\\\.|[^\'\\\\])*\'')
for path in source.rglob("*"):
    if not path.is_file() or path.suffix.lower() not in {".java", ".xml", ".properties", ".json"}:
        continue
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue

    def rebrand(match: re.Match[str]) -> str:
        value = match.group(0)
        return re.sub(r"\bRiff\b", "VYNIX", value)

    updated = literal.sub(rebrand, content)
    # The manifest and resource labels can be unquoted text nodes/attributes.
    updated = updated.replace(">Riff<", ">VYNIX<")
    if updated != content:
        path.write_text(updated, encoding="utf-8")

# Use VYNIX's existing graphite-and-gold vector for the launcher icon.
icon_source = vynix / "app/src/main/res/drawable/ic_vynix_logo.xml"
icon_target = app / "src/main/res/drawable/ic_vynix_logo.xml"
icon_target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(icon_source, icon_target)

# The source app's default accent is lavender; make gold the default while
# leaving the built-in accent choices available to the user.
main_activity = app / "src/main/java/io/github/rootscripts/riff/MainActivity.java"
if main_activity.exists():
    content = main_activity.read_text(encoding="utf-8")
    content = content.replace("0xffd0bcff", "0xffffcd5e")
    main_activity.write_text(content, encoding="utf-8")

print("Prepared VYNIX branding, package ID, version and launcher icon.")
