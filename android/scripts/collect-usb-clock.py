"""Read USB-audio diagnostic access on the OPPO phone; no playback or settings changes.

USB-connected collection is an access preflight: the M2 need not be attached.
An optional explicit serial can select an already-connected wireless ADB target
when the M2 occupies the phone's USB port. This script never enables wireless ADB.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import uuid


def run_query(base, command):
    try:
        result = subprocess.run(base + ["shell", command], capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=10)
        return {"exitCode": result.returncode, "stdout": result.stdout,
                "stderr": result.stderr, "timedOut": False}
    except subprocess.TimeoutExpired:
        return {"exitCode": None, "stdout": "", "stderr": "ADB query timed out.", "timedOut": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Explicit existing ADB target; default selects one physical USB phone (-d).")
    parser.add_argument("--server-port", type=int, default=5037)
    args = parser.parse_args()
    if not 1 <= args.server_port <= 65535:
        parser.error("server port must be between 1 and 65535")
    project = Path(__file__).resolve().parents[1]
    adb_path = project / ".tools/sdk/platform-tools/adb.exe"
    if not adb_path.is_file():
        parser.error(f"Project ADB is missing: {adb_path}")
    base = [str(adb_path), "-P", str(args.server_port)]
    base += ["-s", args.serial] if args.serial else ["-d"]
    report = {
        "schemaVersion": 1,
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "Read-only host-driver diagnostics; no render-clock, balance or bit-perfect validation.",
        "targetSelection": "explicit" if args.serial else "single physical USB phone",
        "queries": {},
    }
    queries = report["queries"]
    queries["model"] = run_query(base, "getprop ro.product.model")
    model = queries["model"]
    if model["exitCode"] != 0 or model["stdout"].strip() != "CPH1937":
        report["result"] = "target_not_confirmed"
        report["nextStep"] = "Connect and authorize the CPH1937 phone. No audio diagnostic files were read."
    else:
        queries["sdk"] = run_query(base, "getprop ro.build.version.sdk")
        queries["kernelRelease"] = run_query(base, "cat /proc/sys/kernel/osrelease")
        queries["asoundAccess"] = run_query(base, "ls -ld /proc/asound /proc/asound/cards")
        queries["cards"] = run_query(base, "cat /proc/asound/cards")
        queries["pcmDevices"] = run_query(base, "cat /proc/asound/pcm")
        # Constant commands only; user input is never interpolated into the remote shell.
        queries["usbStreams"] = run_query(base, '''found=0
for f in /proc/asound/card*/stream*; do
    if [ -f "$f" ]; then
        found=1
        echo "FILE: $f"
        cat "$f" || exit 1
    fi
done
if [ "$found" = 0 ]; then
    echo 'No USB stream file was visible. Missing devices and denied directory traversal remain possible.'
    exit 3
fi''')
        cards = queries["cards"]
        report["result"] = "cards_readable" if cards["exitCode"] == 0 and cards["stdout"].strip() else "access_not_established"
        report["nextStep"] = "Review the raw query results before arranging a live M2 snapshot. Missing files or permission errors do not mean missing feedback."
    output_dir = project / "releases/usb-clock"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / ("usb-clock-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8] + ".json")
    # Exclusive creation preserves all prior reports, including unexpected name collisions.
    with output.open("x", encoding="utf-8") as destination:
        json.dump(report, destination, indent=2)
        destination.write("\n")
    print(f"Saved: {output}")
    print(f"Result: {report['result']}")
    print(report["nextStep"])
    return 2 if report["result"] == "target_not_confirmed" else 0


if __name__ == "__main__":
    raise SystemExit(main())
