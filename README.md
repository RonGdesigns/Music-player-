# Spindle

An offline music player for Android, built around the thing Samsung Music
removed: **a home-screen widget that shows the now-playing queue and lets you
reach into it.**

Everything is local by default. No account, no sync — the
app has no internet permission at all. Your library, your play counts and your
playlists never leave the device.

---

## The widget

The reason this exists. It resizes through four real layouts rather than one
layout that squashes:

| Size | What it shows |
|---|---|
| ~2×1 | Art, title, artist, prev / play / next |
| ~4×2 | Adds cover, progress, shuffle and repeat, favorite |
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
- Long-press any track for play next, add to queue, favorite, add to a
  playlist, and jump to its album or artist
- **Share to Spindle** — send audio to Spindle from a browser, a file manager,
  or anything else holding a file, and it imports and offers a playlist. The
  shortest path from "I have the file" to "it's in my library, named right".
- **Include audio outside the Music folder** (Settings → Library). Android only
  marks a file as music when its scanner decides to, and audio that lands in
  `Download/` usually misses out — meaning it never appears in any music player.
  This setting includes anything that isn't a ringtone, alarm or notification.
- **Import audio files** you already have: the picker copies them into
  `Music/Spindle` through MediaStore, so they become real library files —
  indexed, visible to every other player, and still there after a reinstall —
  and you can drop the whole batch straight into a playlist. Needs Android 10 or
  newer; doing it on older versions would mean holding `WRITE_EXTERNAL_STORAGE`,
  a whole-device permission every user would then be asked for.
- **Edit song details** — title, artist, album, year, track number — from a
  long-press or from the song-info sheet. These are saved as overrides rather
  than written into the audio file: rewriting tags needs a tag-writing library,
  per-file write consent from Android 10 on, and carries a real risk of damaging
  a file you cannot replace. An override is undoable, needs no permission and
  survives a rescan. Only the fields you actually change are stored, so fixing
  the artist does not freeze the title against a future retag.
- **Share a track** — the long-press menu, the player, and a multi-select
  selection all hand the audio file itself to the Android share sheet, so it
  goes to messages or anywhere else exactly as it would from Samsung's player.
  Nothing is copied: the share passes MediaStore's own URI with a read grant,
  and the receiving app reads the original file.
- **Select several** — from a long press, pick a run of tracks and queue, share
  or add the lot to a playlist in one go.
- **Album artist** — read from the file where it exists, so a compilation is one
  entry in Artists rather than forty, and an album with a guest feature on two
  tracks stays one album.
- **Embedded cover art** — MediaStore only hands out art it decided to extract,
  and for files that arrived as downloads it very often did not. Spindle reads
  the art out of the file itself (ID3 `APIC`, FLAC picture blocks, MP4 `covr`)
  and serves it through a small content provider, which is what lets the lists,
  the player, the widget, the notification and Android Auto all show it without
  any of them knowing where it came from. Extraction is lazy, cached, and
  rate-limited to three files at a time; a file with no art is written down as
  such on disk, so a library of art-less downloads pays that cost once ever
  rather than on every scroll.
- **Fast-scroll rail** down the right edge of Songs, Artists and Folders. It is
  keyed off whichever field the list is sorted by, so it shows A–Z for the
  alphabetical sorts and the scale that sort actually runs on otherwise: years
  for date added, count bands for plays, minute bands for length. Drag it for a
  haptic tick per stop and a large readout; the list still scrolls normally,
  because a 14dp rail label can never be the only way to reach a row.

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
| **Favorites** | Everything you marked |
| **Never Played** | In the library, never once heard |
| **Forgotten Favorites** | High count, silent for three months |

Each is a live query, so they are correct the moment a track finishes.

**Lyrics**

Resolved in priority order:
1. Lyrics you typed or pasted
2. A `.lrc` file sitting beside the audio file (synced, with timestamps)
3. The file's own tags — ID3v2 `USLT`/`SYLT` for MP3, Vorbis comments for FLAC,
   the iTunes lyrics atom for M4A
4. **An online lookup, off by default.** When switched on, a track with no
   lyrics of its own is looked up in LRCLIB, an open database with no account
   and no key. What leaves the device is that track's title, artist and length —
   nothing else — and whatever comes back is saved on the phone, so a track is
   only ever looked up once. A definite "no lyrics for this" is remembered too;
   a network failure is not, because one busy moment on someone else's server
   should not permanently deny a track its lyrics.

   Matching is strict about length. A live cut, a radio edit and an extended mix
   are all the same title by the same artist, and duration is the only thing
   that separates them — synced lyrics from the wrong take look correct and then
   drift, which is worse than showing none.

Synced lyrics highlight the current line and each line is tappable to seek.

