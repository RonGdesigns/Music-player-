# Spindle

An offline music player for Android, built around the thing Samsung Music
removed: **a home-screen widget that shows the now-playing queue and lets you
reach into it.**

Everything is local. No account, no sync, no network calls of any kind — the
app has no internet permission at all. Your library, your play counts and your
playlists never leave the device.

---

## The widget

The reason this exists. It resizes through four real layouts rather than one
layout that squashes:

| Size | What it shows |
|---|---|
| ~2×1 | Art, title, artist, prev / play / next |
| ~4×2 | Adds cover, progress, shuffle and repeat, favourite |
| ~4×3 | Adds the queue: tap any upcoming track to jump straight to it |
| ~4×5 | Same, with a long queue visible at once |

The queue rows are the point. The currently playing row is lit; the rest are
not. Tapping one seeks to it immediately, without opening the app.

It survives process death. The queue is persisted on every playback change, so
the play button on the widget works even if the app has not been in memory for
a day — the service restores the queue, paused and at the saved position, and
starts from there.

## Features

**Library**
- Reads whatever is on the device via MediaStore — no `MANAGE_EXTERNAL_STORAGE`
- Songs, Albums, Artists, Folders, hand-built playlists
- Search across title, artist and album
- A minimum track length, so interludes and voice memos stay out

**Play counts and automatic playlists**

A play counts once you have heard **half the track or four minutes, whichever
comes first** — the scrobbling rule. Counting on *start* would make "most
played" a list of what you skip past. Time is accumulated from actual playing
wall-clock, so a track paused on screen for an hour earns nothing.

From that, seven playlists nobody maintains:

| | |
|---|---|
| **Most Played** | Top N by count. Size is configurable (default 100) |
| **On Repeat** | Most played in the last 30 days |
| **Recently Played** | In the order you last heard them |
| **Recently Added** | New arrivals |
| **Favourites** | Everything you marked |
| **Never Played** | In the library, never once heard |
| **Forgotten Favourites** | High count, silent for three months |

Each is a live query, so they are correct the moment a track finishes.

**Lyrics**

Read from local sources only, in priority order:
1. Lyrics you typed or pasted
2. A `.lrc` file sitting beside the audio file (synced, with timestamps)
3. The file's own tags — ID3v2 `USLT`/`SYLT` for MP3, Vorbis comments for FLAC,
   the iTunes lyrics atom for M4A

Synced lyrics highlight the current line and each line is tappable to seek.
Nothing is fetched online, so nothing about what you play is sent anywhere.

**Now playing**
- Cover art with a visualiser behind it (see below)
- Synced lyrics pane
- Full queue: reorder, remove, jump
- Song info sheet: file path (tap to copy), format, codec, bitrate, sample rate,
  channels, size, dates, and your play count against your library's ceiling
- Sleep timer, with a "let the current track finish" option

**Visualiser** — three modes:
- **Artwork colours** (default) — colours pulled from the cover art, drifting
  slowly across three depth planes. No permission, negligible battery.
- **Audio reactive** — genuinely driven by the signal. Android gates its
  audio-analysis API behind the microphone permission, so this mode has to ask
  for it; it is opt-in and explained at the point of asking. Nothing is
  recorded.
- **Off** — a still ground.

All three respect the system's "remove animations" setting.

**Playback**
- Media3 / ExoPlayer, gapless, with proper audio focus
- Pauses when headphones are unplugged
- Optional skip-silence
- One session shared by the app, the notification, the widget, Bluetooth
  remotes and Android Auto — there is never a second queue to keep in sync

## Getting the APK

Every push builds a debug APK in GitHub Actions.

1. Open the repo's **Actions** tab
2. Click the most recent **Build APK** run for your branch
3. Download the **spindle-debug-apk** artifact
4. Unzip and install `app-debug.apk` on your phone (you will need to allow
   installs from your browser or file manager)

The debug build uses the application ID `com.irondigital.spindle.debug`, so it
installs alongside a release build rather than replacing it.

### Building locally

Android Studio: open the project and run. From the command line:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew lintDebug            # Android lint
```

Requires JDK 17 and the Android SDK (compileSdk 35). Minimum device API is 26
(Android 8.0).

## Permissions

| Permission | Why | When |
|---|---|---|
| `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (≤32) | Reading your audio files. The app is useless without it | At first launch, with an explanation |
| `POST_NOTIFICATIONS` | The playback notification and its controls | After the library works, not before |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Playing while the app is in the background | Automatic |
| `RECORD_AUDIO` | **Optional.** The only way Android exposes its audio-analysis API for the reactive visualiser | Only if you turn that mode on |

There is no `INTERNET` permission.

## How it is put together

```
data/
  media/      MediaStore scanning
  db/         Room: play statistics, favourites, playlists, lyric overrides
  lyrics/     LRC parser, ID3/Vorbis/MP4 tag readers
  repo/       Library, statistics, collections, smart playlists
  settings/   DataStore preferences
playback/
  PlaybackService   Media3 MediaSessionService — the single owner of playback
  PlayCountTracker  Decides when a track counts as played
  PlaybackSnapshot  Disk-backed playback state; the widget's data source
widget/
  NowPlayingWidget  Glance, four responsive layouts
  WidgetActions     Buttons act via a short-lived MediaController
ui/
  theme/      Palette, typography, motion, spacing
  library/    Library tabs and track lists
  player/     Now playing, lyrics, queue, song info, visualiser
  settings/
```

The library lives in memory — a few thousand `Track` objects is a handful of
megabytes, and it means grouping, searching and sorting are plain collection
operations rather than a query layer.

There is no dependency-injection framework. The graph is eight
application-scoped singletons in `SpindleApp`, which is what a service locator
is for.

## Design

The register is a 1970s hi-fi separate: an anodised faceplate, engraved scales,
and a warm lamp behind the meter that tells you the thing is live.

- **Ground** — blue-black graphite, never pure `#000`
- **Lamp** — sodium amber, reserved for "this is live". Nothing decorative
  wears it
- **Steel** — cool grey-blue for structure and secondary text
- **Artwork** — a fourth colour supplied at runtime by whatever is playing

One interaction signature throughout: anything becoming live warms to amber
over 180 ms and cools over 260 ms, slower off than on, the way a filament
behaves. The play button, the active queue row, the selected tab, the current
lyric line — all the same gesture.

Typefaces are Barlow and IBM Plex Mono, both SIL Open Font License, bundled
rather than fetched. The mono is used only for data — times, counts, bitrates —
where it makes real columns down a list.

Contrast ratios against the ground, computed from the sRGB values: primary ink
16.1:1, lamp 10.5:1, steel 8.1:1, dim steel 5.5:1. All clear WCAG AA for body
text. **Verify on a real device before shipping** — a computed ratio is not a
measured one.

## Known limits

- The widget's progress bar shows the position as of the last playback event
  rather than ticking every second. A per-second widget update costs a wakeup
  per second for a bar most people do not watch.
- Queue reordering uses explicit up/down buttons rather than drag-and-drop.
  Buttons work with a screen reader and with one thumb; drag does not.
- No embedded-artwork extraction for files whose album art is not in
  MediaStore's album-art provider.
- Not yet tested on a physical device — it compiles, lints and unit-tests
  clean, but the playback path, the widget and the visualiser want real
  hardware.

## Licence

Not yet chosen. Bundled typefaces are SIL OFL 1.1.
