# Listening workspace design

September 19, 2026. Scope: all proposed visual and listening additions, preserving Spindle’s graphite/amber identity.

The visitor’s tasks are resume, browse, arrange, save, and return to local music. These are operational surfaces. The existing app’s palette, bundled type, and actual local-track metadata are the assets. Unknown artwork uses title initials, never fabricated album imagery.

Two compact studies compared the same test tracks and task states. Listening desk places a compact resume card above pins and sessions; Record sleeve gives artwork the Home hero. After rendered comparison, Listening desk was selected for Home and Queue because more user collections remain visible at phone width. Playing retains a balanced, bounded cover. The HTML studies are retained in `spindle-visual-studies.html`.

References: the [Braun SK 4/10 in the Met collection](https://www.metmuseum.org/art/collection/search/491975) informed separated controls and legible labels, retaining the existing palette rather than copying the object’s colors. [Android’s adaptive media guidance](https://developer.android.com/media/implement/surfaces/large_screens) informed library/player panes on wide windows. [Compose gesture guidance](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures) informed explicit drag handles with move-menu alternatives.

The canonical DESIGN_SYSTEM.md and DESIGN_LANGUAGE.md are unavailable on this machine. No archived constitution is substituted. This record carries the project-specific decisions and the rendered-review evidence will accompany verification.

Implementation decisions: Home/Library/Search navigation; compact resume; pinned collection shelf; session management; smaller transport beneath a full queue; typographic missing-art cover; all new actions use labeled Material controls and 48 dp or larger targets; widget layouts change by chosen style and available size; persistent local state uses atomic DataStore edits.