**Timing correction.** Downloaded LRC is routinely a fraction of a second out
against a particular encode, and being consistently early is more distracting
than having no lyrics at all. The lyrics pane has a nudge in tenths of a second;
the correction is saved per track, applies to lyrics from any source including
those inside the file, and tapping the readout resets it.

**Now playing**
- Cover art with a visualizer behind it (see below)
- Synced lyrics pane
- Full queue: reorder, remove, jump
- Song info sheet: file path (tap to copy), format, codec, bitrate, sample rate,
  channels, size, dates, and your play count against your library's ceiling
- Sleep timer, with a "let the current track finish" option

**Visualizer** — three modes:
- **Artwork colors** (default) — colors pulled from the cover art, drifting
  slowly across three depth planes. No permission, negligible battery.
- **Audio reactive** — genuinely driven by the signal. Android gates its
  audio-analysis API behind the microphone permission, so this mode has to ask
  for it; it is opt-in and explained at the point of asking. Nothing is
  recorded.
- **Off** — a still ground.

All three respect the system's "remove animations" setting.

**Listening stats**

Built from the same play-event log that makes Most Played work: total hours,
plays counted, how much of the library you have actually heard, current and
longest daily streak, plays per day over the last 30 days, plays by hour of the
day, and your top artists and tracks.

**Volume normalization (ReplayGain)**

Evens out a quiet album against a loud one using the ReplayGain values already
in your files — nothing is analyzed or re-encoded, and scanning loudness
ourselves would mean decoding every track end to end.

- **Match tracks** — every track against every other. Best on shuffle.
- **Match albums** — levels albums against each other but leaves the loud and
  quiet passages *within* an album alone. Best for anything mastered as one
  piece.
- A pre-amp on top, and automatic clipping prevention: a boost is cut back to
  whatever headroom the file's peak actually leaves.

Reads ID3v2 `TXXX` frames (MP3) and Vorbis comments (FLAC). M4A stores the same
values in `----` freeform atoms, which is not handled yet — those files play at
unity gain. Values are cached after the first play; Settings has a re-read
button for after a retagging session.

**Equalizer**

Nothing about an Android equalizer is fixed — five bands on one phone, ten on
another, a different gain range and a different set of presets on each — so the
screen is built from what your device actually reports rather than from a layout
that assumes. Channel faders per band, the device's own presets, bass boost and
a stereo widener, all attached to the player's own audio session, which is why
none of it needs a permission.

When a device preset is in use, Spindle shows no band positions at all. The
device keeps that curve to itself and never reports it back, so drawing faders
under a preset would be drawing a guess — tap **Custom** to take the bands over.
Some manufacturers reserve the effect for their own sound app and refuse it to
everyone else; where that happens the screen says so rather than presenting
controls that quietly do nothing.

**Library tools**

Three jobs a library assembled from downloads always needs, and that nobody ever
does one track at a time. All three are in Settings → Library tools.

- **Fix names** — reads the artist and title out of the filename for tracks that
  arrived with no usable tags, and shows how it read every one before anything
  changes. `(Official Video)` is stripped because it describes the upload;
  `(Live)`, `(Remix)`, `(Acoustic)`, `(Remastered 2011)` and `(feat. …)` are
  kept, because those name a different recording. Correctly tagged tracks are
  never touched, and what it writes is the same undoable override the edit sheet
  writes.
- **Edit several at once** — sets one artist, album or year across a whole
  selection, which is the only practical way to fix a compilation. A field left
  blank is left alone, so you can correct the album across tracks whose artists
  genuinely differ.
- **Find duplicates** — the same recording sitting in the library twice under
  two filenames or at two bitrates. The matching is deliberately strict: same
  artist, same title once upload noise is stripped, *and* a duration within two
  seconds. A remix, a live cut or a long edit differs on at least one of those
  and is never grouped, because a missed duplicate wastes a few megabytes while
  a false match offers to delete a song you wanted. The better encode is marked
  Keep, both copies' bitrate and size are on screen, and the deletion goes
  through Android's own consent dialog (Android 11 and up; below that the system
  has no such dialog and the delete may simply be refused).

**Backup and restore**

Play counts, favorites, playlists and your detail corrections, written to one
readable JSON file you hold. Cloud backup already covers the ordinary case; it
does not cover a factory reset with backup switched off, a move to a phone from
a different maker, or a reinstall after clearing data — and the play counts are
the entire reason Most Played means anything.

The file is keyed on the *recording* — artist, title and length rounded to the
second — not on MediaStore ids, because those ids do not survive a single one of
the events this exists for. A restore merges rather than overwrites: where a
track has been played on both phones the higher count wins, and a playlist whose
name is already taken arrives beside it rather than merging into it. The report
afterwards says exactly what matched and what did not.

