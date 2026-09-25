# RideSync

**Timed, per-train audio for NoLimits 2 coasters — without writing a line of code.**

RideSync is a desktop timeline editor that lets you lay out music, voices and sound
effects on a timeline and have them play on your NoLimits 2 coaster trains exactly
when you want — launch music, station loops, voice lines, hold music and more.
No scripting, no juggling a DAW, no re-exporting by hand between software for a small change

---

## What it does

- **Visual timeline editor** — drop audio onto tracks, trim clips, arrange cues.
- **Cues, not clips** — a *cue* is what actually plays in-game. Choose what starts it:
  a timeline time, a block state, a track trigger, or the Action-key audio restore.
- **Per-train audio** — target a single train, or run every train independently with
  one script for the whole coaster.
- **Hold music** — music that plays while a train is stopped in a zone, then hands
  back to the ride music on departure.
- **Ducking** — music automatically dips under voice cues.
- **Stall fade-out** — if a train gets stuck, the audio fades out (and can stay muted
  until the player restores it at the station).
- **3D audio** — reference distance and rolloff for positional falloff.
- **One-click export** — bundles the game data, script, resource list and all your
  audio into a single `.zip` ready to drop into your park.

## Requirements

- **Windows**
- **Node.js 18+** (only to run/build from source)
- **NoLimits 2** with a coaster you can attach a script to

## Getting started (from source)

```bat
git clone <your-repo-url>
cd RideSync
npm install
npm start
```

Or just double-click **`Launch RideSync.bat`** — it installs dependencies on first
run and opens the editor.

Then follow the **User Guide** (see below) to build your first timeline and export it.

## Build a portable app

```bat
npm run dist
```

This produces `RideSync-<version>-portable.exe` in `dist/` — no installation needed.

## Documentation

- [User Guide](docs/RideSync-User-Guide.html) — start here. Step-by-step, plain language.
- [Reference Manual](docs/RideSync-Reference-Manual.html) — file formats, engine
  internals, the full technical picture.
- [Setup notes](README-setup.md) — build/source details.

## How it works

The editor writes a binary timeline (`config/timeline.bin`) plus a NoLimits 2 script
(`.nlvm` + `.nl2script`). Drop them into your park's `RideSync/` folder, attach the
`.nl2script` to the coaster, place the track triggers your cues expect, and ride.

The game-side engine is `TimelineAudio.java`; the editor embeds the same code as its
script template, so the two stay in sync.

## Project structure

```
RideSync/
├── index.html            # the editor (UI + timeline engine template)
├── main.js               # Electron main process (windows, zip packaging, IPC)
├── preload.js            # safe IPC bridge
├── TimelineAudio.java    # in-game engine (NoLimits 2 script)
├── package.json
├── Launch RideSync.bat
├── README-setup.md       # technical setup notes
└── docs/
    ├── RideSync-User-Guide.html
    └── RideSync-Reference-Manual.html
```

## Credits

Made by **Pieter H.** — independent audio engineer (live concerts and studios), France.
Find me on Discord: **pieterh_**.

## License

License TBD.
