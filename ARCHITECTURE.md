# BlockBard — Architecture Reference

Quick-parse guide for engineers (and AI sessions) coming in cold.
Covers what exists in the repo today, not what `BLOCKBARD_PLAN.md` describes as future intent.

---

## Project Identity

| Field | Value |
|---|---|
| Mod name | BlockBard |
| Loader | Fabric |
| MC version | 1.21.1 (Yarn mappings 1.21.1+build.3) |
| Language | Kotlin |
| Entry point | `BlockBardClient : ClientModInitializer` |
| Mod ID | `blockbard` |
| Root package | `kyrielie.blockbard` |

**Client-only.** No server-side code, no mixins touching network packets. Every noteblock interaction goes through vanilla `InteractionManager.interactBlock(...)`.

---

## Source Tree Layout

```
src/
  client/kotlin/kyrielie/blockbard/client/
    BlockBardClient.kt          ← ClientModInitializer; wires all subsystems together
    config/
      BlockBardConfig.kt        ← config data class + ConfigManager
    gui/
      MainScreen.kt             ← primary GUI (file picker, organ overlay, controls)
      PlaybackHud.kt            ← HudRenderCallback overlay + mini controls
      BlockBardModMenuIntegration.kt
    input/
      KeyboardInputHandler.kt   ← keys 1–9 → NoteRequest
      MidiInputHandler.kt       ← javax.sound.midi device → NoteRequest
    midi/
      FallbackMapper.kt         ← RemappedNoteEvent + FallbackPlan data classes
      MidiChannelResolver.kt
      MidiInstrumentMap.kt
      OrganReadinessChecker.kt
    organ/
      OrganScanner.kt           ← world scan → NoteBlockRegistry.update()
    playback/
      MidiFilePlayer.kt         ← javax.sound.midi load + timed event dispatch
      NbsFileLoader.kt          ← .nbs binary parser
    player/
      PlayerController.kt       ← rotate/micro-step/interactBlock

  main/kotlin/kyrielie/blockbard/
    organ/
      ArpeggioScheduler.kt      ← tick queue; one note per tick
      InstrumentShifter.kt      ← ShiftMode resolution (EXACT/INSTRUMENT/OCTAVE/BEST_EFFORT)
      MidiToOrganMapper.kt      ← MIDI note → BlockPos assignment
      MobHeadBlocks.kt          ← MOB_HEAD_BLOCKS constant set
      NoteBlockEntry.kt         ← data class (pos, instrument, noteIndex, midiNote, status)
      NoteBlockRegistry.kt      ← singleton Map<BlockPos, NoteBlockEntry>
      NoteBlockTuner.kt         ← TuneTarget data class + click-count math
      OrganMap.kt               ← Map<BlockPos, ReachInfo> built by PlayerController
    util/
      LittleEndianReader.kt     ← DataInputStream extensions for NBS parsing
      NoteUtils.kt              ← midiBase per instrument, noteIndex↔MIDI conversions
      VecMath.kt                ← yaw/pitch helpers
```

**Package split note:** `organ/` and `util/` live under `src/main/` (shared/common). Everything under `src/client/` is client-only. There is no server-side source root.

---

## Data Flow — Primary Pipeline

```
OrganScanner.scan()
      │  iterates blocks within scanRadius, reads BlockState
      ▼
NoteBlockRegistry        (singleton, Map<BlockPos, NoteBlockEntry>)
      │
      ├── queried by MidiToOrganMapper.buildAssignment()
      │         │
      │         │  also consults InstrumentShifter for notes
      │         │  with no exact-match block
      │         ▼
      │   OrganAssignment  { assignment: Map<Int,BlockPos>, unplayable, shifts }
      │         │
      │         ▼
      │   MidiToOrganMapper.computeTuneTargets()
      │         │
      │         ▼
      │   List<TuneTarget>  { pos, currentNote, targetNote, instrument }
      │         │
      │         ▼
      │   NoteBlockTuner  (calls PlayerController.interactWith per click)
      │
      └── queried at playback time by ArpeggioScheduler
                │  via interactDelegate: (BlockPos) -> Unit
                │
                ▼
          PlayerController.interactWith(pos)
                │  rotates player, calls InteractionManager.interactBlock()
```

**MIDI file → timed events:**

```
MidiFilePlayer.load(file)
      │  javax.sound.midi.MidiSystem.getSequence()
      │  extracts NOTE_ON events → List<TimedNoteEvent>
      ▼
ArpeggioScheduler.enqueue(NoteRequest)   ← called per-tick during playback
```

---

## Key Types

