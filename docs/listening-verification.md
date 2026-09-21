# Listening workspace verification

September 19, 2026. This release builds on the player-reliability branch and includes the complete agreed feature set.

## Implemented scope

| Area | Behavior |
|---|---|
| Home and navigation | Continue listening, pins, sessions, recently added albums, custom mixes; Home/Library/Search destinations |
| Now Playing | Balanced cover and title, typographic fallback, explicit listening tools |
| Queue | More vertical space, compact transport, long-press drag handles, accessible move/remove menu, Save session |
| Wide windows | Library and player shown side by side at 840 dp and above |
| Sessions | Named queues, rename/remove/resume, exact position and playback modes, active session updates, missing-file handling |
| Pins | Albums, user playlists, and folders on Home; non-destructive unpin |
| Smart playlists | Intersecting favorite, recency, unplayed-days, and length rules; live match count and sort order |
| Bookmarks | Named per-track positions, play from bookmark, remove, missing-file state |
| A–B repeat | Validated bounds, service-owned looping, clear on track change |
| Custom covers | Owned image copy, bounded decode, track-over-album precedence, reset to original, shared content provider |
| Widgets | Artwork or queue layout, two density choices, preview and explicit Apply, global preference |

## Automated coverage

Local JDK 17 validation: `assembleDebug`, `testDebugUnitTest`, `assembleDebugAndroidTest`, and `lintDebug`. The suite has 192 passing unit/Robolectric tests and three passing API 37 device tests. Lint completes with zero errors, 73 warnings, and two informational findings; this is not a warning-free baseline.

The unit/Robolectric suite covers the existing repair regressions plus listening-data round trips, unsupported/corrupt data rejection, atomic concurrent edits, rule boundary conditions and ordering, duplicate-preserving session filtering, loop validity/ownership, artwork persistence, and invalid-image rejection.

Robolectric was configured to reject invalid image data for these tests; its permissive default initially masked the invalid-image path. The production path uses Android’s decoder.

The device suite exercises a real MediaController/PlaybackService with three synthetic WAV tracks: named-session save and resume, shuffle/repeat retention, A–B loop behavior, native navigation, smart-rule dialog, widget preferences, an accessible Apply control, and serving a copied image after deleting its source.

## Rendered review and corrections

The design studies and selection are recorded in [listening-design.md](listening-design.md). Phone review includes 360 dp width with font scale 1.3 and animations disabled. A navigation-test failure exposed hidden tool tabs at that width; the tool destinations now wrap. The review also removed a clipped brand label from small artwork fallbacks and corrected default dialog surfaces and form outlines to the project palette.

The mechanical design scanner reports zero blocks and zero review warnings for the new UI and changed player surfaces. It is not a native accessibility certification. A targeted dialect scan supplements the unavailable global dialect tool; stats dates were changed to American ordering.

Rendered review also exercised a long-press drag from the first queue position to the third, then Move up through the menu. Both produced the expected order. A bookmark was saved from the Listening tools dialog. A 1600-by-1067-dp tablet window showed both library and player panes without overlapping controls.

Sampled screenshot colors measured queue secondary text at 5.26:1 against its local background; rule-dialog secondary text at 6.81:1 and field outlines at 5.00:1. These are sampled combinations, not a claim about every artwork-derived background.

The review found and corrected crowded widget-preview text (now playback icons), default selected-chip colors, and hidden Home controls remaining in the full-screen player accessibility tree. The underlying destination is now disposed while the player is open, retaining its saveable state. On the final APK, Tab focused the 48-dp Collapse player control, Enter returned to Home, and the player hierarchy contained no hidden Home controls.

## Screenshots

- [Phone Home, large text](listening-review/phone-home-large-text.png)
- [Queue, large text](listening-review/phone-queue-large-text.png)
- [Smart playlist rules, large text](listening-review/smart-rules-large-text.png)
- [Player and keyboard focus](listening-review/phone-player.png)
- [Widget controls, large text](listening-review/widget-controls-large-text.png)
- [Tablet workspace](listening-review/tablet-workspace.png)

## Limits

- A physical phone, Bluetooth equipment, and an Android Auto head unit were not available. Emulator checks do not certify device-specific audio behavior.
- The widget settings preview and persisted preference were tested. Actual launcher widget rendering across vendor launchers remains unverified.
- Full TalkBack traversal and switch-access testing remain outside the automated accessibility smoke checks.
- New listening data and custom-cover files are local to this installation. The existing library JSON export does not yet include them for cross-phone transfer; the UI does not promise otherwise.
- The canonical design-system files were unavailable, so the existing project identity and the recorded studies governed this pass.
- On final validation, a System UI ANR dialog blocked the Home selector. Playback and artwork tests passed; the navigation test passed on retry after dismissing that system dialog.
- Running Gradle and the API 37 emulator together exhausted available host memory and caused Android system/launcher ANRs. Build and emulator checks were separated for verification; no unrelated user process was stopped.
