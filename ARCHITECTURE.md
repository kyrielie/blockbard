# BlockBard — Architecture Reference

Quick-parse guide for engineers (and AI sessions) coming in cold.
Covers what exists in the repo today, not what `BLOCKBARD_PLAN.md` describes as original
design intent — that doc predates most of what's actually built and should be treated as
historical context, not a status reference.

**Last verified against source:** updated alongside the fixes in
`BLOCKBARD_ENGINEERING_PLAN.md` Tiers 1-5 (thread safety, tuner retry cap, dead-code
removal, `OrganReadinessChecker` wiring, keyboard input fix, `NbsPlayer` parity,
`LittleEndianReader` EOF handling, auto-rescan guard, config parse logging, rotation
tunables config wiring) — all "Known Issues" entries from the prior pass were resolved
and the doc updated to match in the same pass. If you find a mismatch between this doc and
the actual code, trust the code and fix this doc — that exact failure mode (doc drifting
silently out of sync) is why this file keeps getting rewritten.

---

## Project Identity

| Field | Value |
|---|---|
| Mod name | BlockBard |
| Loader | Fabric |
| MC version | **26.x (`>=26.1 <27.0`)** (compiled against `26.3`, compatible with 26.1, 26.2, 26.3+) |
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
    BlockBardClient.kt          ← ClientModInitializer; wires all subsystems and keybindings together
    command/
      BlockBardCommands.kt      ← Fabric client commands (/blockbard and /bb)
    config/
      BlockBardConfig.kt        ← config data class + ConfigManager (Gson with InstanceCreator & fallback coalescing)
      BlockBardYaclConfig.kt    ← Yet Another Config Lib (YACL) GUI configuration screen
    gui/
      MainScreen.kt             ← primary tabbed GUI (Files, Queue, Organ, Logs, playback transport & controls)
      QueueListWidget.kt        ← "up next" list: single click highlights, double-click plays now; Top, up, down and remove apply to user-queued rows, "+Q" copies a playlist row into the user queue; displays required instrument block icons and author metadata
      SongListWidget.kt         ← searchable song list widget with favorite stars, format badges, required instrument block icons, and author metadata
      LogListWidget.kt          ← in-GUI log entry viewer with search and copy-to-clipboard
      OrganProblemsScreen.kt    ← dedicated organ problem inspector (untunable, occluded, out-of-reach, unplayable notes, suggestions, and highlight controls)
      VirtualKeyboardWidget.kt  ← interactive on-screen piano keyboard widget for live note triggering
      MaterialsShoppingOverlay.kt← survival materials item-stack HUD overlay (shows items needed/in inventory)
      PlaybackHud.kt            ← HUD overlay via HudElementRegistry (checks both MidiFilePlayer and NbsPlayer state)
      VisualizerState.kt        ← runtime note visualizer state for HUD/GUI rendering
      BlockBardModMenuIntegration.kt ← ModMenu config screen entrypoint
    input/
      KeyboardInputHandler.kt   ← keys 1–9 → NoteRequest; direct dispatch from MainScreen.keyPressed()
      MidiInputHandler.kt       ← javax.sound.midi device → NoteRequest (foreign thread synchronized in ArpeggioScheduler); device chosen via /bb midi, live-note block choice in organ/LiveNoteResolver.kt (piano = HARP preferred, then any instrument tuned to the exact pitch, then octave shifts per Shift Mode / Max Octave Shift; no retuning); name matching and input-port selection (a device listed twice under one name resolves to the entry with transmitters) in util/MidiDeviceMatcher.kt
    midi/                       ← package declared as kyrielie.blockbard.midi
      InstrumentRemapper.kt     ← runtime instrument substitution and muting engine
      MidiChannelResolver.kt    ← resolves each MIDI NOTE_ON to NoteBlockInstrument via per-channel GM program tracking
      MidiInstrumentMap.kt      ← GM program (0-127) + percussion → NoteBlockInstrument table
      OrganReadinessChecker.kt  ← coverage pre-flight check (MIDI requirements vs. NoteBlockRegistry)
    organ/
      OrganScanner.kt           ← client world scan (cube or spherical) → NoteBlockRegistry.update()
      OrganProblemCollector.kt  ← aggregates manual tuning failures, occluded blocks, silenced blocks, unreachable blocks, and unplayable note material hints
    playback/
      MidiFilePlayer.kt         ← loads .mid via MidiChannelResolver, dynamic tempo tracking, timed dispatch
      MidiTempoMap.kt           ← dynamic tempo map for handling accelerandos/ritardandos
      PlaybackClock.kt          ← tempo-continuous wall-clock mapping shared by MidiFilePlayer and NbsPlayer
      ClientPlayQueue.kt        ← the one live PlayQueue (outlives MainScreen), applyConfig(), and songIdOf(File)
      PlaybackCoordinator.kt    ← song-end and auto-advance decisions, ticked from END_CLIENT_TICK (works with the GUI closed); PlaybackHost interface
      TuningController.kt       ← owns the tuner run, the organ assignment and the tune-then-play chain; ticked by PlaybackCoordinator
      NbsFileLoader.kt          ← .nbs binary parser AND NbsPlayer; tick getters and live tempoMultiplier parity
      SongMetadataCache.kt      ← async duration, tempo, and track summary metadata caching
      SongAudioPreviewer.kt     ← client-side local sound auditioning without placing blocks or packets
    player/
      PlayerController.kt       ← eased rotation, playNoteAt (left-click), interactWith (right-click tuning), dynamic retuning
      ProtocolMovementGuard.kt  ← 26.2+ protocol guard enforcing single position packet per tick; safe look-only downgrading
      SoundVerifier.kt          ← zero-mixin playback verification via Minecraft SoundEventListener; logs hits/misses/timeouts
      KickDiagnostics.kt        ← disconnect postmortem via ClientPlayConnectionEvents.DISCONNECT; correlates disconnect screen reason
    render/
      OrganOverlayRenderer.kt   ← in-world augmented reality hologram over all organ noteblocks around the player (working vs non-working checks: silenced, out-of-reach, occluded/untunable, in-tune, needs-tuning, spare; organ center standPos cuboid)
      ManualTuningHighlighter.kt← in-world billboard highlighter over blocks that failed automated tuning
      OrganBlueprintBuilder.kt  ← in-world 3D ghost block hologram showing optimal layout for placing missing instruments
    util/
      InstrumentItemHelper.kt   ← maps NoteBlockInstrument to corresponding block ItemStack for GUI previews
      TuningTraceFile.kt        ← JSON-lines tuning trace logging for diagnostic export

  main/kotlin/kyrielie/blockbard/
    organ/
      ArpeggioScheduler.kt      ← token-bucket rate-limited tick queue (Paper 8/310ms limiter); per-note rotation convergence gating; multi-voice round robin; dynamic retuning delegate; dispatchHistory ring buffer
      HungarianAlgorithm.kt     ← pure Kuhn-Munkres (Hungarian) O(n²m) algorithm for optimal bipartite min-cost matching
      InstrumentShifter.kt      ← ShiftMode resolution (EXACT_ONLY/INSTRUMENT_SHIFT/OCTAVE_SHIFT/BEST_EFFORT)
      MidiToOrganMapper.kt      ← (midiNote, instrument) → BlockPos assignment; defines NotePitch and multi-voice pools
      MobHeadBlocks.kt          ← MOB_HEAD_BLOCKS constant set
      NoteBlockEntry.kt         ← data class (pos, instrument, noteIndex, midiNote, status)
      NoteBlockRegistry.kt      ← singleton Map<BlockPos, NoteBlockEntry>
      NoteBlockTuner.kt         ← TuneTarget + tuning state machine (TUNING/VERIFYING/DONE/FAILED); delegates pacing to TuningEngine
      OrganAssignmentSolver.kt  ← pure min-cost organ assignment solver weighting notes by song frequency
      TuningEngine.kt           ← pure closed-loop click pacing (confirmation, timeout/backoff, stall guard, evidence); no MC types
      OrganMap.kt               ← Map<BlockPos, ReachInfo> built by PlayerController.centerOnOrgan()
    playback/
      LoopSettings.kt           ← pure repeat/shuffle rules: migration from the old loopMode, parsing, labels
      PlayQueue.kt              ← pure play-order model (context, shuffle, repeat, user queue, history); no Minecraft types; driven by MainScreen through ClientPlayQueue (see "PlayQueue" below)
    util/
      AimOrdering.kt            ← orderByAngularProximity / wrapDegrees: greedy nearest-angle ordering used to sequence tuning targets
      BlockBardLogBuffer.kt     ← in-memory circular buffer (1000 entries) for the in-game log viewer; getTailText(maxBytes) returns the newest whole entries within a UTF-8 byte budget (clipboard copy is capped at 32 KB because SDL_SetClipboardText copies onto LWJGL's fixed-size thread stack); DEBUG entries stored only when DebugLog.enabled
      DebugLog.kt               ← hot-path conditional logger (routes to debug() when disabled, info() when enabled)
      InstrumentMaterials.kt    ← blockBelowHint mapping for noteblock instruments (dirt, wood, wool, copper, etc.)
      LittleEndianReader.kt     ← DataInputStream extensions for NBS parsing; throws field-specific EOFException on truncation
      ConfigSanitizing.kt       ← pure value rules behind BlockBardConfig.sanitize() (positive-or-fallback, NaN/infinity guards, notes-per-tick and tempo clamps)
      NoteUtils.kt              ← midiBase per instrument (including 26.x copper trumpet variants), noteIndex↔MIDI conversions, clicksNeeded
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
      │  O(r³) cube iteration around the player, reads BlockState — skipped during active
      │  playback/tuning (see Known Issues, "Still open" for the cube-vs-sphere shape note)
      ▼
NoteBlockRegistry        (singleton, Map<BlockPos, NoteBlockEntry>)
      │
      ├── queried by MidiToOrganMapper.buildAssignment(noteUsageCounts, reachableBlocks)
      │         │  delegates to pure OrganAssignmentSolver (Kuhn-Munkres HungarianAlgorithm),
      │         │  weighting missing notes by song frequency, minimizing total tuning clicks,
      │         │  preferring native timbre over instrument shifts, and avoiding untunable blocks
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
      │   NoteBlockTuner (TUNING → VERIFYING → DONE/FAILED state machine; calls PlayerController.interactWith per right-click)
      │
      └── ArpeggioScheduler.assignment = result.assignment   ← wired by TuningController.assign()
                │  consulted at playback time by ArpeggioScheduler.resolvePos()
                │  fallback chain: resolvedPos → assignment[exact NotePitch] → assignment[pitch-only NotePitch] → NoteBlockRegistry.findBestFor() live lookup
                ▼
          ArpeggioScheduler.onTick()  (END_CLIENT_TICK)
                │  token bucket: 8 interacts / 310ms refilled, capped at maxNotesPerTick
                │  gates dispatch on PlayerController.rotationConverged(pos)
                │  records to dispatchHistory ring buffer for KickDiagnostics
                ▼
          PlayerController.playNoteAt(pos, request)
                │  registers expectation with SoundVerifier.expectNote()
                │  re-snaps to final rotation, sends left-click attack (startDestroyBlock + stopDestroyBlock)
                ▼
          SoundVerifier (listens via Minecraft SoundEventListener, verifies audible output vs expectation)
```

**MIDI file → timed events:**

```
MidiFilePlayer.load(file)
      │  reads tempo via raw MetaMessage scan (0x51)
      │  reads notes via MidiChannelResolver.resolve(sequence, name) — reuses the parsed Sequence
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
It eases toward it at a capped rate (`MAX_ROTATION_DEGREES_PER_TICK`, default `35f`
degrees/tick, a `var` — config-wireable via `BlockBardConfig.maxRotationDegreesPerTick`,
set at init) because many anti-cheat plugins flag large single-tick rotation deltas as a
"rotation hack" signature and will silently drop the following use-item packet — even
though the rotation packet itself is accepted and renders correctly on the client, which
makes the bug look like "the player visibly turns to face the block but the click does
nothing," with no client-side error to point at the cause.

`PlayerController.rotationConverged(pos)` reports once the eased rotation is within
`ROTATION_CONVERGENCE_THRESHOLD_DEGREES` (default `2f`, also a `var` — config-wireable via
`BlockBardConfig.rotationConvergenceThresholdDegrees`) of the target.
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
waiting behind it. A separate `rotationInProgressTimeoutMs` (default 1500ms, a `var`,
config-wireable via `BlockBardConfig.rotationInProgressTimeoutMs`) caps a genuinely stuck
rotation (e.g. overridden by something else) so the queue can't deadlock on one note
forever.

Note: `MAX_ROTATION_DEGREES_PER_TICK` and `ROTATION_CONVERGENCE_THRESHOLD_DEGREES` are
top-level properties in `PlayerController.kt` (not members of the `PlayerController`
object), imported by name into `BlockBardClient.kt` to be set at init — same package,
different file.

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
    val shifts: Map<NotePitch, Int>,     // semitone shift applied per note, 0 if native match
    val voicePools: Map<NotePitch, List<BlockPos>> = assignment.mapValues { listOf(it.value) } // multi-voice round-robin pools
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
in `MainScreen.tick()` — the timer is skipped while `MidiFilePlayer.isActive()`,
`NbsPlayer.isPlaying`, or the tuner (`tuner?.isActive`) is active, since rescanning during
playback/tuning serves no purpose. The cube iteration shape itself is unchanged (see Known
Issues, "Still open").

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
Tick-driven queue (`kotlin.collections.ArrayDeque<NoteRequest>`, guarded by a private
`lock` — every public method (`enqueue`, `onTick`, `peekNextPos`, `clear`, `isEmpty`,
`queueSize`, `recentDispatches`) is `synchronized(lock)`, since `enqueue()` is called from a
`javax.sound.midi` device thread while `onTick()`/`peekNextPos()` run on the Minecraft
client thread). Called every tick from `BlockBardClient`'s `ClientTickEvents.END_CLIENT_TICK`.
Key responsibilities:
- **Token-bucket rate limiter:** refills continuously at Paper's standard limit (8 tokens per
  310ms). Burst capped per tick by `maxNotesPerTick` (configurable 1–64, default 8; above 8 only enlarges the post-idle burst).