### `NoteBlockEntry`
```kotlin
data class NoteBlockEntry(
    val pos: BlockPos,
    val instrument: NoteBlockInstrument,
    val noteIndex: Int,           // Minecraft note 0–24
    val midiNote: Int,            // instrument.midiBase + noteIndex
    val distanceFromPlayer: Double,
    val status: NoteBlockStatus,  // PLAYABLE | SILENCED | MOB_HEAD
    val mobHeadType: Block? = null
)
```

### `NoteBlockStatus`
```kotlin
enum class NoteBlockStatus { PLAYABLE, SILENCED, MOB_HEAD }
```
- `SILENCED`: non-air, non-mob-head block directly above the noteblock
- `MOB_HEAD`: mob skull above — plays fixed ambient sound, not a musical note

### `OrganAssignment`
```kotlin
data class OrganAssignment(
    val assignment: Map<Int, BlockPos>,  // midiNote → pos
    val unplayable: List<Int>,
    val shifts: Map<Int, Int>            // midiNote → semitone shift applied
)
```

### `ShiftMode`
```kotlin
enum class ShiftMode { EXACT_ONLY, INSTRUMENT_SHIFT, OCTAVE_SHIFT, BEST_EFFORT }
```
Default: `BEST_EFFORT` (instrument shift first, octave shift fallback).

### `ReachInfo` / `OrganMap`
```kotlin
data class ReachInfo(val isReachable: Boolean, val yaw: Float, val pitch: Float, val distance: Double)

class OrganMap(val standPos: BlockPos, private val reachMap: Map<BlockPos, ReachInfo>)
```
Built once by `PlayerController.centerOnOrgan()`. Pre-computed facing angles used during playback to avoid per-tick recalculation.

---

## Instrument MIDI Bases

From `NoteUtils.kt` — the `NoteBlockInstrument.midiBase` extension:

| midiBase | Instruments |
|---|---|
| 30 (F♯1) | BASS, DIDGERIDOO |
| 42 (F♯2) | GUITAR, TRUMPET_WEATHERED, TRUMPET_OXIDIZED |
| 54 (F♯3) | HARP, BASS_DRUM, SNARE, HAT, BANJO, COW_BELL, BIT, IRON_XYLOPHONE, PLING, TRUMPET, TRUMPET_EXPOSED |
| 66 (F♯4) | FLUTE |
| 78 (F♯5) | BELL, CHIME, XYLOPHONE |

Formula: `midiNote = instrument.midiBase + noteIndex` (noteIndex 0–24).

---

## Subsystem Summaries

### `OrganScanner` (client)
Iterates a cube of `scanRadius` (default 5, max 10) blocks around the player on demand. Calls `NoteBlockRegistry.update(found)`. Also checks for instrument overflow (>25 blocks of one instrument) and emits chat warnings. Triggered from `MainScreen` button or on-tick rescan.

### `NoteBlockRegistry` (main, singleton)
Authoritative in-memory store. Key query methods:
- `findBestForMidi(midiNote)` — closest PLAYABLE exact match
- `allPlayableMidiNotes()` — used by GUI to grey unavailable keys
- `updateTunedNote(pos, newNoteIndex)` — called by tuner after each click

### `MidiToOrganMapper` (main)
Two-phase:
1. `buildAssignment(midiNoteUsageCounts, reachableBlocks)` — assigns most-used notes first; native-range blocks preferred, `InstrumentShifter` for remainder.
2. `computeTuneTargets(assignment, reachableBlocks)` — converts assignment to per-block click counts. Drops (with warning) any effective MIDI note outside the instrument's 0–24 range.

### `InstrumentShifter` (main, singleton)
Stateful: `mode` and `maxOctaveShift` set at init from config. `findBest(midiNote, candidates)` dispatches by `ShiftMode`. Instrument shift checks `instrument.coversNatively(midiNote)` (range check); octave shift tries ±12/±24 semitones.

### `ArpeggioScheduler` (main, singleton)
Tick-driven queue (`ArrayDeque<NoteRequest>`). Called every tick from `BlockBardClient`'s `ClientTickEvents.END_CLIENT_TICK`. Drops stale entries (>`staleTimeoutMs`, default from config). Delegates actual interaction via `interactDelegate: ((BlockPos) -> Unit)?` — set to `PlayerController::interactWith` at init.

### `PlayerController` (client)
Handles:
- `centerOnOrgan(blocks)` — finds best stand position, builds `OrganMap`
- `interactWith(pos)` — looks up `ReachInfo`, rotates player, calls `InteractionManager.interactBlock()`
- Micro-step logic when a block falls outside 4.5-block reach

