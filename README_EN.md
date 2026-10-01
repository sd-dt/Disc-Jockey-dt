[简体中文](README.md) | **English**

# Disc-Jockey-dt

Play note block songs (`.nbs` / MIDI) in Minecraft. This is a **Minecraft 26.2 port and bug-fix fork of Disc Jockey**.

> The mod ID stays `disc_jockey` for upstream compatibility (config paths and modpack dependencies rely on it).
> Targets Minecraft **26.2** (Fabric, Java 25). Requires **Fabric API** and **Cloth Config** (Mod Menu is optional,
> only used for the config-screen entry point).

---

## Lineage

| Step | Source | License |
| --- | --- | --- |
| Original Disc Jockey | [SemmieDev/Disc-Jockey](https://github.com/SemmieDev/Disc-Jockey) (stops at 1.7.0 / MC 1.21) | MIT |
| 1.21.11 / 26.2 branches and newer features | [xjjakm/Disc-Jockey](https://github.com/xjjakm/Disc-Jockey) (`1.21.11`, `26.2` branches) | MIT |
| This fork (Disc-Jockey-dt) | this repository | MIT (inherited) |

This repository takes xjjakm's **26.2 branch** as its baseline, ports every change we made on the **1.21.11 line**
back into it, and fixes two issues that affect actual playback.

## What this fork changes vs. the upstream 26.2 branch

### 1. Features carried over from the 1.21.11 port

* **Unconditional MIDI support** — the `enableExperimentalMIDI` toggle is gone; `.mid` / `.midi` files are scanned
  and played next to `.nbs` songs (variable-tempo maps, pitch-window mapping, percussion table, GS/XG bank select).
* **Lazy parsing + fast rescans** — MIDI files are first registered from metadata only (listed as `<file name> [midi]`
  with a 0:00 length) and fully parsed the first time they are played or previewed (`SongLoader.ensureSongLoaded`).
  Scans parse in parallel on a bounded thread pool, reuse unchanged songs through a `path + mtime + size` incremental
  cache, and publish the list in one atomic swap. Every scan logs
  `Song list loaded: N songs (X parsed, Y cached) in Z ms`.
* **Automatic server-version detection** — `Config.expectedServerVersion` is no longer user-configurable (hidden from
  the settings screen); it is refreshed every client tick from `player.blockInteractionRange()`
  (`> 5` → `v1_20_5_Or_Later`, otherwise the generic tier).
* **Playback speed 0.1x – 20x** — log-scale slider in the playback panel plus the `/discjockey speed` command
  (same clamping for both).
* **⟳ Refresh-playlist button** — bottom right of the song list; rebuilds the list once the rescan finishes and
  restores the current folder and selection.
* **MIDI instruments match Open Note Block Studio** — ships ONBS's `midi_ins` / `midi_drum` tables (128 GM programmes
  with per-instrument octave compensation; 64 percussion entries with their own pitch) and writes song data through
  `Note.nbsId()` instead of the enum `ordinal()`.

### 2. Fixes in this fork (26.2 line)

* **Instrument mapping is back in line with ONBS.** The upstream 26.2 baseline maps GM brass 56–63 to the four copper
  instruments (`TRUMPET*`) that 26.2 added, and zeroes their octave compensation. But in the NBS format instrument IDs
  **`>= 16` belong to the "custom instrument" range**, so writing those IDs into song data both diverges from ONBS and
  collides with custom instruments stored in files. Brass now follows ONBS's original table again (falling back to
  Flute / Didgeridoo, with ONBS's octave values); `PROGRAM_ID` / `PROGRAM_OCTAVE` / `DRUM_ID` / `DRUM_PITCH` match ONBS
  entry for entry.
* **NBS custom instruments no longer crash the song.** In a `.nbs` file, notes with
  `instrument >= vanillaInstrumentCount` (16 in modern files) are custom instruments whose sound files live in an
  optional section at the end of the file — this mod cannot play them. The old code indexed a 16/20-entry instrument
  array with that ID and threw `ArrayIndexOutOfBoundsException` (followed by `Index 0 out of bounds for length 0` on
  the playback thread). Such notes now **fall back to Harp** and the song keeps playing, with one log line reporting
  how many notes were affected:
  `Song "<file>": N of M notes use custom instruments (NBS instrument id >= 16), which this mod cannot play - using Harp instead`
* **Empty-song guard** — songs that fail to parse or genuinely contain no notes no longer start the playback/preview
  thread.
* **Packaging fix** — the embedded license entry is now named `LICENSE_disc_jockey` instead of `LICENSE_null`.

### 3. Behaviour notes (differences or things worth knowing)

* Config file location: `config/disc_jockey/config.json` (not the old `disc_jockey.json5`); existing settings are not
  migrated automatically.
* Notes that use custom instruments sound as Harp (see above) — that is the limit of what this mod can do, since it
  does not load custom sound files.
* The four copper instruments 26.2 added remain in the instrument table (IDs 16–19): they are only used when a `.nbs`
  file itself declares `vanillaInstrumentCount = 20`; MIDI conversion never writes those IDs.
* The incremental cache only compares `mtime + size`, so a change that keeps both values identical is not detected.
* Files whose extension is neither `.nbs`, `.mid` nor `.midi` are skipped (files without any extension are still
  attempted as NBS).
* `gui/hud/BlocksOverlay` (the note-block counter HUD) stays unregistered, matching upstream 26.2 — the required
  note-block list is printed to chat by the "Blocks" button in the GUI instead.

## Building

Requires **JDK 25** (Minecraft 26.2 runs on Java 25):

```bash
./gradlew build          # -> build/libs/disc_jockey-1.9.8+mc26.2.jar
```

* Toolchain: Gradle `9.6.0` (`gradle/wrapper/gradle-wrapper.properties` points at a Tencent mirror, because
  `services.gradle.org` is unreachable from the machine this fork is developed on), Fabric Loom `1.17-SNAPSHOT`,
  and **no mappings** (Minecraft has shipped unobfuscated since 26.2).
* Dependencies: Fabric Loader `0.19.3`, Fabric API `0.161.0+26.2`, Cloth Config `26.2.155`, Mod Menu `20.0.1`
  (via the Modrinth maven).
* The version string lives in `gradle.properties` (`mod_version`).

## Installation

Drop the built `disc_jockey-*.jar` into `.minecraft/mods/` and make sure you have:

* Fabric Loader ≥ 0.19.3
* [Fabric API](https://modrinth.com/mod/fabric-api)
* [Cloth Config](https://modrinth.com/mod/cloth-config) (required for the config screen)
* [Mod Menu](https://modrinth.com/mod/modmenu) (optional, config-screen entry point only)

## Quick usage

* Put `.nbs` / `.mid` songs into `config/disc_jockey/songs/` (subfolders are supported and scanned recursively).
* The default key opens the song list (rebindable in the config); select a song and hit play — your character performs
  it on the note blocks around you.
* The playback panel offers play modes (single loop / list loop / random / stop after), a speed slider and a preview
  scrubber; the ⟳ button at the bottom right rescans the library.
* `/discjockey` provides the command entry points (e.g. `/discjockey speed <0.1-20>`, play/stop) with TAB completion
  for song names.

## License & credits

* This repository keeps the upstream **MIT** license — see [LICENSE](LICENSE). As MIT requires, keep the original
  copyright notice: `Copyright (c) 2022 Semmieboy_YT`.
* Authorship (identical to `fabric.mod.json`):
  * **SemmieDev** — original author (Disc Jockey, upstream stops at 1.7.0 / MC 1.21)
  * **xjjakm** — second author of modifications ([xjjakm/Disc-Jockey](https://github.com/xjjakm/Disc-Jockey) branches
    `26.2` / `1.21.11`, the baseline of this fork)
  * **sd_dt**, **deepseekfl4.1** — this fork (26.2 port integration + instrument-mapping and custom-instrument fixes)
* Upstream contributors: EnderKill98, myueqf, EnderPhantomWing, chxjj.
* The MIDI instrument tables come from [Open Note Block Studio](https://github.com/OpenNBS/NoteBlockStudio)'s
  `midi_instruments` script.
* Song files (`.nbs` / `.mid`) are copyrighted by their respective authors and are unrelated to this repository.
