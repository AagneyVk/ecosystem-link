#!/usr/bin/env python3
"""
Ecosystem Hub — startup diagnostic script.
Run this INSTEAD of the hub to find the exact error.

Usage:
    python diagnose.py
"""
import sys
import os

print(f"Python: {sys.version}")
print(f"CWD: {os.getcwd()}")
print()

errors = []

# 1. Check Python version
if sys.version_info < (3, 11):
    errors.append(f"❌ Python 3.11+ required. You have {sys.version_info.major}.{sys.version_info.minor}")
else:
    print(f"✅ Python version OK ({sys.version_info.major}.{sys.version_info.minor})")

# 2. Check required packages
packages = {
    "aiohttp": "aiohttp",
    "websockets": "websockets",
    "tomllib": "built-in (Python 3.11+)",
}
for pkg, label in packages.items():
    try:
        __import__(pkg)
        print(f"✅ {pkg} installed ({label})")
    except ImportError:
        errors.append(f"❌ Missing package: {pkg}  →  run: pip install {pkg}")

# 3. Check config file
from pathlib import Path
config_paths = [
    Path.home() / ".config" / "ecosystem" / "config.toml",
    Path("/etc/ecosystem/config.toml"),
]
for cp in config_paths:
    if cp.exists():
        size = cp.stat().st_size
        print(f"\n📄 Config file found: {cp} ({size} bytes)")
        if size == 0:
            errors.append(f"❌ Config file is EMPTY: {cp}\n"
                          f"   Fix: either delete it (rm {cp})\n"
                          f"   or fill it with valid TOML content")
        else:
            try:
                import tomllib
                with open(cp, "rb") as f:
                    data = tomllib.load(f)
                print(f"✅ Config parses OK — keys: {list(data.keys())}")
            except Exception as e:
                errors.append(f"❌ Config TOML parse error in {cp}:\n   {e}\n"
                              f"   Fix: delete the file (rm {cp}) and let defaults be used")
        break
else:
    print("\n📄 No config file found — defaults will be used (this is fine)")

# 4. Check ecosystem_hub package is importable
print()
try:
    import ecosystem_hub
    print(f"✅ ecosystem_hub package found at: {ecosystem_hub.__file__}")
except ImportError as e:
    errors.append(f"❌ Cannot import ecosystem_hub: {e}\n"
                  f"   Make sure you are running from inside ~/arch-hub with the venv active")

# 5. Check sub-modules
submodules = [
    "ecosystem_hub.config.config",
    "ecosystem_hub.core.device_manager",
    "ecosystem_hub.core.session_manager",
    "ecosystem_hub.storage.storage_manager",
    "ecosystem_hub.streaming.streaming_server",
    "ecosystem_hub.ui.web_server",
    "ecosystem_hub.server.websocket_server",
    "ecosystem_hub.plugins.base",
    "ecosystem_hub.plugins.camera_plugin",
    "ecosystem_hub.plugins.audio_plugin",
]
for mod in submodules:
    try:
        __import__(mod)
        print(f"✅ {mod}")
    except Exception as e:
        errors.append(f"❌ Import failed: {mod}\n   Error: {e}")

# 6. Check static files exist
print()
static_dir = Path(__file__).parent / "ecosystem_hub" / "ui" / "static"
for f in ["index.html", "style.css", "app.js"]:
    p = static_dir / f
    if p.exists():
        print(f"✅ Static file OK: {f} ({p.stat().st_size} bytes)")
    else:
        errors.append(f"❌ Missing static file: {p}")

# 7. Check storage root can be created
print()
from pathlib import Path
storage = Path.home() / "EcosystemHub"
try:
    storage.mkdir(parents=True, exist_ok=True)
    print(f"✅ Storage root writable: {storage}")
except Exception as e:
    errors.append(f"❌ Cannot create storage root {storage}: {e}")

# ── Summary ──────────────────────────────────────────────────────────────────
print()
print("=" * 60)
if errors:
    print(f"FOUND {len(errors)} PROBLEM(S):\n")
    for i, e in enumerate(errors, 1):
        print(f"  [{i}] {e}")
        print()
    print("Fix all issues above, then run the hub again.")
else:
    print("✅ ALL CHECKS PASSED — should start cleanly!")
    print("   Run: python -m ecosystem_hub.main")
print("=" * 60)
