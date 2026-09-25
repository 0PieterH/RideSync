# RideSync — Setup Guide

DAW-style audio timeline for NoLimits 2 coasters, with per-train playback driven
by track triggers, block states, a timeline clock, and an optional stop-triggered
fade-out ("extinction") state machine ported from OnboardAudio1.

## 0. Getting started

Requirements: Node.js 18+ and the **desktop app** — packaging, project files and
reading audio from disk all go through the Electron shell.

```bash
npm install
npm start            # run the editor
npm run dist         # build the Windows installer (NSIS)
npm run portable     # build a portable .exe
```

Editor at a glance:

- **Import Audio** (button, or drag `.wav`/`.ogg` files onto the timeline) —
  dropped files land at the drop position and track.
- **New** clears to a blank timeline. **More → Save/Open Project** stores the
  whole session **plus every referenced audio file** in one `.nl2audio` file
  (see §6b).
- **Settings** is tabbed: *Project*, *Block states*, *Extinction*, *Ducking* and
  *Audio distance* (with an interactive falloff visualiser you can drag).
- Zoom keeps the playhead in view (buttons or Ctrl/Cmd + wheel); drag the
  playhead in the ruler to scrub.
- Clips never overlap on a track. Regions support wait music, loop crossfade,
  follow actions and the special-event (restore) launcher.
- Closing the app with unsaved changes prompts **Save / Don't Save / Cancel**.

## 1. Architecture in 30 seconds

| Concept | Purpose | Launched by | File field |
|---|---|---|---|
| Clip | Editor-only DAW layout (**never played in-game**) | — | `CLIP` records |
| Trigger def | Named one-shot/loop audio | `audio_<name>` track trigger | `TRIGGER` records |
| Region | Event-driven single audio channel | Time, block state, track trigger, or special event (restore) | `REGION` records |
| Stop command | Region with **empty filename** | Any region launcher | fades out & stops all looping regions |
| Extinction | Auto fade-out on prolonged stop | Engine state machine | Settings menu |

- **One region = one sound.** To overlay several files from one event, create
  several regions all bound to the same launcher.
- Tracks and mute/solo are **editor-only** organization — the NL2 script mixes
  every clip/region independently regardless of track.

## 1b. What plays in-game: regions, not clips

Clips are the visual DAW layout you build in the editor (idle music at 0-20 s,
launch at 20 s, starter/station at the end). The script never starts clips.
In-game audio is driven **only by regions**, each launched by exactly what you
set in the editor:

- **Track trigger** (`audio_<name>`) — place the matching `audio_...` trigger
  on the coaster track. Loop regions (role `Loop`) only play while a trigger
  keeps them going; a stop command (`empty filename`) fades them out.
- **Block state** (e.g. block `Zone1` → state `4`) — fires when the block
  state changes to the target value. The transition only fires when **this
  train** (`TRAIN_INDEX`) is on that block's section — another train driving the
  same state change does not fire it. This is how the launch sequence starts in
  the RNRC pattern.
  - **Scripted coaster** (Settings, default on) uses scripted operation mode and
    the custom states from `Block.getState()` (the "Free / Approaching / …"
    list).
  - **Unscripted** (toggle off) uses the built-in normal-mode states from
    `Block.getNormalModeState(PROTOCOL_V1)` (Idle / Occupied / In station /
    Approaching FWD / Approaching BWD / Offline / Full manual) plus extra
    conditions read from the **Section** class (works without scripted operation
    mode): Train on section, Train before/behind center, before/behind brake
    trigger, before/behind lift trigger, Brakes on, Station wait-for-clear /
    wait-for-advance, Station gates open, Station platform up, Lift running,
    Transport running. So block-based regions and the extinction arm/restore work
    without a scripted coaster.
- **Time** (`Start`) — fires once when the playhead crosses the start time.
- **Special event** (launch by "Special event (audio restored)") — fires when the
  extinction system restores audio (the Action key in the station). Several
  special-event regions can fire on the same restore; it is independent of the
  single **Restore region** setting.

