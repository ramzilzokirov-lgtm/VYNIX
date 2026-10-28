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
app = riff / "android" / "app"

gradle = app / "build.gradle"
text = gradle.read_text(encoding="utf-8")
text = text.replace("applicationId 'io.github.rootscripts.riff'", "applicationId 'app.vynix'")
text = re.sub(r"versionCode\s+1", "versionCode 5", text, count=1)
text = re.sub(r"versionName\s+'[^']+'", "versionName '0.1.0-beta2'", text, count=1)
text = text.replace("ndk { abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64' }", "")
text = text.replace("include 'arm64-v8a', 'armeabi-v7a', 'x86_64'\n            universalApk true", "include 'arm64-v8a'\n            universalApk false")
gradle.write_text(text, encoding="utf-8")

manifest = app / "src/main/AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
text = text.replace('android:label="Riff"', 'android:label="VYNIX"')
text = text.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_vynix_logo"')
text = text.replace('android:roundIcon="@mipmap/ic_launcher"', 'android:roundIcon="@drawable/ic_vynix_logo"')
manifest.write_text(text, encoding="utf-8")

literal = re.compile(r'"(?:\\\\.|[^"\\\\])*"|\'(?:\\\\.|[^\'\\\\])*\'')
for path in (app / "src/main").rglob("*"):
    if not path.is_file() or path.suffix.lower() not in {".java", ".xml", ".properties", ".json"}:
        continue
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    updated = literal.sub(lambda m: re.sub(r"\bRiff\b", "VYNIX", m.group(0)), content)
    updated = updated.replace(">Riff<", ">VYNIX<")
    if updated != content:
        path.write_text(updated, encoding="utf-8")

icon_source = vynix / "app/src/main/res/drawable/ic_vynix_logo.xml"
icon_target = app / "src/main/res/drawable/ic_vynix_logo.xml"
icon_target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(icon_source, icon_target)

main_activity = app / "src/main/java/io/github/rootscripts/riff/MainActivity.java"
if main_activity.exists():
    content = main_activity.read_text(encoding="utf-8").replace("0xffd0bcff", "0xffffcd5e")
    main_activity.write_text(content, encoding="utf-8")

online = app / "src/main/java/io/github/rootscripts/riff/Online.java"
content = online.read_text(encoding="utf-8")
content = content.replace('"ytsearch15:"', '"ytsearch5:"').replace('"scsearch15:"', '"scsearch5:"')
content = content.replace('.addOption("--retries", 1)', '.addOption("--retries", 2)')
content = content.replace('.addOption("--extractor-retries", 1)', '.addOption("--extractor-retries", 2)')
content = content.replace(
    "if (cached != null && System.currentTimeMillis() - cached.time < 20 * 60 * 1000) return cached;",
    "// Signed media URLs expire quickly; avoid reusing stale URLs on replay.\n"
    "    if (cached != null && System.currentTimeMillis() - cached.time < 45 * 1000) return cached;\n"
    "    cache.remove(source);",
)
content = content.replace('.addOption("--no-warnings");', '.addOption("--no-warnings")\n        .addOption("--no-cache-dir");')
online.write_text(content, encoding="utf-8")

media_source = app / "src/main/java/io/github/rootscripts/riff/OnlineMediaSource.java"
content = media_source.read_text(encoding="utf-8")
old_http_factory = '''                new DefaultHttpDataSource.Factory()
                    .setUserAgent("RiffAndroid/1.0")
                    .setConnectTimeoutMs(20000)
                    .setReadTimeoutMs(30000);'''
new_http_factory = '''                new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(20000)
                    .setReadTimeoutMs(45000);'''
if old_http_factory not in content:
    raise SystemExit("Expected HTTP factory block was not found; refusing to build without the playback-header fix.")
content = content.replace(old_http_factory, new_http_factory, 1)
old_error = 'error = new IOException("Could not resolve this audio: " + e.getMessage(), e);'
new_error = old_error + '\n                    refreshSourceInfo(Timeline.EMPTY);'
if old_error not in content:
    raise SystemExit("Expected resolver error block was not found; refusing to build an unpatched APK.")
content = content.replace(old_error, new_error, 1)
media_source.write_text(content, encoding="utf-8")

print("Prepared VYNIX branding, online playback fixes and ARM64-only APK.")
