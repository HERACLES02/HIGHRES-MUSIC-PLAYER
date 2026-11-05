"""Check that an open, unsaved text draft survives emulator rotation.

Open the editor to check first (for example Lyrics -> Paste / edit). This
types a synthetic draft, rotates the virtual device and checks the real
Android accessibility tree. It never presses Save or modifies music files.
"""
from pathlib import Path
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default="127.0.0.1:5561")
parser.add_argument("--server-port", default="5038")
parser.add_argument("--field-index", type=int, default=0)
parser.add_argument("--name", default="draft")
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
base = [str(project / ".tools/sdk/platform-tools/adb.exe"), "-P", args.server_port, "-s", args.serial]
out = project / "releases/ui-state-checks"
out.mkdir(exist_ok=True)
assert re.fullmatch(r"[a-zA-Z0-9_-]+", args.name)


def adb(*command):
    return subprocess.run(base + list(map(str, command)), check=True, capture_output=True, timeout=30).stdout


def hierarchy():
    adb("shell", "uiautomator", "dump", "/data/local/tmp/localmusic-ui-state.xml")
    raw = adb("exec-out", "cat", "/data/local/tmp/localmusic-ui-state.xml")
    return ET.fromstring(raw), raw


def capture(stage):
    root, raw = hierarchy()
    (out / f"{args.name}-{stage}.xml").write_bytes(raw)
    (out / f"{args.name}-{stage}.png").write_bytes(adb("exec-out", "screencap", "-p"))
    return root


def has_draft(root):
    return any(node.get("class") == "android.widget.EditText" and node.get("text") == draft
               for node in root.iter("node"))


if adb("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
    raise SystemExit("This check requires an emulator.")
original = adb("shell", "settings", "get", "system", "user_rotation").decode().strip()
accelerometer = adb("shell", "settings", "get", "system", "accelerometer_rotation").decode().strip()
assert original in {"0", "1", "2", "3"}
target = "1" if original in {"0", "2"} else "0"
draft = "Draft survives rotation"
root, _ = hierarchy()
fields = [node for node in root.iter("node") if node.get("class") == "android.widget.EditText" and node.get("enabled") == "true"]
assert len(fields) > args.field_index, "Open the editor first; no matching text field is visible."
box = list(map(int, re.findall(r"\d+", fields[args.field_index].get("bounds"))))
adb("shell", "input", "tap", (box[0] + box[2]) // 2, (box[1] + box[3]) // 2)
adb("shell", "input", "keycombination", "113", "29")
adb("shell", "input", "text", draft.replace(" ", "%s"))
assert has_draft(capture("before")), "Synthetic draft input did not reach the editor."
try:
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb("shell", "settings", "put", "system", "user_rotation", target)
    # Wait for the new layout, not the old activity's last frame.
    end = time.monotonic() + 20
    size = re.search(rb"(\d+)x(\d+)", adb("shell", "wm", "size"))
    short_edge = min(map(int, size.groups()))
    while True:
        root, _ = hierarchy()
        boxes = [list(map(int, re.findall(r"\d+", n.get("bounds")))) for n in root.iter("node")]
        if not boxes:
            assert time.monotonic() < end, "No active window after rotation."
            continue
        extent = max(b[2 if target == "1" else 3] for b in boxes)
        if extent > short_edge:
            break
        assert time.monotonic() < end, "Emulator did not finish rotating."
    for _ in range(2):
        assert has_draft(capture("after")), "Unsaved draft/editor disappeared after activity recreation."
    print(f"PASS: {args.name} draft survived rotation; no Save action was sent.")
finally:
    adb("shell", "settings", "put", "system", "user_rotation", original)
    adb("shell", "settings", "put", "system", "accelerometer_rotation", accelerometer)