So a RNRC ride with a configured coaster looks like: `audio_station` triggers
start the idle loops while loading; block `Zone1`→state `4` stops the loops and
starts the launch/ride music; `audio_dispatch` triggers the starter. If a sound
doesn't fire, check that its launcher (trigger placed on the track, or the
block/state in a scripted op mode) actually fires.

### Container regions (multiple clips in one region)

A region with the special filename `__CLIPS__` is a **container**: on launch it
plays **every clip whose start lies inside its [Start, End) range**, at their
timeline offsets relative to the region start — so clips stacked at the same
offset play *layered*, and clips at later offsets play in *sequence*. No
per-clip regions needed. Pick **"Play timeline clips"** in the region panel —
this is the default for new regions.

- Loop containers wrap the playhead and replay the contained sequence.
- Once containers run the sequence once and stop.
- Volume/fade/role apply to the whole container (role Music is ducked by role
  Voice regions, just like a single-file region).
- Empty-filename regions still mean **stop command** (fade out all loops) and
  are separate from containers, so keep the stop command on a region of its own
  next to the container that starts at the same event.

## 2. File layout (all at the script classpath root)

```
MyRideScript/
├── TimelineAudio.nlvm          <- your script (renamed TimelineAudio.java)
├── TimelineAudio.nl2script     <- script description: declares every .wav/.pkf
│                                  as a resource (see below)
├── config/
│   └── timeline.bin            <- exported binary
├── rrc_idle1_st1.wav           <- loop files (ONBOARD recipe)
├── rrc_idle2_st1.wav
├── rrc_idle3_st1.wav
├── rrc_station_allst.wav
├── rrc_starter_st1.wav
├── rrc_wildline1.wav
├── rrc_wildline2.wav
├── rrc_wildline3.wav
└── rrc_launch_st1.wav
```

- Copy `TimelineAudio.java` and rename it to `TimelineAudio.nlvm` (file name must
  equal the class name).
- The script loads `config/timeline.bin`.
- **Export Package (.zip)** from the editor bundles `timeline.bin` plus all audio
  that was imported as clips, so import every file you reference in regions too
  (or copy them manually).

### Script description file (`.nl2script`) and resource-based audio

Because the timeline audio file names are stored in `timeline.bin` at runtime,
they can never be compile-time constants, so `StaticSound.loadFromFile` would
emit a "Argument should be static final constant String" package-manager warning
and the sounds would not be auto-detected for packaging. The script therefore
loads every sound through the **NL2 resource system**:

- Attach the script to the coaster via **`TimelineAudio.nl2script`** (not the
  `.nlvm` directly). The `.nl2script` lives beside the `.nlvm` and declares
  each `.wav`/`.pkf` as a `<resource>` with `id` = file name (see
  `TimelineAudio.nl2script` in the RideSync folder).
- The engine resolves each sound with `getResourcePathForId(fileName)` and loads
  it via `StaticSound.loadFromResource(...)`, which needs no constant path and
  therefore produces **no warning**.
- **Keep the resource list in sync**: any new `.wav` you reference in the editor
  must be added as a `<resource>` in the `.nl2script`, or it will not load.
- The editor can generate the `.nl2script` for you: click **Generate .nl2script**
  in the toolbar. It collects every file the timeline references (clips, regions,
  triggers, confirm beep, wait music) and writes a `TimelineAudio.nl2script`
  with each as a `<resource>`. Regenerate it whenever you add a new audio file.
- The `config/timeline.bin` `AudioDir` field is the editor's absolute path; the
  engine detects absolute paths and uses the bare file name as the resource id,
  so the resources are declared with bare file names.

## 3. OnboardAudio recipe (loops → trigger start, block-state stop)

Editor steps:

1. **Import Audio**: all files above (they sit end-to-end on track 1 — tracking
   is cosmetic).
