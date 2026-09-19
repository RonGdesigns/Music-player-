# Widget regression repair

September 19, 2026.

The previous feature pass tested an in-app widget preview but did not render the actual launcher widget. That gap allowed clipping to ship.

## Reproduction

The API 37 launcher displayed a one-row Spindle widget at roughly 356 by 88 dp. At font scale 1.6, the title and artist consumed the height and the play button was flattened vertically. Playback responded, but the control was visibly clipped. The widget metadata allowed 180 by 70 dp while the smallest Glance responsive candidate was 250 by 96 dp. Glance selected that oversized candidate when no candidate fit. The artwork layout also used a percentage of the total height without reserving scaled text and transport space.

## Repair

Use the actual host dimensions with `SizeMode.Exact`. Reserve scaled text and transport space before adding artwork, progress, or queue rows. Short widgets use a single transport row; wider hosts get full 48-dp controls. Queue layouts may drop the progress readout to preserve useful, readable queue rows. Artwork falls back to the compact header when there is insufficient room. Configuration changes refresh active widgets and update their font-scale calculation. The artwork and title also regain their tap-to-open action, which was missing from the artwork style.

The sizing behavior follows [Android’s Glance sizing guidance](https://developer.android.com/develop/ui/compose/glance/build-ui?hl=en).

## Verification

Layout boundary tests cover 70, 88, and 96 dp heights; font scales 1.0 through 2.0; short artwork fallback; queue priority; and tall artwork budgets. A dedicated device test operates the installed launcher widget and checks the full play target plus Play, Next, and Pause against the real service.

The launcher regression test passed on API 37 with a 356-by-88-dp widget and font scale 1.6. Its full play target was at least 47 dp high (48 dp nominal), and the test exercised Play, Next, and Pause. Manual checks confirmed the widget icon changes to Play when the Android media session is PAUSED, artwork opens Spindle, resizing restores the expanded layout, and tapping River Walk in the queue starts River Walk and updates the widget.

The final polish changes off-state shuffle/repeat controls from decorative engraving to Steel.Dim. Against the widget's Plate background, this control color measures 5.00:1; active amber remains distinct. Existing artwork placeholders remain decorative.

Local build and lint pass with 196 unit tests, zero lint errors, 73 warnings, and two informational findings. Vendor launchers and physical audio hardware remain outside this emulator run.

## Launcher evidence

- [Before: clipped short widget at large text](widget-review/before-short.png)
- [After: full transport at the same size](widget-review/after-short.png)
- [Expanded artwork](widget-review/after-artwork.png)
- [Queue row starts the selected track](widget-review/after-queue-jump.png)

