# BlockBard — Architecture Reference

Quick-parse guide for engineers (and AI sessions) coming in cold.
Covers what exists in the repo today, not what `BLOCKBARD_PLAN.md` describes as original
design intent — that doc predates most of what's actually built and should be treated as
historical context, not a status reference.

**Last verified against source:** full read of every `.kt` file, this pass. If you find
a mismatch between this doc and the actual code, trust the code and fix this doc — that
exact failure mode (doc drifting silently out of sync) is why the previous version of this
file was rewritten.

---

## Project Identity

| Field | Value |
|---|---|
| Mod name | BlockBard |
| Loader | Fabric |
| MC version | **26.2** (see `gradle.properties`) |
| Mapping layer | **None** — Minecraft ships unobfuscated Mojang names from 26.1 onward; no Yarn/Intermediary step needed |
| Language | Kotlin |
| Entry point | `BlockBardClient : ClientModInitializer` |
| Mod ID | `blockbard` |
| Root package | `kyrielie.blockbard` |

**Client-only.** No server-side code, no mixins touching network packets. Every noteblock
interaction goes through `PlayerController.interactWith()`, which wraps the client's
normal block-interaction call — there is no packet manipulation.

---

## Source Tree Layout

```
src/
  client/kotlin/kyrielie/blockbard/client/
    BlockBardClient.kt          ← ClientModInitializer; wires all subsystems together
    config/
      BlockBardConfig.kt        ← config data class + ConfigManager (plain Gson — see Config section)
    gui/
      MainScreen.kt             ← primary GUI (file picker, organ overlay, controls, tuning, playback)
      PlaybackHud.kt            ← HUD overlay via HudElementRegistry (not HudRenderCallback — see note below)
      BlockBardModMenuIntegration.kt
    input/
      KeyboardInputHandler.kt   ← keys 1–9 → NoteRequest (currently non-functional while MainScreen is open — see Known Issues)
      MidiInputHandler.kt       ← javax.sound.midi device → NoteRequest (runs on a foreign thread — see Known Issues)
    midi/                       ← package declared as kyrielie.blockbard.midi, NOT .client.midi (directory/package name mismatch, harmless but worth knowing when searching)
      FallbackMapper.kt         ← RemappedNoteEvent + FallbackPlan — DEAD CODE, zero callers anywhere (see Known Issues)
      MidiChannelResolver.kt    ← resolves each MIDI NOTE_ON to a NoteBlockInstrument via per-channel GM program tracking
      MidiInstrumentMap.kt      ← GM program (0-127) + percussion → NoteBlockInstrument table
      OrganReadinessChecker.kt  ← coverage check (MIDI requirements vs. NoteBlockRegistry) — built, currently never called
    organ/
      OrganScanner.kt           ← world scan → NoteBlockRegistry.update()
    playback/
      MidiFilePlayer.kt         ← loads .mid via MidiChannelResolver, timed dispatch via ArpeggioScheduler
      NbsFileLoader.kt          ← .nbs binary parser AND NbsPlayer (object NbsPlayer lives in this same file)
    player/
      PlayerController.kt       ← eased rotation + interactWith; see "Rotation & Anti-Cheat Compatibility" below

  main/kotlin/kyrielie/blockbard/
    organ/
      ArpeggioScheduler.kt      ← tick queue; dispatches one note per tick once rotation has converged
      InstrumentShifter.kt      ← ShiftMode resolution (EXACT_ONLY/INSTRUMENT_SHIFT/OCTAVE_SHIFT/BEST_EFFORT)
      MidiToOrganMapper.kt      ← (midiNote, instrument) → BlockPos assignment; defines NotePitch
      MobHeadBlocks.kt          ← MOB_HEAD_BLOCKS constant set
      NoteBlockEntry.kt         ← data class (pos, instrument, noteIndex, midiNote, status)
      NoteBlockRegistry.kt      ← singleton Map<BlockPos, NoteBlockEntry>
      NoteBlockTuner.kt         ← TuneTarget + stateful tuning state machine (TUNING/VERIFYING/DONE/FAILED)
      OrganMap.kt               ← Map<BlockPos, ReachInfo> built by PlayerController.centerOnOrgan()
    util/
      LittleEndianReader.kt     ← DataInputStream extensions for NBS parsing (no EOF checking — see Known Issues)
      NoteUtils.kt              ← midiBase per instrument, noteIndex↔MIDI conversions, clicksNeeded
      VecMath.kt                ← vecToYawPitch (direction vector → yaw/pitch degrees)
```