2. **Loop regions** (3 regions, all on the same trigger):
   | Region | Launch by | Trigger | File | Mode | Fade | Vol |
   |---|---|---|---|---|---|---|
   | Loop1 | Track trigger | `audio_station` | `rrc_idle1_st1.wav` | Loop | 1.5 | 0.25 |
   | Loop2 | Track trigger | `audio_station` | `rrc_idle2_st1.wav` | Loop | 1.5 | 0.25 |
   | Loop3 | Track trigger | `audio_station` | `rrc_idle3_st1.wav` | Loop | 1.5 | 0.25 |
3. **One-shots** (Once mode):
   | Region | Launch by | Trigger | File | Fade |
   |---|---|---|---|---|
   | Station | Track trigger | `audio_station` | `rrc_station_allst.wav` | 0.8 |
   | Dispatch | Track trigger | `audio_dispatch` | `rrc_starter_st1.wav` | 0.5 |
   | Wildline 1 | Track trigger | `audio_wildline` | `rrc_wildline1.wav` | 0 |
   | Wildline 2 | Track trigger | `audio_wildline2` | `rrc_wildline2.wav` | 0 |
   | Wildline 3 | Track trigger | `audio_wildline3` | `rrc_wildline3.wav` | 0 |
   | Ride launch | Block state | block `Zone1`, state `4` | `rrc_launch_st1.wav` | 0.3 |