- **Per-note rotation convergence gating:** applies `rotationConvergedDelegate` to *every* note,
  not just the first. If rotation is still in flight, dispatch halts for the tick and resumes
  next tick so the server always receives a rotation packet between successive interacts.
- **Backlog pruning:** drops backlogged requests sitting behind the head older than `staleTimeoutMs`
  (default 200ms). The active head note is protected up to `rotationInProgressTimeoutMs` (default 1500ms).
- **Dispatch history:** maintains a ring buffer of the last 200 `DispatchRecord` entries (timestamp,
  target block, MIDI note, token budget before/after) accessible via `recentDispatches()`.
- **Delegation:** invokes `interactDelegate: ((BlockPos, NoteRequest) -> Boolean)?` — wired to
  `PlayerController::playNoteAt` at init.

### `PlayerController` (client)
Handles player positioning, rotation easing, and block interaction:
- `centerOnOrgan()` — scans reachable blocks from current player eye position, builds `OrganMap`
  (distance ≤ 4.5 blocks).
- `primeRotation(pos)` — eases player view toward target block at up to `MAX_ROTATION_DEGREES_PER_TICK`
  (default 35°/tick) in `START_CLIENT_TICK`.
- `rotationConverged(pos)` — confirms player facing is within `ROTATION_CONVERGENCE_THRESHOLD_DEGREES`
  (default 2°).