**Android Auto**

Browse Most Played, Favorites, Recently Played and Recently Added — the
auto-curated lists first, because a car is exactly where you should not be
hunting for a specific album — then Songs, Albums, Artists and Folders, with
voice search. The same session drives it as everything else, so the queue you left on
the phone is the queue in the car.

**Sideloaded apps do not appear in Android Auto until you allow them**, and this
is the usual reason Spindle is missing when everything else looks right. Android
Auto only lists apps it got from the Play Store unless you tell it otherwise:

1. Open the Android Auto settings on the phone (Settings → Connected devices →
   Android Auto, or the standalone Android Auto app).
2. Scroll to the bottom and tap **Version** ten times to unlock developer mode.
3. Open the overflow menu → **Developer settings**.
4. Turn on **Unknown sources**.
5. Reconnect the car.

Until that is on, Android Auto sees Spindle as an ordinary phone app and offers
only "open it on the phone", which is exactly what it looks like when something
is broken.

**Playback**
- Media3 / ExoPlayer, gapless, with proper audio focus
- Pauses when headphones are unplugged
- Optional skip-silence
- Equalizer, bass boost and stereo widening (see above)
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
| `RECORD_AUDIO` | **Optional.** The only way Android exposes its audio-analysis API for the reactive visualizer | Only if you turn that mode on |

There is no `INTERNET` permission.

## How it is put together

```
data/
  media/      MediaStore scanning, ReplayGain tags, file import
  db/         Room: play statistics, favorites, playlists, lyric overrides
  lyrics/     LRC parser, ID3/Vorbis/MP4 tag readers
  repo/       Library, statistics, collections, smart playlists
  settings/   DataStore preferences
playback/
  PlaybackService     Media3 MediaSessionService — the single owner of playback
  PlayCountTracker    Decides when a track counts as played
  PlaybackSnapshot    Disk-backed playback state; the widget's data source
  Normalization       The ReplayGain arithmetic, kept free of the audio stack
  LoudnessController  Applies it to the running player
widget/
  NowPlayingWidget  Glance, four responsive layouts
  WidgetActions     Buttons act via a short-lived MediaController
ui/
  theme/      Palette, typography, motion, spacing
  library/    Library tabs and track lists
  player/     Now playing, lyrics, queue, song info, visualizer
  stats/      Listening stats and its charts
  settings/
```

The library lives in memory — a few thousand `Track` objects is a handful of
megabytes, and it means grouping, searching and sorting are plain collection
operations rather than a query layer.

There is no dependency-injection framework. The graph is eight
application-scoped singletons in `SpindleApp`, which is what a service locator
is for.

## Design

The register is a 1970s hi-fi separate: an anodized faceplate, engraved scales,
and a warm lamp behind the meter that tells you the thing is live.

- **Ground** — blue-black graphite, never pure `#000`
- **Lamp** — sodium amber, reserved for "this is live". Nothing decorative
  wears it
- **Steel** — cool gray-blue for structure and secondary text
- **Artwork** — a fourth color supplied at runtime by whatever is playing

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
- The big bleeding header on an album or playlist screen appears only when
  MediaStore has the art. Whether to draw it is a layout decision that has to be
  made before any image is read, and embedded art cannot be found without
  opening the file. Rows, the player, the widget and the car all show it.
- ReplayGain is read from tags, not measured. A library that has never been
  scanned by a tagger gets no normalization.
- No MP4/M4A ReplayGain yet (see above).
- The fast-scroll rail thins its stops to what fits a phone's height, so on a
  library spanning many years the date-added rail lands a few rows off rather
  than exactly.
- Editing song details changes how Spindle shows a track, not the tags inside
  the file. Other apps will still see the original metadata.
- Importing requires Android 10 or newer (see above).
- Android Auto will not list a sideloaded build until Unknown sources is
  enabled in its developer settings (see above). Nothing in the app can work
  around that.
- Deleting duplicate files uses Android's consent dialog, which exists from
  Android 11. On older versions the delete is attempted directly and the system
  may refuse it; the screen says so rather than pretending it worked.
- The equalizer cannot show the curve behind a device preset, because Android
  does not expose it.
- A backup restores history for tracks that are on the phone. Entries it cannot
  match are left in the file untouched — restore again once those files are back
  and it will find them.
- Two features reach the network, and only those: the link converter, and the
  optional lyrics lookup. Your library, play counts, playlists and listening
  history are never sent anywhere by either of them. Turn the lyrics lookup off
  in Settings and the only thing left that uses the network is a conversion you
  started yourself.

## License

Not yet chosen. Bundled typefaces are SIL OFL 1.1.