4. **Loop fade-out on launch** (like OnboardAudio's 6s duck):
   Block-state region → `Zone1`, state `4`, **empty filename**, fade `6.0`.
5. **Export .bin**.

NL2 setup:

- Track triggers named exactly: `audio_station`, `audio_dispatch`,
  `audio_wildline`, `audio_wildline2`, `audio_wildline3`.
- Blocks named `Zone1` (and `Zone7` if using the Action restore).
- Coaster in **scripted operation mode** (block states).
- Sound is scoped to **train 1** (`TRAIN_INDEX = 0` in TimelineAudio.java).

## 4. Extinction (stop-triggered fade-out) — Settings menu

Click **Settings** in the toolbar. Ported faithfully from OnboardAudio1 section 7:

| Setting | Default | Meaning |
|---|---|---|
| Enable extinction | off | Master switch for this timeline |
| Launch/arm block | `Zone1` | Block whose state transition arms the ride clock |
| Arm state | `4` | State value that arms (4 = behind launch trigger) |
| Stopped for | `15.0` s | How long the train must be stopped after launch |
| Fade-out duration | `3.5` s | Duration of the fade of all looping regions |
| Mute lock | on | While silent, everything stays muted until restored |
| Restore block | `Zone7` | Block where the Action key restores audio (station) |
| Restore state | `8` | State meaning "in station" |
| End-watch trigger | *(blank)* | Track trigger that marks the END of the extinction watch section |

Behavior (mirrors OnboardAudio1, with a configurable watch window):
- When the arm block transitions to the arm state and **this train** is on the
  section, the ride cycle arms and the extinction **watch** starts.
- The train is watched for stalls from that point until it crosses the
  **end-watch trigger** (if one is set). Once crossed, the watch ends and the
  train is no longer monitored for extinction for the rest of the ride.
- While watched, if the train stays below ~0.1 m/s for `stopped` seconds, all
  looping regions fade out over `fade` seconds.
- With **mute lock**, after the fade the ride audio is completely muted: trigger
  loops and one-shots are suppressed. The train must be stopped in the restore
  block (station, state 8) and the player presses the **Action key** near the
  rear (within ~4 m) to restore; that re-starts the trigger-launched looping
  regions. The end-watch trigger does **not** restore audio.
- Without mute lock, the fade-out simply stops the loops and the next station
  trigger restarts them normally.

Notes:
- Block polling uses scripted states when **Scripted coaster** is on, otherwise
  the built-in normal-mode states (see §1b). No scripted coaster is required.
- The setting lives in the timeline record and round-trips in the `.bin`.

## 5. Ducking, station wait music, confirm beep, disable-fade — Settings menu

The same **Settings** menu also carries the remaining OnboardAudio1 behaviors:

| Setting | Default | Meaning |
|---|---|---|
| Duck depth | `0.75` | Music (role 1) volume multiplier while a voice (role 2) plays |
| Duck fade-in | `0.35` s | Fade time for music ducking down when a voice starts |
| Duck release | `0.8` s | Fade time for music recovering after the voice ends |
| Confirm beep | *(blank)* | Sound played when Action restores audio (`1Kfull.wav`) |
| Disable-fade trigger | *(blank)* | Track trigger that turns off extinction fading for the ride |
| Audio ref. distance | `5.0` m | `setDistanceParameters` reference distance applied to every loaded sound |
| Audio rolloff | `1.0` | `setDistanceParameters` attenuation curve (0 = flat, higher = steeper) |

Region **Role** (per region, in the inspector):
- `Normal` (0) �?" no ducking behavior.
- `Music` (1) �?" ducked while any voice plays; also faded by wait music.
- `Voice` (2) �?" ducks all music regions for its playing length when it fires.

### Station wait music (per-region, replaces the old global "Zone 5")

The old *Zone 5 idle music* setting is gone from Settings. It is now **zone
neutral**: configure it on any **block-launch region** via the region
inspector (fields shown under the block section):

| Field | Default | Meaning |
|---|---|---|
| Wait music file | *(empty)* | Wait soundtrack for this region; **empty = feature off** |
| Waited for | `10` s | Stopped time before ride music fades + wait track starts |
| Wait fade-in | `2.5` s | Fade-in of the wait track |
| Wait fade-out | `10` s | Fade-out of the wait track on departure |
| Ride fade-out | `2` s | Fade-out of ride music when the wait track starts |
| Station state | `8` | Block state meaning "stopped/waiting" (informational) |

Behavior: when any role-2 region starts, music (role 1) regions duck to
`depth` over `attack`, then recover over `release`. Ducking is a **separate
multiplier** applied on top of each region's own fade gain, so a voice starting
or ending while a region fades in/out (or crossfades) never cancels that fade.
When a block region with a
wait file fires and the train stays below ~0.1 m/s for `waited` seconds, ride
music fades out over `rideFadeOut` and the wait file fades in over `fadeIn`
(looping); on departure the wait file fades out over `fadeOut`. Regions with a
wait file get a **WAIT** badge in the region list. This makes any block (e.g.
`Zone5`, a station block, or a mid-ride holding brake) able to play hold music.
`disableFadeTrigger` suppresses the extinction fade for the current ride cycle.

## 6. Porting OnboardAudio1 as a bin

`ridesync-timeline.bin` is generated verbatim from the channel
constants in `OnboardAudio1.java`. Every channel becomes one launcher-driven
region — **no clips**, because OnboardAudio has no timeline-based audio.

| OnboardAudio channel | Region | Launch by | File | Mode | Gain | Fade | Role |
|---|---|---|---|---|---|---|---|
| CH_STATION | Station | `audio_station` | `rrc_station_allst.wav` | Once | 0.90 | 0 | — |
| CH_LOOP1 | Loop1 | `audio_station` | `rrc_idle1_st1.wav` | Loop | 0.25 | 1.5 | — |
| CH_LOOP2 | Loop2 | `audio_station` | `rrc_idle2_st1.wav` | Loop | 0.25 | 1.5 | — |
| CH_LOOP3 | Loop3 | `audio_station` | `rrc_idle3_st1.wav` | Loop | 0.25 | 1.5 | — |
| CH_DISPATCH | Dispatch | `audio_dispatch` | `rrc_starter_st1.wav` | Once | 1.00 | 0 | — |
| CH_RIDE | Ride | block `Zone1` → state 4 | `rrc_launch_st1.wav` | Once | 1.00 | 0.3 | Music |
| CH_WILDLINE1 | Wildline1 | `audio_wildline` | `rrc_wildline1.wav` | Once | 1.00 | 0 | Voice |
| CH_WILDLINE2 | Wildline2 | `audio_wildline2` | `rrc_wildline2.wav` | Once | 1.00 | 0 | Voice |
| CH_WILDLINE3 | Wildline3 | `audio_wildline3` | `rrc_wildline3.wav` | Once | 1.00 | 0 | Voice |
| (loop fade-out) | StopLoops | block `Zone1` → state 4 | *(empty)* | Loop | — | 6.0 | — |

Settings footer (from OnboardAudio1 constants): extinction armed on `Zone1` →
state 4, 15 s stopped, 3.5 s fade, mute lock, restore on `Zone7` → state 8;
ducking 0.75 / 0.35 / 0.80; confirm `1Kfull.wav`; disable-fade
`audio_disable_fade`. The old Zone 5 footer fields are no longer written; wait
music is configured per block region instead.

Behavior wrinkles recorded for completeness:
- OnboardAudio ducks **only** the ride channel; the station loops are role 0
  here so wildlines don't duck them. The Ride region is role 1 so the wildlines
  duck it exactly as in the original.
- Extinction fades **all** currently-playing regions (once + loop), matching
  OnboardAudio fading `CH_RIDE` on a mid-ride halt. The StopLoops command stays
  loop-only.
- The station fade-out on dispatch (0.8 s) isn't representable — the editor has
  no per-region "fade out on stop", and a generic stop on `audio_dispatch`
  would wrongly stop the loops too. The confirm beep also plays at full gain
  (OnboardAudio uses 0.1).

## 6b. Project file (.nl2audio) — timeline + audio in one file

**Save Project… / Open Project…** (More menu) store the whole editor session as a
single file, Ableton-style "collect all and save": a zip containing
`project.json` (tracks, groups, clips, regions, triggers, extinction settings,
duration, audio folder) plus an `audio/` folder with **every referenced audio
file**. So a project is fully self-contained — no relinking needed.

- **Save Project…** gathers every audio file the timeline references (imported
  clip paths, or the linked audio folder) and packs them with the timeline. Any
  files it can't find are reported in the toast.
- **Open Project…** reads the zip, decodes the packed audio into buffers and
  restores the full state (clips, waveforms, regions, triggers, settings).
- Re-saving an opened project repacks the audio from memory, so it works even if
  the original files are gone.
- Unlike **Export Package** (which produces the in-game `timeline.bin` + `.nlvm`
  + `.nl2script`), the project file is purely for round-tripping inside the
  editor.
- Closing the app with unsaved changes prompts **Save / Don't Save / Cancel**.

## 7. Audio relinking (no blank clips after import)

Bins/texts store the **audio folder** a timeline was built against. Importing a
timeline in a fresh session restores the layout but clips start blank (no
waveform) because the audio isn't decoded yet. To bring the sound back:

- **Link Audio Folder** (toolbar) → pick the folder containing the audio
  files. The editor scans it (via the desktop app), resolves each
  clip/region/trigger name to a file, decodes the audio, and re-draws
  waveforms. Matching is **exact first, then fuzzy**: if the referenced name
  isn't present verbatim the closest file is used, so `9launch` still finds
  `9launch_v2.wav`, `RNRC_9launch.wav` or `9 Launch.wav`, and `wildline1`
  finds `wildline01.wav`. Matching is deliberately conservative — different
  takes are never confused (`wildline1` will not pick `wildline4`, `idle4a`
  will not pick `idle4b`). The toast reports how many resolved exactly vs by
  name, and lists anything unresolved. Fuzzy matches are remembered so
  **Export Package**/**Save Project** pack the matched file under the
  referenced name. The chosen folder is remembered in the timeline (binary
  header audio-dir field) and reused on the next import.
- **Import Timeline** auto-relinks against the saved audio folder when one is
  present.
- **Export Package (.zip)** changes the audio folder to wherever you saved the
  package, so the packaged timeline points at its own audio layout.

Format notes:
- Binary **v4** adds an audio-dir string at header offset 18 (2-byte length +
  UTF-8 bytes after the 24/18-byte base header). v1–v3 files still load (clips
  default to 120 s duration; audio dir empty). The Java engine reads the dir and
  uses it as an audio lookup prefix: if it is a relative subfolder (e.g.
  `Audio/`) the script loads every `rrc_*.wav` through `Audio/`; absolute
  drive/UNC paths are skipped (NL2's `StaticSound.loadFromFile` resolves paths
  relative to the script classpath only), so the bare classpath-root filenames are
  used then — exactly the pre-v4 behavior.
- Binary **v5** adds an optional per-region wait-music tail: each REGION record
  is followed by a 1-byte flag (1 = wait spec present), then — when present —
  the wait file (length + UTF-8), four f32 times (stop / fade-in / fade-out /
  ride-fade-out) and a u16 "station state". v4 and older bins load unchanged
  (wait music off everywhere). Binary **v6** adds bit1 of that flag: a
  per-region **follow action** string (length + UTF-8) naming the region(s) started
  automatically when this one finishes (once mode or once container completion;
  never on manual stop/fade), written after any wait tail. Multiple targets are
  stored as a `;`-separated list (the inspector's **+ Add follow** button adds a
  row); all of them start together. v5 files still load
  (follow off everywhere).
  The v6 **extinction footer** also appends a `RESTOREREGION` string after the
  script name: the region started when the Action key restores muted audio
  (engine skips the script-name bytes then reads it; empty = none).
  The **currently deployed old `TimelineAudio.nlvm`
  cannot parse v6** — after this change you must re-export `timeline.bin` *and*
  recompile/replace the `.nlvm` together, and the two must stay in sync.
- Binary **v7** appends a per-region **loop crossfade** f32 (seconds; 0 = off)
  after any follow tail. For a loop region with a crossfade > 0 the engine keeps
  two handles of the same file and gain-blends them across the loop seam, so each
  repetition overlaps the next instead of restarting abruptly. It is driven from
  the frame update using `getLength()`, so it is not sample-accurate (jitter of
  up to one frame) and doubles the voices for that region. The region inspector
  exposes it as **Loop crossfade (s)** (loop mode only). The version byte is
  written as 7.
- The v5 **extinction footer** appends two f32 values after the disable-fade
  trigger: `audioRefDist` and `audioRolloff` (Settings → Audio Distance). Old
  footers without the tail keep the engine defaults `5.0` / `1.0`.

## 8. Verification

Play the ride and watch the console:
- `Region 'Loop1' trigger 'audio_station' registered.`
- `Region 'Ride' fired: block 'Zone1' state 2 -> 4`
- `Region 'Ride' skipped: block 'Zone1' state 2 -> 4 (different train)` — another
  train caused the state change; the region was correctly withheld from our train.
- `Region 'Loop1' fired by trigger.`
- `Extinction armed (block 'Zone1' -> state 4).`
- `Extinction fired: fade out looping regions (3.5s).`
- `Info [TimelineAudio]: Audio muted. Press Action in station to restore.`
- `Info [TimelineAudio]: Action OK: audio restored.`
- `Info [TimelineAudio]: Action restore fires special event 'Fireworks'.`
- `Info [TimelineAudio]: Region 'Ride' finished -> follow 'Outro'.`
- `Info [TimelineAudio]: Crossfade loop enabled for region 'Loop1' (0.5s).`
- `Info [TimelineAudio]: Wait zone 'Station' stop detected: ride music out, wait music in.`
- `Info [TimelineAudio]: Extinction fade disabled by trigger.`

If loops never fade out: check scripted mode. If a region never fires: check
trigger/block name spelling in the `not found` console lines. Block-based regions
need the coaster in **scripted** operation mode unless **Scripted coaster** is
turned off in Settings (then the built-in normal-mode / Section states are used).