- `interactWith(pos)` — **Right-click (tuning only):** aims at block, uses `gameMode.useItemOn()`,
  advancing the noteblock's `NOTE` property by 1. Requires empty main hand and survival/adventure mode.
- `playNoteAt(pos, request)` — **Left-click (playback):** aims at block, registers expectation with
  `SoundVerifier.expectNote()`, then calls `gameMode.startDestroyBlock()` followed immediately by
  `gameMode.stopDestroyBlock()`. This triggers NoteBlock attack sound without altering block pitch.

### `SoundVerifier` (client, SoundEventListener)
Provides ground-truth playback verification without mixins:
- Registers directly with Minecraft's `SoundManager` via `addListener(SoundVerifier)`.
- Listens to all game sound events; filters for `minecraft:block.note_block.*`.
- Decodes sound pitch back to note index via $12 + 12 \cdot (\ln(\text{pitch}) / \ln(2))$.
- Matches played sounds against FIFO deque of pending `Expectation` objects registered by `playNoteAt()`.
- Flags and logs hits, misses (wrong pitch/instrument), and timeouts (no sound within 250ms).
- Logs periodic rollup summaries every 50 notes.

### `KickDiagnostics` (client)
Captures automated postmortems upon server disconnection:
- Registered on Fabric's `ClientPlayConnectionEvents.DISCONNECT`.
- Extracts disconnect reason from the current Screen via reflection (`getReason`, `reason`, `getMessage`).
- Correlates disconnect reason with `ArpeggioScheduler.recentDispatches()` (last 200 dispatches).
- Dumps packet send volume over 310ms and 1000ms windows, along with token budget states.
- Instantly diagnoses whether disconnect was caused by packet rate limits or server anti-cheat.