**Package split note:** `organ/` and `util/` live under `src/main/` (shared/common, would
also be visible to a dedicated server if this mod ever gained one). Everything under
`src/client/` is client-only and cannot be referenced from `src/main/` code — this
boundary has caused real compile errors in the past when a type needed by both sides
(`NotePitch`, `NoteBlockInstrument`-keyed assignment) was initially placed on the wrong
side of the split. If you need a type usable from both `ArpeggioScheduler` (main) and
`MidiFilePlayer` (client), it must live under `src/main/`.

---

## Data Flow — Primary Pipeline

```
OrganScanner.scan()
      │  O(r³) cube iteration around the player, reads BlockState — see Known Issues
      ▼
NoteBlockRegistry        (singleton, Map<BlockPos, NoteBlockEntry>)
      │
      ├── queried by MidiToOrganMapper.buildAssignment(noteUsageCounts, reachableBlocks)
      │         │  noteUsageCounts: Map<NotePitch, Int> — NotePitch(midiNote, instrument?)
      │         │  native-match preferred by fewest tuning clicks (not just distance),
      │         │  then InstrumentShifter for notes with no exact-instrument match
      │         ▼
      │   OrganAssignment { assignment: Map<NotePitch, BlockPos>, unplayable: List<NotePitch>, shifts: Map<NotePitch, Int> }
      │         │
      │         ▼
      │   MidiToOrganMapper.computeTuneTargets(assignment, reachableBlocks)
      │         │
      │         ▼
      │   List<TuneTarget> { pos, snapshotNote, targetNote, instrument }
      │         │
      │         ▼
      │   NoteBlockTuner (TUNING → VERIFYING → DONE/FAILED state machine; calls PlayerController.interactWith per click)
      │
      └── ArpeggioScheduler.assignment = result.assignment   ← wired by MainScreen.startTuning()
                │  consulted at playback time by ArpeggioScheduler.resolvePos()
                │  fallback chain: resolvedPos → assignment[exact NotePitch] → assignment[pitch-only NotePitch] → NoteBlockRegistry.findBestFor() live lookup
                ▼
          ArpeggioScheduler.onTick()  (END_CLIENT_TICK)
                │  only dispatches once PlayerController.rotationConverged(pos) is true
                ▼
          PlayerController.interactWith(pos)
                │  re-snaps to final rotation (already within ~2° by this point), then interacts
```

**MIDI file → timed events:**

```
MidiFilePlayer.load(file)
      │  reads tempo via raw MetaMessage scan (0x51)
      │  reads notes via MidiChannelResolver.resolve(file) — re-parses the file independently
      │  for instrument resolution (tracks PROGRAM_CHANGE per channel; channel 10 = percussion)
      ▼
List<TimedNoteEvent>(tick, midiNote, instrument)   ← instrument is non-null, always resolved
      │
      ▼  (on play(), per scheduled tick)
ArpeggioScheduler.enqueue(NoteRequest(midiNote, instrument = event.instrument))
```

**NBS file → timed events:**

```
NbsFileLoader.load(file)
      │  binary parse via LittleEndianReader; version-gated fields (velocity/panning/pitch only on version >= 4 — verified correct against the NBS spec)
      ▼
NbsFile { notes: List<NbsNote>, tempo, title, ... }
      │
      ▼  (NbsPlayer.play(), per scheduled tick)
nbsInstrumentToBlock(note.instrument)   ← 16-entry NBS instrument byte table → NoteBlockInstrument?
      │  null for custom (index >= 16) instruments — falls back to pitch-only matching
      ▼
ArpeggioScheduler.enqueue(NoteRequest(note.key + 21, instrument = resolvedInstrument))
```

