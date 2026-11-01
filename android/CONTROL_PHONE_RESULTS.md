# M2 control-write results reviewed 2 November 2026

Source: `../m2-controls-test.json`, preserved unchanged. Captured
1 November at 20:11:08 UTC / 2 November at 02:11:08 in Dhaka.
App 0.4.0-controls; OPPO CPH1937, Android 11, CPH1937_11_F.17; user-confirmed
Standard physical preset. The report contains 114 operations.

## Verified report observations

Both channels began at 0 dB, with -50 to 0 dB ranges and 0.5 dB resolution.
Each channel accepted a -40 dB SET_CUR and returned the exact raw value -10240
on GET_CUR. The initial low-level verification passed.

| Alternate / advertised stereo depth | 44.1 kHz | 48 kHz | 88.2 kHz | 96 kHz |
| --- | --- | --- | --- | --- |
| 1 / 16-bit | Exact SET/readback | Exact SET/readback | Exact SET/readback | Exact SET/readback |
| 2 / packed 24-bit | Exact SET/readback | Exact SET/readback | Exact SET/readback | Exact SET/readback |

Both alternate selections read back exactly. All 20 subsequent channel-volume
checks (both channels after each alternate change and after each of the eight
rate changes) returned exactly -40 dB. No tested format change reset the reported
volume to a louder value in this run.

The original idle endpoint rate in this report was **96,000 Hz**, not 48,000 Hz.
Cleanup restored and verified that original rate, alternate 0, and both original
0 dB channel levels, while no audio transfers were being sent. Both interface
releases succeeded. AudioControl and playback kernel drivers each reported active
after release. `settingsRestored`, `lowVolumeControlVerified` and
`configurationMatrixPassed` are all true.

The only eight negative results are the expected unclaimed volume GETs before
AudioControl ownership. They again report LIBUSB_ERROR_IO with supplementary
errno 16 (EBUSY). There are no failed claimed control tests or cleanup operations.
Supplementary errno 11 on successful requests does not invalidate the exact
successful libusb transfer results.

## Meaning and limits

The hardware-volume write/readback mechanism and all eight advertised playback
configurations now have physical **control-plane** evidence on this phone/cable.
These results remove the outstanding uncertainty about whether those standard
USB controls can be applied and read back under native ownership.

No audio was submitted. Configured rates and readable hardware volume are not
measurements of acoustic attenuation, sample delivery, correct playback speed,
device-internal processing or sustained transport stability. The report still
correctly marks clocking unvalidated and does not arm a safe audio route for a
future session. Every future audio start must set/check low hardware volume
again, including after format changes.

Restoration was verified before Android driver reattachment. Driver-active
status does not independently test restored audible Android playback, microphone
or buttons. Android may reconfigure device controls afterward. No observation
about those user-facing behaviors was included in this JSON.

## Next gate

The next implementation is the direct-audio streaming prototype, rather than
another repeat of this silent control matrix. It must retain these runtime volume
guards and cleanup, establish a justified clocking arrangement for the descriptor's
asynchronous OUT endpoint without explicit feedback, and implement bounded native
isochronous transfers with cancellation and diagnostics. Microphone capture must
not be used for timing, and nominal fixed packet pacing must not silently become
a validated clocking claim.

Exact PCM packing/submission comparisons, correct-speed/channel listening,
all-format streaming, detach/reconfigure tests and the minimum 60-minute 24/96
screen-off run remain outstanding. Stage A is not complete; the full player stays
behind its accepted feasibility/validation gate. No new APK or code was produced
while reviewing this report, and no repeat of this control test is needed now.
