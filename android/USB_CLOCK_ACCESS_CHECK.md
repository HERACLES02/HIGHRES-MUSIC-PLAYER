# First check: can the phone expose its USB driver diagnostics?

## Phone result: access blocked; do not repeat

Reviewed `releases/usb-clock/usb-clock-20261106T210726Z-103b2958.json`, captured
6 November 2026 at 21:07:26 UTC (7 November in Dhaka).
ADB successfully read model CPH1937 and API 30. The audio diagnostic reads returned
explicit permission denials:

- `/proc/asound/cards`: permission denied (both metadata lookup and content read).
- `/proc/asound/pcm`: permission denied.
- `/proc/sys/kernel/osrelease`: permission denied.
- `/proc/asound` directory metadata was visible, but no USB stream file was
  visible through the glob. That is inconclusive, particularly with the DAC
  disconnected; it is not evidence that streams or feedback are absent.

The collector worked: it retained the actual failures and did not report success.
This preflight does not establish access for a live snapshot. Do not set up
wireless ADB, reconnect the M2 for a rerun, or request root to repeat these reads.
The source-only next path is a verified driver/firmware match; direct render-clock
and analog balance evidence still requires suitable external measurement. An
initial targeted public-source search did not identify a verified kernel match
for the recorded CPH1937_11_F.17 build. Do not substitute another OPPO/Qualcomm
kernel and claim it describes this phone.

The instructions below are the completed preflight's record, not a new request.

This is an access preflight, not another listening test. It prepares the next
clocking investigation: whether Android's kernel exposes its active USB audio
stream and any synchronization endpoint. No USB control writes, audio playback,
root access, wireless-debugging setup, installs or phone settings changes are
performed by the collector. Only the resulting JSON is written, on the PC.

1. Stop Flick. Unplug the M2. Connect the OPPO phone to the PC using its USB cable.
2. Enable USB debugging in Developer options if it is not already enabled. Unlock
   the phone and accept this PC's USB-debugging authorization prompt if shown.
3. Run this in PowerShell:

```powershell
Set-Location D:\music_player
python android\scripts\collect-usb-clock.py
```

The collector uses the existing project ADB on port 5037 and selects one physical
USB phone with `-d`, excluding the separate emulator. It checks for model CPH1937
before reading audio diagnostics. Multiple phones, denied authorization, missing
targets and unexpected models stop the audio queries. It never collects the
phone serial, personal media, accounts, general logcat or a complete bugreport.
ADB may start its normal local server. It does not change the emulator server
on port 5038 or enable a network debugging connection.

4. Send the generated `usb-clock-....json` from `android\releases\usb-clock`.
   That folder is Git-ignored. No sound needs to play and the M2 need not be
   attached during this first check.

`cards_readable` means that the card list was readable, not that the USB stream
files are accessible or a clocking gate passed. The M2 is expected to be absent
while the phone uses its only USB port to connect to the PC. Permission failures,
missing files and timeouts are retained; they are not reinterpreted as evidence
that a feedback path is absent.

If the relevant stream diagnostics are accessible, the next step is to arrange
one short live snapshot while the M2 is attached and Normal playback is active.
That requires an existing wireless ADB connection or another separately prepared
collection method because the DAC and PC cannot both occupy the same phone port.
Do not enable wireless ADB or run new listening comparisons for this preflight.
If diagnostics are blocked, stop here; no root or repeated endurance run follows.

The [Linux USB-audio proc implementation](https://github.com/torvalds/linux/blob/v4.14/sound/usb/proc.c)
is a reference for stream diagnostics, not proof of the OPPO kernel's configuration.
Any reported momentary frequency describes the driver's timing estimate; it is
not an independent measurement of the physical DAC clock. A missing synchronization
field is inconclusive without checking the device's driver version/implementation.
See the [current evidence table](DIRECT_USB_INVESTIGATION_2026_11_07.md).

Local verification covers Python syntax, CLI help and simulated ADB responses,
including permission denial, target rejection and unavailable stream files.
No personal phone was contacted during implementation. The subsequent user-run
preflight above establishes permission denial, not audio diagnostic accessibility.
Diagnostic/player application source and APKs are unchanged.