Both playback paths converge on the same `ArpeggioScheduler`/`NoteRequest` pipeline — see
Key Types below for `NoteRequest`'s exact fields.

---

## Rotation & Anti-Cheat Compatibility

This is the single most important behavioral mechanism in the player-control subsystem
and is not obvious from the type signatures alone, so it gets its own section.

`PlayerController.primeRotation(pos)` does **not** snap yaw/pitch instantly to a target.
It eases toward it at a capped rate (`MAX_ROTATION_DEGREES_PER_TICK`, currently `35f`
degrees/tick, a `const val`) because many anti-cheat plugins flag large single-tick
rotation deltas as a "rotation hack" signature and will silently drop the following
use-item packet — even though the rotation packet itself is accepted and renders
correctly on the client, which makes the bug look like "the player visibly turns to face
the block but the click does nothing," with no client-side error to point at the cause.

`PlayerController.rotationConverged(pos)` reports once the eased rotation is within
`ROTATION_CONVERGENCE_THRESHOLD_DEGREES` (currently `2f`, also `const val`) of the target.
`ArpeggioScheduler.onTick()` will not dispatch the actual interaction
(`interactDelegate`) until `rotationConvergedDelegate` confirms convergence — this is
wired via two separate function-reference delegates set in
`BlockBardClient.onInitializeClient()`, not a direct call, so a unit test or alternate
client entrypoint could substitute different rotation logic without `ArpeggioScheduler`
needing to know about `PlayerController` directly (required by the `main`/`client` source
split — `ArpeggioScheduler` lives in `main` and cannot import client-only classes).

A large turn (observed up to ~178° yaw in real testing) can take several ticks to
converge. `ArpeggioScheduler.staleTimeoutMs` (default 200ms) does **not** apply to a note
once it has become the head of the queue and started converging — only to notes still
waiting behind it. A separate `rotationInProgressTimeoutMs` (1500ms, a `var` — already
config-wireable, just not yet wired) caps a genuinely stuck rotation (e.g. overridden by
something else) so the queue can't deadlock on one note forever.

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
- `MOB_HEAD`: floor-mounted mob skull above (see `MobHeadBlocks.kt` for the exact set —
  wall-mounted variants are intentionally excluded) — plays a fixed ambient sound, not a
  musical note

### `NotePitch` (defined in `MidiToOrganMapper.kt`, package `kyrielie.blockbard.organ`)
```kotlin
data class NotePitch(val midiNote: Int, val instrument: NoteBlockInstrument? = null)
fun NotePitch.displayName(): String  // e.g. "C4 (BELL)", or "C4" if instrument is null
```
The instrument-aware key used throughout the assignment/scheduling pipeline.
`instrument = null` means "any instrument at this pitch is acceptable" — used by callers
with no instrument source (keyboard 1-9 presses, the chromatic-scale test). This exists
because a pitch-only key would let two notes at the same pitch but different instruments
(e.g. a harp note and a bell note at the same MIDI value) collide on one assignment slot,
silently picking whichever instrument happened to be assigned first.

There is a second, structurally near-identical type, `MidiNoteRequirement(instrument: NoteBlockInstrument, midiNote: Int)`
in `OrganReadinessChecker.kt` (package `kyrielie.blockbard.midi`, client-only). It cannot
currently be unified with `NotePitch` without moving `OrganReadinessChecker` into the
`main` source set, since `NotePitch` must stay in `main` for `ArpeggioScheduler` to use
it. Known duplication, not yet resolved — see `BLOCKBARD_ENGINEERING_PLAN.md`.

### `NoteRequest` (in `ArpeggioScheduler.kt`)
```kotlin
data class NoteRequest(
    val midiNote: Int,
    val instrument: NoteBlockInstrument? = null,
    val enqueuedAtMs: Long = System.currentTimeMillis(),
    val resolvedPos: BlockPos? = null   // bypasses assignment lookup entirely when set
)
```
`resolvedPos` is used by the chromatic-scale test and any caller that already knows
exactly which block to hit, skipping the `assignment`/`NoteBlockRegistry` lookup chain.

