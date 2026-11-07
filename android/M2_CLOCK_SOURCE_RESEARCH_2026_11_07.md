# M2 published-source research, 7 November 2026

The user confirms no recording equipment is available. Keep acoustic balance
unresolved; do not request another subjective comparison as a substitute for a
measurement. Phone driver diagnostics are permission-blocked, and the Flick
comparison is suspended after locked volume and loud output. The remaining
source investigation needs evidence for the exact `31b2:0311` hardware/firmware.

## Findings and their limits

| Source inspected | What it establishes | What it does not establish |
| --- | --- | --- |
| [KZ M2 product page](https://kz-audio.com/kz-m2.html) | KZ describes an integrated DAC and three selectable EQ modes. | The inspected page supplies no chip part number, render-clock topology, feedback protocol or assurance that Standard bypasses every DSP stage. |
| [KTMicro USB product catalogue](https://www.ktmicro.com/?usb_104/=) | Multiple distinct chip families support USB audio, including full-speed/UAC1 parts. | Vendor ID or a common 96-kHz/full-speed capability is insufficient to identify the M2 die or firmware. |
| [KTMicro KT0211L page](https://www.ktmicro.com/?usb_104/485.html=) and [KT02F20 page](https://www.ktmicro.com/?usb_104/393.html=) | The vendor describes integrated oscillators and DSP functions on those parts; their configurations can be customized. | An integrated oscillator does not reveal its relationship to USB SOF, clock recovery or FIFO behavior. Neither page identifies the M2 as that part or documents the M2's synchronization mechanism. |
| [Community chip field guide](https://github.com/eyad-elghareeb/kt0231h/blob/master/CHIPS.md) | The author distinguishes several KTMicro families and reports differing IDs/register layouts. | Its listed 0111/1132/1112 devices are not an exact match to our recorded 0311. Do not import a chip identification from another product. |
| [Community protocol research](https://github.com/eyad-elghareeb/kt0231h/blob/master/PROTOCOL.md) | Documents HID-based control on other KTMicro devices, with chip-dependent gain/EQ maps. | No usable M2 render-clock error, buffer-health or feedback source was established. Standard UAC volume equality does not measure unobserved DSP/analog stages. Unknown register accesses are not justified. |
| [Linux KT0206 patch discussion](https://patchew.org/linux/SG2PR02MB58780ED138433086A3213AE98A1B9@SG2PR02MB5878.apcprd02.prod.outlook.com/) | The proposed change adds vendor-specific USB driver matching for `31b2:0011`. | It is a different product ID and a driver-binding change, not an M2 clock-synchronization fix. |

These are bounded observations from the cited pages, not a claim that all public
documentation or all project history has been exhausted. Community reverse
engineering is a research lead, not manufacturer validation for our cable.
No firmware, vendor application or WebHID controller was executed; no HID report,
register write, flash operation or external message was sent. No new clock signal
or evidence-backed transport correction was found in this increment.

## Next discriminating information

Prepared [manufacturer questions](M2_VENDOR_CLOCK_QUESTIONS.txt), unsent. They ask
for exact chip/firmware identity, the intended host synchronization method for
the recorded asynchronous OUT without associated feedback, and documented clock
or buffer telemetry. They also distinguish Standard EQ selection from an actual
DSP bypass. A manufacturer answer can justify an implementation path; marketing
capabilities and another successful transport run cannot supply the missing clock
error signal. Balance still requires suitable measurement evidence.

Direct remains gated in Local Music. Existing Normal playback and experimental
probe controls are unchanged. This increment contains source research and
documentation only; no APK rebuild or repeat of unchanged software suites was
needed. Checked local documentation links and whitespace. A future source change
must use regression tests appropriate to the supported mechanism and retain
physical validation limits.