### `NoteBlockTuner` (main)
`TuneTarget` carries `(pos, currentNote, targetNote, instrument)` plus `estimatedClicks = ((target - current) % 25 + 25) % 25`. Tuning is purely sequential right-clicks; only goes forward in the 0→24→0 cycle.

### `MidiFilePlayer` (client)
Loads `.mid` via `javax.sound.midi.MidiSystem`. Extracts `NOTE_ON` events (velocity > 0) into `List<TimedNoteEvent>`. Supports tempo scaling: `tickToScaledMs(tick, ticksPerBeat, usPerBeat, tempoMultiplier)`. Pause/resume by recording current tick position.

### `NbsFileLoader` (client)
Parses `.nbs` binary format (little-endian) using `LittleEndianReader`. NBS key offset: add 21 to NBS key for MIDI note number. Tempo field is ticks-per-second × 100.

### `FallbackMapper` / MIDI channel files (client/midi)
`RemappedNoteEvent` and `FallbackPlan` support the GUI's fallback warning display when a MIDI channel's instrument has no exact noteblock coverage. `MidiChannelResolver` and `MidiInstrumentMap` handle channel-to-instrument mapping for MIDI file analysis. `OrganReadinessChecker` cross-references `NoteBlockRegistry` against a loaded MIDI file to produce readiness status before tuning.

---

## Initialization Sequence (`BlockBardClient.onInitializeClient`)

1. `ConfigManager.load()` — reads `config/blockbard/config.json`
2. Applies config to `OrganScanner.scanRadius`, `InstrumentShifter.mode/maxOctaveShift`, `ArpeggioScheduler.staleTimeoutMs`
3. Sets `ArpeggioScheduler.interactDelegate = { pos -> PlayerController.interactWith(pos) }`
4. Registers keybindings: `B` (open GUI), `H` (toggle HUD)
5. `KeyboardInputHandler.register()`, `PlaybackHud.register()`
6. `ClientTickEvents.END_CLIENT_TICK` — polls keybindings, calls `ArpeggioScheduler.onTick()`
7. `MidiInputHandler.autoConnect()` — attempts to open saved MIDI device

---

## Config (`BlockBardConfig`)

Persisted to `config/blockbard/config.json` via Cloth Config / AutoConfig. Key fields:
- `scanRadius: Int` — default 5
- `shiftMode: String` — maps to `ShiftMode` enum via `shiftModeEnum()`
- `maxOctaveShift: Int` — 1 or 2
- `arpeggioStaleTimeoutMs: Long`
- MIDI device name, HUD visibility, tempo default, rescan interval

---

## What Is Not Yet Implemented (per plan vs. current code)

These are called out in `BLOCKBARD_PLAN.md` but have no corresponding source file in the repo:

- `VirtualKeyboardScreen` (Section 5.11) — no file found
- `FileListWidget`, `OrganOverlayPanel`, `PlaybackControlBar` (Section 5.12 sub-components) — logic is inside `MainScreen.kt` directly
- `ReachCalculator` (separate class) — reach logic is inline in `PlayerController`
- `NbsPlayer` (coroutine-based playback) — `NbsFileLoader` exists but no dedicated player class
- Data generation, DataProvider classes — no `runData` resources exist; no data pack content

---

## Build Commands

```bash
./gradlew runClient     # dev client with mod loaded
./gradlew clean build   # production JAR → build/libs/blockbard-*+1.21.1.jar
./gradlew genSources    # generate Yarn-mapped MC sources for IDE navigation
```

CI: `.github/workflows/build.yml` — runs `clean build` on push to `main`/`dev`, uploads JAR artifact.

---

## Quick Orientation Checklist

Coming in to work on a specific area? Start here:

| Task | Files to read first |
|---|---|
| Change scan logic | `OrganScanner.kt`, `NoteBlockRegistry.kt`, `NoteBlockEntry.kt` |
| Change note assignment | `MidiToOrganMapper.kt`, `InstrumentShifter.kt` |
| Change tuning behavior | `NoteBlockTuner.kt`, `NoteUtils.kt` (click math) |
| Change playback timing | `ArpeggioScheduler.kt`, `MidiFilePlayer.kt` |
| Change player movement | `PlayerController.kt`, `OrganMap.kt` |
| Change GUI | `MainScreen.kt`, `PlaybackHud.kt` |
| Change instrument ranges | `NoteUtils.kt` (`midiBase` extension) |
| Add a config option | `BlockBardConfig.kt`, `BlockBardClient.kt` (apply it at init) |
| Debug MIDI file issues | `MidiFilePlayer.kt`, `midi/MidiChannelResolver.kt`, `midi/OrganReadinessChecker.kt` |