### `NoteBlockTuner` and `TuningEngine` (main)
`NoteBlockTuner` (`TunerState`: `IDLE → TUNING → VERIFYING → DONE` or `FAILED`) is a closed loop:
a click counts as applied only when the server's block update shows the note changed. Initial
clicks come from live world reads (`TuneTarget.snapshotNote` is only a fallback). Blocks already
at target are skipped.

Pacing and bookkeeping live in `TuningEngine<K>` (no Minecraft types, unit-tested in
`TuningEngineTest`):
- One focused block at a time; the next click is sent after the previous one is confirmed
  (two in flight with `fastTuning`), with a minimum gap (`clickGapMs` 100 ms, `fastClickGapMs`
  50 ms). Remaining clicks = `clicksNeeded(lastSeen, target) - inFlight`.
- A click unconfirmed after `max(250 ms, 2 * ping + 250 ms)` is a timeout: the block backs off
  (250 ms doubling, capped at 2 s), moves to the back of the queue, and fails after
  `maxTimeoutsPerBlock` (default 5) consecutive timeouts. Any confirmation resets the count.
- A stall guard fails remaining blocks when nothing is clicked or confirmed for
  `max(10 s, 4 * timeout)` (for example the camera never converges).
- When every block reads at target, a confirmation window (`ping*2 + 150 ms`) re-reads all blocks;
  regressions (for example a late-delivered click) are re-tuned up to `maxRetries` (default 3).
- Failures are classified from per-block evidence (`TuningEvidence`): a block that ever advanced
  is `DESYNC`; one that never responded goes through `diagnoseFailState` (geometry), else
  `UNRESPONSIVE`; rejected interactions give `INTERACT_REJECTED`; missing blocks `BLOCK_MISSING`.
- `onTrace` receives one line per event (SENT, CONFIRMED with latency, TIMEOUT, DONE, FAILED).
  The old token bucket was removed: the click gap keeps the rate at or below ~6 interacts per
  310 ms even with `fastTuning`.

### `MidiFilePlayer` (client)
Loads `.mid` files. Tempo read via a direct `MetaMessage` (0x51) scan; notes resolved via
`MidiChannelResolver.resolve(sequence, label)` on the `Sequence` that `parse()` already
read, so a file is read once per `parse()` (`resolve(file)` remains as a wrapper for callers
without a sequence). Every `TimedNoteEvent` carries a resolved `NoteBlockInstrument` —
this is not optional/best-effort; channel 10 is always treated as percussion regardless of
program, and any unset channel defaults to GM program 0 (piano → HARP). Tempo scaling is
read live during playback through `PlaybackClock` (same file package), which anchors
song position to wall time and re-anchors when `tempoMultiplier` changes, so changing the
tempo slider takes effect immediately without shifting time already elapsed.
`tempoMultiplier`, `isPaused` and the play flags are `@Volatile` (GUI thread writes, playback
thread reads). A `seekToTick()` while paused records a pending resume tick; `resume()` then
starts a new playback thread there.

Run identity (both `MidiFilePlayer` and `NbsPlayer`): each `play()` starts a thread tied to a
`runId`; `stop()` bumps `runId` before interrupting. A thread whose id is stale exits without
touching `isPlaying`, `currentTick`, the scheduler or `onFinished`, so an interrupted old
thread cannot stop the run that replaced it (seek while playing is `stop()` then `play()`).
Lifecycle changes, and each check-then-enqueue on the playback thread, run under a private
`lock`. Lock order is player lock, then `ArpeggioScheduler`'s lock; the scheduler never
calls into the players. `onFinished` and `onTickUpdate` run outside the lock, and
`onFinished` fires only for a run that reached its natural end (a stop or seek never
reports completion).

Rotation ownership: `BlockBardClient`'s `START_CLIENT_TICK` handler primes playback rotation
from `ArpeggioScheduler.peekNextPos()` only while no tuner is active
(`TuningController.isActive`). While a tuner runs it is the sole writer of
`PlayerController.primedRotation`. `/bb play` does not check for an active tuner, so notes
queued meanwhile may be delayed by the rotation gate (unverified); the gate in the GUI paths
already prevents this case.