### `OrganAssignment`
```kotlin
data class OrganAssignment(
    val assignment: Map<NotePitch, BlockPos>,
    val unplayable: List<NotePitch>,
    val shifts: Map<NotePitch, Int>     // semitone shift applied per note, 0 if native match
)
```
**Not** `Map<Int, BlockPos>` — this was the type before the instrument-threading work;
every consumer (`ArpeggioScheduler.assignment`, `MainScreen`'s coverage display) must
agree on `NotePitch` as the key or the project will not compile. This exact mismatch has
caused a real build failure once already (`ArpeggioScheduler.assignment` was declared with
a separate `Pair<Int, NoteBlockInstrument?>` key type during the same refactor that
introduced `NotePitch`, before being reconciled) — if you see a `Pair<Int, ...>` key
anywhere related to note assignment, it's very likely a stale leftover and should be
converted to `NotePitch`.

### `ShiftMode`
```kotlin
enum class ShiftMode { EXACT_ONLY, INSTRUMENT_SHIFT, OCTAVE_SHIFT, BEST_EFFORT }
```
Default: `BEST_EFFORT` (instrument shift first, octave shift fallback). Resolved via
`InstrumentShifter.findBest(midiNote, candidates)`. Note: when `MidiToOrganMapper` calls
this with a candidate list already filtered to one specific instrument (because the note
specified an instrument), `findBest`'s internal instrument-shift step becomes a
structural no-op — only its octave-shift step can find anything new in that case, since
every candidate already shares the same instrument. This is intentional (switching
instruments away from what a note explicitly specified would defeat the point), not a bug.

### `ReachInfo` / `OrganMap`
```kotlin
data class ReachInfo(val isReachable: Boolean, val yaw: Float, val pitch: Float, val distance: Double)
class OrganMap(val standPos: BlockPos, private val reachMap: Map<BlockPos, ReachInfo>)
```
Built once by `PlayerController.centerOnOrgan()`. Pre-computed facing angles, consulted by
`primeRotation`/`interactWith` to avoid recomputing yaw/pitch from scratch every tick —
falls back to live `vecToYawPitch()` computation for any position not in the map (e.g.
stale map after blocks moved, or map never built this session).

---

## Subsystem Summaries

### `OrganScanner` (client)
Iterates a cube of `scanRadius` (default 5, max 10 per config) blocks around the player.
**O((2r+1)³)** — up to 9261 block-state reads at max radius, run synchronously on the
client thread. Calls `NoteBlockRegistry.update(found)`. Also checks for instrument
overflow (>25 blocks of one instrument) and emits chat warnings. Triggered from
`MainScreen`'s Scan button or an automatic timer (`autoRescanIntervalSeconds`, default 3s)
that currently runs unconditionally — including during active playback or tuning, which
is wasted client-thread work with no benefit (see Known Issues).

### `NoteBlockRegistry` (main, singleton)
Authoritative in-memory store. Key query methods:
- `findBestForMidi(midiNote)` — closest PLAYABLE block at that exact pitch, any instrument
- `findBestFor(midiNote, instrument)` — prefers exact instrument match, falls back to
  `findBestForMidi` if no instrument match exists or `instrument` is null
- `updateTunedNote(pos, newNoteIndex)` — called by the tuner after each confirmed click

### `MidiToOrganMapper` (main)
Two-phase:
1. `buildAssignment(noteUsageCounts: Map<NotePitch, Int>, reachableBlocks)` — assigns
   most-used notes first; among candidates whose instrument natively covers the note,
   prefers the one needing the *fewest tuning clicks* (not just nearest distance) so
   re-running assignment after a song change or rescan doesn't gratuitously reassign an
   already-correctly-tuned block. Falls back to `InstrumentShifter` for notes with no
   native match.
2. `computeTuneTargets(assignment, reachableBlocks)` — converts assignment to per-block
   `TuneTarget`s. Drops (with a warning, not silently) any effective MIDI note that falls
   outside the assigned instrument's 0–24 range after a shift is applied.

### `InstrumentShifter` (main, singleton)
Stateful: `mode` and `maxOctaveShift` set at init from config. `findBest(midiNote, candidates)`
dispatches by `ShiftMode`. Instrument shift checks `instrument.coversNatively(midiNote)`
(range check) and, among multiple covering candidates, prefers fewest tuning clicks
(same fix as `MidiToOrganMapper`'s native-match step, for consistency). Octave shift tries
±12/±24 semitones depending on `maxOctaveShift`.

### `ArpeggioScheduler` (main, singleton)
Tick-driven queue (`kotlin.collections.ArrayDeque<NoteRequest>` — **not thread-safe**, see
Known Issues). Called every tick from `BlockBardClient`'s `ClientTickEvents.END_CLIENT_TICK`.
Only dispatches the head of the queue once `rotationConvergedDelegate` confirms the player
has finished turning toward it (see Rotation section above). `staleTimeoutMs` prunes
backlog sitting behind an in-progress head note; `rotationInProgressTimeoutMs` caps a
stuck head note. Delegates the actual interaction via `interactDelegate: ((BlockPos) -> Boolean)?`
— wired to `PlayerController::interactWith` at init, **not** `Unit`-returning; the boolean
result is logged and used to detect a failed dispatch.

### `PlayerController` (client)
Handles:
- `centerOnOrgan(blocks)` — finds best stand position, builds `OrganMap`
- `primeRotation(pos)` — eases rotation toward target, called every tick a note is
  pending (`START_CLIENT_TICK`, before the dispatch-gating tick)
- `rotationConverged(pos)` — convergence query, consulted by `ArpeggioScheduler`
- `interactWith(pos)` — looks up `ReachInfo` (or computes live), re-snaps to final
  rotation, calls the actual block-interaction; returns `Boolean` success/failure

### `NoteBlockTuner` (main)
Stateful (`TunerState`: `IDLE → TUNING → VERIFYING → DONE` or `FAILED`). Re-reads live
world blockstate every tick for every target rather than trusting the scan-time snapshot;
the snapshot (`TuneTarget.snapshotNote`) is only used for the initial click-count estimate.
Skips blocks already at their target note (checked against live world state, not the
snapshot). Token-bucket rate limiter (8 interacts / 310ms, matching Paper's server-side
limit) and a verification phase that waits `ping*2 + 100ms` before declaring success.
**On a verification mismatch it currently retries with no retry cap** — see Known Issues;
a block that can never converge will cycle `TUNING ↔ VERIFYING` indefinitely.

### `MidiFilePlayer` (client)
Loads `.mid` files. Tempo read via a direct `MetaMessage` (0x51) scan; notes resolved via
`MidiChannelResolver.resolve(file)`, which independently re-parses the file (a small
deliberate duplicate parse, traded for keeping `MidiChannelResolver`'s signature
self-contained). Every `TimedNoteEvent` carries a resolved `NoteBlockInstrument` —
this is not optional/best-effort; channel 10 is always treated as percussion regardless of
program, and any unset channel defaults to GM program 0 (piano → HARP). Tempo scaling is
read live per-event during playback (`tickToScaledMs(..., tempoMultiplier)`), so changing
the tempo slider has immediate effect on a song already playing.

### `NbsFileLoader` / `NbsPlayer` (client — same file)
`NbsFileLoader` parses `.nbs` binary format via `LittleEndianReader`. Version-gated fields
(velocity/panning/pitch, version ≥ 4) are correctly gated — verified against the published
NBS spec. NBS key offset: `midiNote = note.key + 21`. Tempo field is ticks-per-second × 100
(divide by 100.0 for real ticks/sec). `nbsInstrumentToBlock()` maps the 16 standard NBS
instrument byte values (0-15) to `NoteBlockInstrument`; values ≥ 16 are custom
(non-vanilla) instruments and resolve to `null`, falling back to pitch-only matching.

`NbsPlayer` is a **separate `object` in this same file**, not a distinct class — search
here, not for a file named `NbsPlayer.kt`. Unlike `MidiFilePlayer`, its tempo multiplier
is captured once at `play()` start and not re-read live during playback (see Known
Issues), and it currently has no tick/progress getters equivalent to
`MidiFilePlayer.getCurrentTick()`/`getTotalTicks()`.

### `FallbackMapper` / MIDI channel files (client/midi, package `kyrielie.blockbard.midi`)
`MidiChannelResolver` and `MidiInstrumentMap` are live and load-bearing — they're what
`MidiFilePlayer` actually uses to resolve instrument per note (see above).
`OrganReadinessChecker` is built and correct but has zero callers anywhere in the codebase
— nothing currently invokes a pre-tuning coverage check. `FallbackMapper` (`RemappedNoteEvent`,
`FallbackPlan`, its own `PITCH_LADDER` instrument-shift logic) is **entirely dead code** —
confirmed by grep, zero callers anywhere, including no usage in `MainScreen`'s GUI despite
what older documentation claimed. It implements an overlapping but different fallback
strategy from `InstrumentShifter`/`MidiToOrganMapper`, which is the one actually wired in.

---

## Initialization Sequence (`BlockBardClient.onInitializeClient`)

1. `ConfigManager.load()` — reads `config/blockbard/config.json` (plain Gson; a malformed
   file is currently caught and silently reset to defaults with no log line — see Known
   Issues)
2. Applies config to `OrganScanner.scanRadius`, `InstrumentShifter.mode`/`maxOctaveShift`,
   `ArpeggioScheduler.staleTimeoutMs`
3. Wires two `ArpeggioScheduler` delegates: `interactDelegate = { pos -> PlayerController.interactWith(pos) }`
   and `rotationConvergedDelegate = { pos -> PlayerController.rotationConverged(pos) }`
4. Registers keybindings: `B` (open GUI), `H` (toggle HUD)
5. `KeyboardInputHandler.register()`, `PlaybackHud.register()`
6. `ClientTickEvents.START_CLIENT_TICK` — primes rotation toward the next pending note
   (`ArpeggioScheduler.peekNextPos()?.let { PlayerController.primeRotation(it) }`)
7. `ClientTickEvents.END_CLIENT_TICK` — polls the GUI/HUD keybindings, calls
   `ArpeggioScheduler.onTick()` (the actual dispatch-gated-on-convergence step)
8. `MidiInputHandler.autoConnect()` — attempts to open a saved MIDI device; its receiver
   callback runs on a foreign (non-Minecraft) thread — see Known Issues

---

## Config (`BlockBardConfig`)

**Plain Gson serialization to `config/blockbard/config.json`** — there is no Cloth Config
or AutoConfig dependency involved in how this mod persists its own settings (Cloth Config
is listed in `BLOCKBARD_PLAN.md` as a mods-folder runtime dependency for a config-screen
UI library, which is a different thing; verify against that doc's actual context if this
distinction matters for your task).

Current fields (`BlockBardConfig.kt`):
`scanRadius: Int = 5`, `shiftMode: String = "BEST_EFFORT"`, `maxOctaveShift: Int = 1`,
`reportShiftsInHud: Boolean = true`, `hudEnabled: Boolean = true`,
`arpeggioStaleTimeoutMs: Long = 200L`, `defaultTempoMultiplier: Float = 1.0f`,
`autoRescanIntervalSeconds: Int = 3`, `maxTuningClicksPerTick: Int = 1`,
`lastPlayedTrack: String?`, `shuffleHistory: MutableList<String>`, `defaultOctave: Int = 4`,
`midiDeviceName: String?`, `keyMappings: List<Int> = [54..62]` (MIDI notes for keys 1-9).

**Not yet config-driven, despite being directly comparable to the above:**
`PlayerController.MAX_ROTATION_DEGREES_PER_TICK` (35f) and
`ROTATION_CONVERGENCE_THRESHOLD_DEGREES` (2f) are `const val`, and
`ArpeggioScheduler.rotationInProgressTimeoutMs` (1500L) is a `var` but not yet wired from
config — all three directly affect anti-cheat compatibility and are plausible candidates
for per-server tuning.

---

## Known Issues (verified against current source — fix details in `BLOCKBARD_ENGINEERING_PLAN.md`)

- **Thread safety:** `MidiInputHandler`'s MIDI-device callback thread calls
  `ArpeggioScheduler.enqueue()` directly; the scheduler's queue is not synchronized against
  the concurrent client-thread `onTick()`/`peekNextPos()` calls. Live data race whenever a
  MIDI keyboard is connected.
- **`NoteBlockTuner` has no retry cap:** a block that never converges during verification
  cycles `TUNING ↔ VERIFYING` forever instead of eventually reaching `FAILED`.
- **Keyboard keys 1-9 don't register while `MainScreen` is open.** Root cause:
  `KeyboardInputHandler` relies on `KeyMapping.consumeClick()`, the polling mechanism for
  gameplay keybinds with no GUI focus. `MainScreen` never overrides `keyPressed`, so there's
  no path for these keys to reach the scheduler while the GUI (required for the feature to
  be "active" at all) is open.
- **`FallbackMapper` is dead code**; `OrganReadinessChecker` is unwired. Two issues, not
  one — see the subsystem summary above.
- **`NbsPlayer` lacks feature parity with `MidiFilePlayer`:** no tick/progress getters; tempo
  multiplier captured once at `play()` start rather than read live, so the tempo slider has
  no effect on NBS playback already in progress.
- **`PlaybackHud` doesn't check `NbsPlayer` state at all** — shows idle during NBS playback.
- **`LittleEndianReader` doesn't check for EOF** (`DataInputStream.read()` returning `-1`)
  — a truncated/corrupted `.nbs` file produces silently-garbage values instead of a clean
  error.
- **`OrganScanner.scan()` is O(r³)**, runs synchronously on the client thread, and the
  automatic rescan timer in `MainScreen` currently fires unconditionally — including during
  active playback or tuning.
- **`ConfigManager.load()` swallows parse exceptions silently** — a corrupt `config.json`
  resets to defaults with zero log output explaining why.

---

## Build Commands

```bash
./gradlew build          # full build → build/libs/
./gradlew runClient      # dev client with mod loaded
```

CI: `.github/workflows/build.yml` — check this file directly for the current trigger
branches and steps rather than assuming; it has not been re-verified in this pass.

---

## Quick Orientation Checklist

| Task | Files to read first |
|---|---|
| Change scan logic | `OrganScanner.kt`, `NoteBlockRegistry.kt`, `NoteBlockEntry.kt` |
| Change note assignment | `MidiToOrganMapper.kt` (`NotePitch`, `buildAssignment`), `InstrumentShifter.kt` |
| Change tuning behavior | `NoteBlockTuner.kt`, `NoteUtils.kt` (`clicksNeeded`) |
| Change playback timing | `ArpeggioScheduler.kt`, `MidiFilePlayer.kt`, `NbsFileLoader.kt` (`NbsPlayer`) |
| Change player movement / rotation | `PlayerController.kt` — read the Rotation & Anti-Cheat section above first |
| Change GUI | `MainScreen.kt`, `PlaybackHud.kt` |
| Change instrument ranges | `NoteUtils.kt` (`midiBase` extension) |
| Add a config option | `BlockBardConfig.kt`, then wire it in `BlockBardClient.onInitializeClient()` |
| Debug MIDI file issues | `MidiFilePlayer.kt`, `midi/MidiChannelResolver.kt`, `midi/OrganReadinessChecker.kt` (currently unwired — won't run unless you call it) |
| Debug NBS file issues | `NbsFileLoader.kt` (parser + `NbsPlayer`), `LittleEndianReader.kt` (no EOF handling — see Known Issues) |
| Debug instrument mismatches during playback | `NotePitch`, `ArpeggioScheduler.resolvePos()`'s fallback chain |