### `NbsFileLoader` / `NbsPlayer` (client — same file)
`NbsFileLoader` parses `.nbs` binary format via `LittleEndianReader`. Version-gated fields
(velocity/panning/pitch, version ≥ 4) are correctly gated — verified against the published
NBS spec. NBS key offset: `midiNote = note.key + 21`. Tempo field is ticks-per-second × 100
(divide by 100.0 for real ticks/sec). `nbsInstrumentToBlock()` maps the 16 standard NBS
instrument byte values (0-15) to `NoteBlockInstrument`; values ≥ 16 are custom
(non-vanilla) instruments and resolve to `null`, falling back to pitch-only matching.

`NbsPlayer` is a **separate `object` in this same file**, not a distinct class — search
here, not for a file named `NbsPlayer.kt`. It now has feature parity with `MidiFilePlayer`:
`getCurrentTick()`/`getTotalTicks()` mirror `MidiFilePlayer`'s getters (consumed by
`PlaybackHud`), and `play()` no longer takes a `tempoMultiplier` parameter — it reads
`MidiFilePlayer.tempoMultiplier` live inside the playback loop each tick (the same single
shared value `MainScreen`'s tempo +/- buttons mutate), so the tempo slider has live effect
on NBS playback already in progress, using the same `PlaybackClock` as `MidiFilePlayer`. `selectFile()`/`startPlayback()` in
`MainScreen` wrap `NbsFileLoader.load()` in try/catch, surfacing a chat warning on a
malformed file rather than propagating an unhandled exception.

### MIDI channel files (client/midi, package `kyrielie.blockbard.midi`)
`MidiChannelResolver` and `MidiInstrumentMap` are live and load-bearing — they're what
`MidiFilePlayer` actually uses to resolve instrument per note (see above).
`OrganReadinessChecker` is wired into `MainScreen.selectFile()`: on selecting a `.mid`/
`.midi` file, `LoadedMidi.noteUsageCounts.keys` (already normalised by `parse()`, unpitched
instruments collapsed to `midiBase`) is the `Set<NotePitch>` passed to `OrganReadinessChecker.check()`, and the result is
shown as a single-line `readinessMessage` in the organ panel (plus a chat warning if
coverage is incomplete) — before the user clicks Tune, not just after.

`FallbackMapper` (`RemappedNoteEvent`, `FallbackPlan`, its own `PITCH_LADDER`
instrument-shift logic) has been **deleted** — it was entirely dead code (confirmed by
grep, zero callers anywhere, including no usage in `MainScreen`'s GUI despite what older
documentation once claimed). It implemented an overlapping but different fallback
strategy from `InstrumentShifter`/`MidiToOrganMapper`, which is the one actually wired in
and remains unchanged.

---

## PlayQueue (`playback/PlayQueue.kt`, `main` source set)

Pure model of what plays next. `MainScreen` drives it through `client/playback/ClientPlayQueue.kt`,
which holds the one live instance (it outlives the screen) and `songIdOf(File)`, the single
file-to-`SongId` mapping (normalized forward-slash relative path within the library). Only the client thread may touch an instance.

Concepts: a **context** (the song ids of the selected playlist, in caller-supplied order),
an **order** (that list, or a stored shuffle permutation), a **cursor** (index of the last
context song played, -1 before the first), a **user queue** (explicit "up next", always before
the context), the **now playing** song and its source, and a bounded **history** (cap 50).
`RepeatMode { OFF, ALL, ONE }` and `shuffle` are independent.

Rules (tested in `PlayQueueTest`):
1. The order is rebuilt only by `setContext`, `setShuffle`, `reshuffle`, `refreshContext`, and `startFromContext` under shuffle.
   Queue edits, `upcoming`, skips, and anything the UI does with selection or search never reorder it.
2. `onSongFinished`: user queue head, else Repeat ONE replays the current song, else the next context song (`cursor + 1`);
   at the end Repeat ALL wraps (new permutation under shuffle, never starting with the song that just ended when there are two or more), Repeat OFF returns null.
3. `skipNext` is the same with Repeat ONE treated as ALL. A null result changes no state.
4. A song played from the user queue never moves the cursor, so the context resumes at `cursor + 1` when the queue drains.
5. `startFromContext(id)`: cursor = index of `id`; under shuffle the order becomes `[id] + shuffled(rest)`. `startFromUser(i)` removes and plays that entry.
   `startFromUpcoming(offset)` jumps the cursor to an upcoming context row without reordering anything (double-click on a context row).
6. `skipPrev` pops the history. Each history entry remembers the context song the cursor sat on while it played, and the cursor goes back to it
   (an anchor no longer in the context leaves the cursor alone). Going back to a queued song and then forward therefore replays the context song after it, not the one after that.
7. `setContext` and `setShuffle` rebuild around the current song (cursor on it, or -1 when it is not in the context).
   `refreshContext` drops missing ids and keeps the cursor on the same song; under shuffle survivors keep their relative order and new ids are shuffled onto the tail.
8. `upcoming(limit)` lists user rows then context rows after the cursor; it never invents the order after a wrap. `wrapsAtEnd` and `repeatingCurrent` flag those cases for display.
9. Coordinator duties, not `PlayQueue` duties: skipping songs that fail to load (cap 5 in a row), calling `stop()` on a null result, and persisting settings.

Additions beyond the plan's API sketch: `stop()`, `startFromUpcoming`, `contextOrder`, `contextCursor`, `HISTORY_CAP`.

### How `MainScreen` uses it

- **Context** is the whole library (`midiFiles`), pushed with `refreshContext` from `refreshFiles()`. The filter buttons and the
  search box only change what the Files tab shows; they never change what plays next.
- **Settings** (`repeatMode`, `shuffle`) reach the live queue through `ClientPlayQueue.applyConfig`, called at client init, from the
  YACL save lambda, from `/bb repeat` and `/bb shuffle`, and from the two GUI buttons (Loop cycles OFF, ALL, ONE; Shuf toggles shuffle).
  `setShuffle` ignores an unchanged value, so saving settings without touching shuffle does not reorder the queue.
- **Starting songs:** Tune & Play and a double-click on a library row call `startFromContext` (unless that song is already the now-playing one);
  Next and Prev call `skipNext` and `skipPrev`; a double-click on a queue row calls `startFromUser` or `startFromUpcoming`.
  Skip and queue starts reach `playSelectedSong(fromQueue = true)` so the queue is not told twice.
- **Song end:** see "PlaybackCoordinator" below. The screen no longer decides what plays next; it only acts when the coordinator asks it to start a song.
- **Queue actions:** library rows have **Next** (`enqueueNext`) and **Last** (`enqueueLast`); the Files tab has **Queue view** (every song in `displayedFiles`, in view order, appended).
  User rows have Top (`moveUserToFront`), up, down and remove; playlist rows have "+Q" (`promote`, a copy appended to the user queue). Playlist rows cannot be removed or reordered.
  Hover hints replace the row's subtext line (rows have no tooltip API in use here).
- **Display:** a single click on a queue row only highlights it. Only user-queued rows have reorder and remove controls; context rows follow the playlist order.
  `updateQueueDisplay()` caches the rows and counts so the per-frame renderers do not copy the queue.

### `PlaybackCoordinator` (`client/playback/PlaybackCoordinator.kt`)

Decides what happens when a song ends, whether or not the GUI is open. It is installed once at client init (`install()` wires the playback-thread
callbacks) and ticked from `END_CLIENT_TICK` in `BlockBardClient`. Its tick first ticks `TuningController`.

- **Thread rule:** `MidiFilePlayer.onFinished` and `NbsPlayer.onFinished` (playback thread) only set `songEnded`. `tick()` (client thread) consumes it and is the only
  place the `PlayQueue` is asked what follows a finished song.
- **Song end:** `handleSongEnded()` asks `queue.onSongFinished()` (skipping up to 5 files that no longer exist, then going idle) and arms an advance. The armed advance waits
  until no tuner is active and both players are idle, then calls the host: `beginAdvance(file)` (select, retune, then play) for a different song, or `replayCurrent()`
  for the same song again (Repeat ONE or a one-song playlist), which needs no retune.
- **Host:** loading a song and starting playback still live in `MainScreen`, which implements `PlaybackHost` (`fileForId`, `startQueuedSong`, `beginAdvance`, `replayCurrent`,
  `startPlayback`, `onTuningComplete`, `onQueueChanged`) and registers itself in `init()`. The registration is kept after the screen closes, so auto-advance works with the GUI closed.
  Until a screen has been opened once in a session there is no host: a song that ends then simply goes idle. The tuner run no longer needs the host except to start playback
  and refresh the organ info afterwards.

### `TuningController` (`client/playback/TuningController.kt`)

Owns the tuner run so it behaves the same with the GUI open or closed. Client thread only.

- **State:** `tuner`, `isActive`, `assignment` (the organ assignment for the song last tuned for or played), `statusMsg`, `error`, `coverageMessage`, and the private play-after-tune flag.
  The screen reads these; it holds no tuner, assignment or auto-play state of its own.
- **`start(source, thenPlay)`:** `MainScreen.startTuning` decides the `Source` (`Song(label, noteUsageCounts)` for the loaded MIDI or NBS, or `Chromatic`) and calls this. It does the
  refusals (creative or spectator mode, hotbar and offhand, no Center, nothing in reach), assignment, target ordering, trace file and tuner construction. The play-after-tune flag is set
  only when a run actually starts (a refused start no longer leaves it armed).
- **`tick()`** (from `PlaybackCoordinator.tick`): ticks the tuner. On success it rescans, calls `host.onTuningComplete()` and, if requested, `host.startPlayback()`. On failure it records
  `error`, warns in chat and drops the play-after-tune request.
- **`abort()`:** cancels the run and the play-after-tune request (called by `PlaybackCoordinator.stop()`); it does not clear `error`, the Stop button does via `clearError()`.
- **`assign(noteUsageCounts, reachable)`:** the shared assignment step used by tuning and by `startPlayback` when no assignment exists yet; also builds the coverage message
  (`formatCoverageMessage` in `util/CoverageMessage.kt`, pure and unit-tested).
- Tuner ticking moved from the screen's `tick()` and the companion `MainScreen.onClientTick` to END_CLIENT_TICK for both cases. Chat feedback without a screen goes through `client/util/Notifier.kt`.
- **Stop** (Stop button and `/bb stop`) calls `PlaybackCoordinator.stop()`: cancels a pending advance, aborts any tuner run (so `/bb stop` now really stops tuning, as its message says), stops both players
  and clears now-playing.
- **`/bb play`** calls `beginExternalPlay(file)` first. It refuses while a tuner is running (starting playback mid-tune would fight the tuner for rotation and block interactions), and otherwise registers the song with the queue
  (`startFromContext`) so its end, Next and Prev follow the play order. If the song is not in the playlist (the screen has not listed the library yet) the queue's now-playing is cleared instead of left stale.
- **`lastPlayedTrack`** is written when playback starts (`onPlaybackStarted`, from `startPlayback` and `/bb play`), not when a row is merely selected. On opening the screen the Files tab selects the last song that was played.
- Not yet moved (still `MainScreen` methods, because they need its selection and tuner state): library double-click and Tune & Play (`startFromContext` plus the tune chain), Next and Prev (`skipNext` / `skipPrev`), queue row play-now.

---

## Initialization Sequence (`BlockBardClient.onInitializeClient`)

1. `ConfigManager.load()` — reads `config/blockbard/config.json` (plain Gson; a malformed
   file is caught, logged via `logger.warn` with the exception, and reset to defaults —
   see the Gson caveat in the Config section below for a related but distinct risk)
2. Applies config to `OrganScanner.scanRadius`, `InstrumentShifter.mode`/`maxOctaveShift`,
   `ArpeggioScheduler.staleTimeoutMs`/`rotationInProgressTimeoutMs`,
   `PlayerController.MAX_ROTATION_DEGREES_PER_TICK`/`ROTATION_CONVERGENCE_THRESHOLD_DEGREES`
3. Wires two `ArpeggioScheduler` delegates: `interactDelegate = { pos -> PlayerController.interactWith(pos) }`
   and `rotationConvergedDelegate = { pos -> PlayerController.rotationConverged(pos) }`
4. Registers keybindings: `B` (open GUI), `H` (toggle HUD)
5. `KeyboardInputHandler.register()`, `PlaybackHud.register()`
6. `ClientTickEvents.START_CLIENT_TICK` — primes rotation toward the next pending note
   (`ArpeggioScheduler.peekNextPos()?.let { PlayerController.primeRotation(it) }`)
7. `PlaybackCoordinator.install()` — wires the playback-thread song-end callbacks
   `ClientTickEvents.END_CLIENT_TICK` — polls the GUI/HUD keybindings,
   `PlaybackCoordinator.tick()` (ticks `TuningController`, then song end and auto-advance), then `ArpeggioScheduler.onTick()` (the actual dispatch-gated-on-convergence step)
8. `MidiInputHandler.autoConnect()` — logs the input devices Java can see, then attempts to open the saved `midiDeviceName`
   (no-op with an INFO line when unset; set it with `/bb midi connect <name>`). `MidiInputHandler.tickRetry()` runs from
   `END_CLIENT_TICK` and retries a saved-but-unconnected device every 10 s. Its receiver
   callback runs on a foreign (non-Minecraft) thread, synchronized against
   `ArpeggioScheduler`'s queue (see `ArpeggioScheduler` subsystem summary above)

---

## Config (`BlockBardConfig`)

**Plain Gson serialization to `config/blockbard/config.json`** — there is no Cloth Config
or AutoConfig dependency involved in how this mod persists its own settings (Cloth Config
is listed in `BLOCKBARD_PLAN.md` as a mods-folder runtime dependency for a config-screen
UI library, which is a different thing; verify against that doc's actual context if this
distinction matters for your task).

Current fields (`BlockBardConfig.kt`):
`scanRadius: Int = 5`, `sphericalScan: Boolean = true`, `shiftMode: String = "BEST_EFFORT"`,
`maxOctaveShift: Int = 1`, `reportShiftsInHud: Boolean = true`, `hudEnabled: Boolean = false`,
`arpeggioStaleTimeoutMs: Long = 200L`, `defaultTempoMultiplier: Float = 1.0f`,
`autoRescanIntervalSeconds: Int = 3`, `maxTuningClicksPerTick: Int = 1` (deprecated, never read by the tuner, kept for one release),
`tuningClickIntervalTicks: Int = 2` (minimum ticks between tuning clicks; `fastTuning` halves it and allows two clicks in flight per block),
`lastPlayedTrack: String?`, `shuffleHistory: MutableList<String>` (no longer read or written; kept so old files load), `defaultOctave: Int = 4`,
`midiDeviceName: String?`, `keyMappings: List<Int> = [54..62]` (MIDI notes for keys 1-9),
`smoothCameraRotation: Boolean = false`, `fastTuning: Boolean = true`,
`maxRotationDegreesPerTick: Float = 35f`, `rotationConvergenceThresholdDegrees: Float = 2f`,
`rotationInProgressTimeoutMs: Long = 1500L`, `debugLogging: Boolean = false`,
`maxNotesPerTick: Int = 8` (range 1–64 burst cap), `repeatMode: String?` (OFF, ALL, ONE; `null` = file predates the field), `shuffle: Boolean = false`,
`loopMode: String = "NONE"` (DEPRECATED, kept one release for downgrades: read once to migrate, never rewritten),
`instrumentRemap: MutableMap<String, String?>`, `favorites: MutableList<String>`,
`activeFilter: String = "ALL"`, `dynamicRetuning: Boolean = false`, `dynamicRetuningMaxClicks: Int = 2`,
`centerMovesPlayer: Boolean = false`, `enableChatFeedback: Boolean = false`.

**Caveat affecting fields with non-trivial defaults:**
This project uses plain `Gson` (`com.google.code.gson:gson:2.10.1`, with an `InstanceCreator<BlockBardConfig>`)
to deserialize `BlockBardConfig`. Missing keys keep the constructor default, which is why `repeatMode` defaults to `null`:
`null` is the only way to tell an old file from a new one.
`ConfigManager.load()` then runs `BlockBardConfig.sanitize()` (zero, negative, NaN, infinite or null values become safe ones; rules are the pure
helpers in `util/ConfigSanitizing.kt`: positive-or-fallback for `scanRadius`, `arpeggioStaleTimeoutMs`, `maxRotationDegreesPerTick`,
`rotationConvergenceThresholdDegrees`, `rotationInProgressTimeoutMs`, `autoRescanIntervalSeconds`, `maxTuningClicksPerTick`;
`tuningClickIntervalTicks` 1-20 with 2 as the fallback; `maxNotesPerTick` 1-64 with 8 as the fallback; `defaultTempoMultiplier` non-finite or <= 0 → 1.0, then clamped 0.5–2.0; null `shuffleHistory` and `instrumentRemap` replaced by empty collections)
and `migrateLoopSettings()` (see `LoopSettings.resolve`), saving the file once when a migration happened.
Repeat and shuffle are two settings (`repeatMode`, `shuffle`) that feed the live `PlayQueue` through `ClientPlayQueue.applyConfig`.
A planned architectural improvement is migrating to `kotlinx.serialization` with type-safe serializers.

---

## Known Issues

All issues previously tracked here (thread-unsafe `ArpeggioScheduler` queue, uncapped
`NoteBlockTuner` retries, keyboard 1-9 not registering while `MainScreen` is open, dead
`FallbackMapper` code, unwired `OrganReadinessChecker`, `NbsPlayer`/`MidiFilePlayer`
feature-parity gaps, `PlaybackHud` not checking `NbsPlayer`, `LittleEndianReader`'s
missing EOF handling, the unconditional auto-rescan timer, and `ConfigManager` swallowing
parse exceptions silently) have been fixed — see the subsystem summaries above and
`BLOCKBARD_ENGINEERING_PLAN.md` for the original fix rationale per item.

**Current Architectural Nuances:**
- **Gson + Kotlin data class defaults** (see Config section above) — guarded at runtime via
  `InstanceCreator` and explicit coalescing fallback in `ConfigManager.load()`.
- **Spherical vs. Cubical Scan Optimization**:
  `OrganScanner.scan()` supports configurable `sphericalScan: Boolean = true` (default). When enabled,
  it checks Euclidean distance `x² + y² + z² <= r²`, reducing blockstate inspect iterations by ~48%
  compared to the full cube scan. Players who deliberately place organ blocks in cube corners outside
  the sphere can toggle `sphericalScan = false` in settings.

---

## Build Commands

```bash
./gradlew build          # full build → build/libs/
./gradlew runClient      # dev client with mod loaded
./gradlew test           # unit tests (src/test/kotlin); also run by `build`
```

### Unit tests

JUnit 6.1.2 (`junit_version` in `gradle.properties`, BOM-pinned in `build.gradle`) runs on the
JUnit Platform; `test { }` logs passed, skipped and failed tests and standard output.

Result of the first run (`TestClasspathProbeTest`, owner-reported output from `./gradlew clean test`):

| Probe | Result |
|---|---|
| `main` pure Kotlin (`NoteUtilsKt`) | loads |
| `main` organ package (`NoteBlockEntry`) | loads |
| client `PlaybackClock` (`internal`), `MidiTempoMap` | load at runtime |
| Minecraft `NoteBlockInstrument` | on the classpath, but its static initializer fails (`NoClassDefFoundError: Could not initialize class`; root cause not captured on that run) |

What follows from it:

- Pure `main` code is unit-testable (`PlayQueue`, `NoteUtils` functions that do not touch an instrument).
- The client source set is on the test runtime classpath, so `PlaybackClock` and `MidiTempoMap` did not need to move to `main`.
  Not verified: whether test source can compile against the client set's `internal` symbols. The probe only loads classes by name.
- Code that touches `NoteBlockInstrument` (the `midiBase`, `isPitched` and `coversNatively` extensions in `NoteUtils.kt`, `NoteBlockEntry` instances) cannot be exercised until the failure is explained;
  the probe now prints the full cause chain, so the next run records it. A likely cause is that Minecraft needs bootstrap before registry-backed types initialize (**Unverified**).
  Until then the Minecraft-dependent assertion in the probe is skipped, not failed, and allocator tests that need `NoteBlockEntry` stay blocked.

Rule of thumb: keep every unit under test free of Minecraft types and place it in the `main` source set
(see `blockbard-roadmap-plan.md` section 8).

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
| Change play order, repeat, shuffle or the queue | `playback/PlayQueue.kt` and `PlayQueueTest.kt` (pure), then `client/playback/ClientPlayQueue.kt` and the queue methods in `MainScreen.kt` |
| Add a config option | `BlockBardConfig.kt`, then wire it in `BlockBardClient.onInitializeClient()` |
| Debug MIDI file issues | `MidiFilePlayer.kt`, `midi/MidiChannelResolver.kt`, `midi/OrganReadinessChecker.kt` (wired into `MainScreen.selectFile()`) |
| Debug NBS file issues | `NbsFileLoader.kt` (parser + `NbsPlayer`), `LittleEndianReader.kt` (throws field-specific `EOFException` on truncation) |
| Debug instrument mismatches during playback | `NotePitch`, `ArpeggioScheduler.resolvePos()`'s fallback chain |