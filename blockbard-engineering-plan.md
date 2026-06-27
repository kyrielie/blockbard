# BlockBard — Engineering Plan for Next Engineer

**Read this first, before `ARCHITECTURE.md` or `BLOCKBARD_PLAN.md`.** Those two docs are
stale relative to the actual code and will mislead you on several specific points — see
"Doc trust calibration" at the bottom before consulting them for anything.

Verified against the actual current source (repomix-output.xml, 44 files). Build currently
compiles successfully (`./gradlew build`) — confirmed via two compile-error round-trips in
prior sessions, both now resolved. This plan assumes you are starting from a clean,
building codebase, not debugging a broken build.

Target: Minecraft 26.2, Fabric, unobfuscated Mojang names (no Yarn mapping layer needed —
see `gradle.properties`). Kotlin, client-only mod, no server-side code.

---

## How to use this plan

Each item below is independently shippable — implement and verify one before moving to
the next rather than batching. Items are ordered by priority within each tier, but tiers
themselves should be done in order (don't start Tier 2 with open Tier 1 items, since
several Tier 2 items assume Tier 1 fixes are already in place).

For every item: read the referenced file(s) in full first — do not pattern-match from this
plan's description alone, since exact line numbers will have shifted by the time you reach
this. Verify the actual current signatures before writing any fix. Do not invent Minecraft
API behavior; this project's `CLAUDE.md` instructions require verifying APIs by reading
code or docs before asserting them — that applies to you too.

---

## Tier 1 — Correctness bugs (do these first; everything downstream assumes the queue is sound)

### 1.1 Thread-safety: MIDI input races with the scheduler tick

**File:** `client/input/MidiInputHandler.kt`, `main/organ/ArpeggioScheduler.kt`

`MidiInputHandler.connect()` installs a `Receiver` whose `send()` callback fires on a
MIDI-device thread (owned by `javax.sound.midi`, not Minecraft). It calls
`ArpeggioScheduler.enqueue(...)` directly. `ArpeggioScheduler`'s queue is a
`kotlin.collections.ArrayDeque`, not thread-safe, and `onTick()`/`peekNextPos()` run
concurrently on the Minecraft client thread via `END_CLIENT_TICK`/`START_CLIENT_TICK`.
This is a real, live data race whenever a physical MIDI keyboard is connected.

**Fix:**
- Add a `private val lock = Any()` to `ArpeggioScheduler` and wrap every method that
  touches `queue` (`enqueue`, `onTick`, `peekNextPos`, `clear`, `isEmpty`, the `queueSize`
  getter) in `synchronized(lock) { ... }`.
- Alternative if you'd rather avoid lock contention on the hot tick path: have
  `MidiInputHandler`'s receiver push into a separate `java.util.concurrent.ConcurrentLinkedQueue<NoteRequest>`,
  and have `ArpeggioScheduler.onTick()` drain that queue into its own `ArrayDeque` at the
  top of each tick (single-threaded from that point on). This avoids locking the
  performance-sensitive tick path at the cost of one extra queue.
- Either approach is acceptable; prefer `synchronized` for simplicity unless profiling
  shows contention.

**Verify:** connect a real or virtual MIDI input device, play notes rapidly while a song
is also queued via `MidiFilePlayer`, confirm no `ConcurrentModificationException` in logs
over an extended session (10+ minutes of mixed input).

### 1.2 NoteBlockTuner retry loop has no exit condition

**File:** `main/organ/NoteBlockTuner.kt`

`tickVerifying()` clears `notePredictions` and sets `state = TunerState.TUNING` on any
mismatch with no retry counter. A block that can never converge (anticheat dropping
packets server-side, a block destroyed mid-tune and respawned elsewhere, persistent lag)
cycles `TUNING ↔ VERIFYING` forever. The GUI shows "Retrying N mismatched blocks..."
indefinitely with no way out short of closing the screen.

**Fix:**
- Add `private var retryCount: Int = 0` and `private val maxRetries = 3` (or make it a
  constructor parameter, defaulting to 3, for testability).
- Reset `retryCount = 0` in `start()`.
- In `tickVerifying()`'s mismatch branch, increment `retryCount` before retrying; if
  `retryCount > maxRetries`, set `state = TunerState.FAILED` instead of looping back to
  `TUNING`, and report exactly which `target.pos`s never converged in the `onProgress`
  message (the `mismatches` list is already built — just don't discard it on the final
  failure).

**Verify:** construct a `NoteBlockTuner` in a unit test (or manually, via a fake
`worldNoteReader` that never returns the target note for one specific `pos`) and confirm
it reaches `FAILED` after exactly `maxRetries + 1` verification attempts, not before and
not never.

---

## Tier 2 — Dead/duplicate code (resolve before adding new features on top of either path)

### 2.1 Two competing instrument-fallback systems exist; only one is wired in

**Files:** `client/midi/FallbackMapper.kt` (unwired), `main/organ/InstrumentShifter.kt` +
`main/organ/MidiToOrganMapper.kt` (wired, used by `MainScreen.startTuning()`)

`FallbackMapper.plan()` has zero callers anywhere in the codebase — confirmed by grep
across every `.kt` file. It implements its own ladder-walk instrument-shift + octave-clamp
logic (`PITCH_LADDER`, `instrumentShift()`, `octaveClamp()`), which overlaps but does not
match `InstrumentShifter`'s native-match → instrument-shift → octave-shift logic (already
wired into `buildAssignment()` in `MidiToOrganMapper.kt`).

**Decision needed before coding:** pick one. Recommendation: keep `InstrumentShifter` +
`MidiToOrganMapper` (already integrated, already handles the `NotePitch`
instrument-keying from the last refactor) and delete `FallbackMapper.kt` entirely, along
with `RemappedNoteEvent`/`FallbackPlan` if nothing else references them (re-grep before
deleting — `ARCHITECTURE.md` claims the GUI uses `FallbackPlan` for a warning display, but
that claim should be verified against actual `MainScreen.kt` usage, not assumed; if no
real GUI usage exists, it's safe to delete).

**If you instead decide `FallbackMapper`'s pitch-ladder approach is better** (it does have
one real advantage: it operates on whatever's *actually present* in the organ rather than
walking instrument ranges abstractly), then the task inverts: wire `FallbackMapper.plan()`
into `MainScreen.startTuning()` in place of the `InstrumentShifter` calls, and remove
`InstrumentShifter`/the shift logic in `MidiToOrganMapper` instead. Do not keep both.

**Either way:** after removing one, re-run the full import/symbol cross-check (see
"Verification methodology" below) since deleting a file changes what's importable.

### 2.2 OrganReadinessChecker is built but never invoked

**File:** `client/midi/OrganReadinessChecker.kt`, `client/gui/MainScreen.kt`

Cross-references a loaded MIDI file's required `(instrument, midiNote)` pairs against
`NoteBlockRegistry`. Zero callers. Currently the only way to discover coverage gaps is to
run `startTuning()` and read the `unplayable` list it produces after the fact.

**Fix:** call `OrganReadinessChecker` (check its actual current method signature first —
don't assume it matches `ARCHITECTURE.md`'s description) from `MainScreen` right after a
MIDI file is selected (in `selectFile()`, alongside the existing `MidiFilePlayer.load(file)`
call), and surface the result as a chat message or a coverage line in the GUI — before the
user clicks Tune, not after. This turns a post-hoc discovery into a pre-flight check.

**Verify:** load a MIDI file that uses an instrument range your test organ doesn't cover;
confirm the gap is reported immediately on file selection, not only after tuning.

---

## Tier 3 — Input handling

### 3.1 Number keys 1-9 do not register while MainScreen is open

**File:** `client/gui/MainScreen.kt`, `client/input/KeyboardInputHandler.kt`

Root cause (verified by reading both files in full): `KeyboardInputHandler` relies on
`KeyMapping.consumeClick()`, the polling mechanism for gameplay keybinds active when no GUI
owns input focus. While `MainScreen` is open — required for these keys to be "active" per
the class's own design — raw key events are first handled by `Screen`'s input pipeline.
`MainScreen` currently overrides `mouseClicked` but **never overrides `keyPressed` at
all**, so there is no code path through which a 1-9 press while the screen is open can
register on the `KeyMapping`'s click-counter the way it would during ordinary gameplay.

**Fix:**
- Override `keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean` directly in
  `MainScreen` (check the exact current method signature against whatever Screen base
  class this Minecraft version uses — do not assume the 1.20-era signature is identical;
  this project is on 26.2).
- Inside the override, check `keyCode` against `GLFW.GLFW_KEY_1` through
  `GLFW.GLFW_KEY_9` (or whatever range `ConfigManager.config.keyMappings` actually
  represents — re-read `KeyboardInputHandler.kt`'s current mapping comment for the
  authoritative key→MIDI table before wiring this).
- Call `ArpeggioScheduler.enqueue(NoteRequest(midiNote))` directly from the override,
  matching what `KeyboardInputHandler.onTick()` currently does — you can either move that
  logic into `MainScreen` entirely and delete the polling-based version in
  `KeyboardInputHandler`, or keep `KeyboardInputHandler` as a shared helper function called
  from both places. Prefer consolidating into one path to avoid double-handling if the
  `KeyMapping`-based path turns out to fire in some circumstances after all.
- Return `true` when a key was consumed so it doesn't also fall through to vanilla
  hotbar-slot switching.

**Verify:** open MainScreen, press 1-9, confirm chat messages and noteblock clicks fire —
this was the original unresolved symptom from the start of this project's debugging
history and has never actually been root-caused or fixed until this item.

---

## Tier 4 — Feature parity between MidiFilePlayer and NbsPlayer

The two playback paths have drifted; NBS is missing capabilities MIDI has.

### 4.1 NbsPlayer has no tick/progress getters

**File:** `client/playback/NbsFileLoader.kt` (contains `object NbsPlayer` — it is not a
separate file, despite what `ARCHITECTURE.md`'s "not yet implemented" list claims; verify
this yourself by grepping for `object NbsPlayer` before doing anything else in this item,
since trusting the stale doc here would send you looking for a nonexistent file).

`MidiFilePlayer` exposes `getCurrentTick()`/`getTotalTicks()`; `NbsPlayer` has neither.

**Fix:** add `currentTick`/`totalTick` tracking to `NbsPlayer`, mirroring
`MidiFilePlayer`'s pattern — a private var updated inside the playback loop each time a
tick's notes are dispatched, plus public getters. `NbsFile.notes.last().tick` (need to
confirm this is sorted — check `NbsFileLoader.load()`'s actual note-ordering guarantee
before relying on `.last()`) gives total ticks.

### 4.2 NbsPlayer's tempo multiplier is captured once at play() start, not read live

**File:** `client/playback/NbsFileLoader.kt`

`tickDurationMs` is computed once in `play()` from the `tempoMultiplier` parameter and
baked into `targetMs = startWallMs + tick * tickDurationMs` for the rest of playback.
`MidiFilePlayer` recomputes its scaled timing fresh per event
(`tickToScaledMs(..., tempoMultiplier)` inside the loop), so its tempo slider has live
effect; NBS's does not.

**Fix:** restructure the wait loop to recompute the target time per-tick from a live
tempo value (read `MidiFilePlayer.tempoMultiplier` fresh, the same shared value
`MainScreen` already passes in, rather than the captured parameter) rather than baking a
fixed `tickDurationMs` once. Match `MidiFilePlayer`'s approach precisely so both players
behave identically under a live tempo change — read its actual current loop structure
first since this plan's earlier description of it may not reflect later edits.

### 4.3 PlaybackHud doesn't know NbsPlayer exists

**File:** `client/gui/PlaybackHud.kt`

`render()` only checks `MidiFilePlayer.isActive()`/`isPaused`. During NBS playback it
always falls through to the idle branch. This is the same class of bug already fixed in
`MainScreen`'s in-GUI status bar (check that fix's exact branching logic in `MainScreen.kt`
and mirror it here) — but `PlaybackHud` is a separate render path and was never updated.

**Fix:** add `NbsPlayer.isPlaying`/`isPaused` branches matching the pattern already used
in `MainScreen`, plus (once 4.1 is done) NBS tick/total display analogous to the existing
MIDI tick display.

**Do 4.1 before 4.3** — 4.3 needs the getters from 4.1 to show real progress, not just a
playing/paused state.

---

## Tier 5 — Robustness

### 5.1 LittleEndianReader has no EOF handling

**File:** `main/util/LittleEndianReader.kt`

`DataInputStream.read()` returns `-1` at end-of-stream. `readLEShort()`/`readLEInt()`
don't check for it — a truncated/corrupted `.nbs` file silently produces garbage
tick/key/instrument values baked from `-1` bytes instead of failing cleanly.

**Fix:** after each `read()` call, check for `-1` and throw
`java.io.EOFException("Unexpected end of NBS file while reading ...")` with a description
of what was being read (tick, key, instrument byte, etc. — be specific per call site, not
one generic message) rather than letting `-1` flow into the bit-shift math. Confirm
`NbsFileLoader.load()`'s caller in `MainScreen.kt` already wraps the load call in
something that surfaces an exception as a chat warning rather than crashing the GUI — if
it doesn't, add a `try/catch` there too so a bad file produces a clear in-game message
instead of a silent failure or an unhandled crash.

### 5.2 OrganScanner cost scales as O(r³), runs on the client thread, on a timer, even during playback

**File:** `client/organ/OrganScanner.kt`, `client/gui/MainScreen.kt`

`scan()` is a triple-nested loop over `(2r+1)³` blocks (up to 9261 at the documented max
radius 10), each iteration calling `world.getBlockState()` twice. Runs synchronously on
the client thread. `MainScreen` triggers it automatically every
`autoRescanIntervalSeconds` (default 3s, per current `BlockBardConfig.kt` — verify this
default hasn't changed), including while a song is playing.

**Fix (pick at least one, b is cheapest and highest-value):**
- (a) Replace the cube iteration with a sphere check (`if (x*x + y*y + z*z > r*r) continue`)
  — cuts roughly 48% of iterations at radius 5+ for free.
- (b) Skip the automatic rescan entirely while `MidiFilePlayer.isActive()` or
  `NbsPlayer.isPlaying` or a `NoteBlockTuner` is active — re-scanning while actively
  playing or tuning serves no purpose and is the worst time to spend client-thread budget.
  Check `MainScreen`'s tick method for where the auto-rescan timer fires and add this
  guard there.
- (c) Raise the default `autoRescanIntervalSeconds` or convert it to "only auto-rescan when
  idle and the screen has been open more than N seconds," reserving manual rescans (the
  Scan button) for the rest.

Implement (b) at minimum — it's a small, low-risk change with a clear, immediate benefit
and no behavioral downside (there's never a good reason to rescan mid-playback).

### 5.3 Config silently swallows parse errors

**File:** `client/config/BlockBardConfig.kt`

`ConfigManager.load()`'s `catch (_: Exception)` discards the actual exception and resets
to defaults with no log line. A malformed `config.json` gives zero indication of why
settings reverted.

**Fix:** change to `catch (e: Exception) { logger.warn("Config parse failed, resetting to defaults: ${e.message}", e); ... }`
— add a `private val logger` if one doesn't already exist in this object (check current
state; `ARCHITECTURE.md` doesn't mention one).

### 5.4 Rotation/anticheat tunables are hardcoded, unlike every comparable config value

**Files:** `client/player/PlayerController.kt` (`MAX_ROTATION_DEGREES_PER_TICK`,
`ROTATION_CONVERGENCE_THRESHOLD_DEGREES`), `main/organ/ArpeggioScheduler.kt`
(`rotationInProgressTimeoutMs`)

Every other comparable tunable (`arpeggioStaleTimeoutMs`, `scanRadius`, `maxOctaveShift`)
is config-driven, set at init in `BlockBardClient.onInitializeClient()`. These three
constants control anticheat-compatibility behavior directly — exactly the kind of value
that needs per-server tuning — but are hardcoded.

**Fix:** add corresponding fields to `BlockBardConfig` with the current hardcoded values
as defaults, and wire them in `BlockBardClient.onInitializeClient()` the same way
`arpeggioStaleTimeoutMs` already is. Check `PlayerController.kt`'s current declaration
style (`const val` vs `var`) — moving from `const val` to a settable `var` is required for
this to be configurable at runtime, so this touches the declaration, not just the value.

---

## Tier 6 — Documentation debt (do last, once the above is stable)

### 6.1 ARCHITECTURE.md is stale and actively misleading on several points

Specific confirmed contradictions with current code (verify each yourself before fixing,
since the actual code may have moved again by the time you read this):

- Claims `OrganAssignment.assignment: Map<Int, BlockPos>` — actual current type uses a
  `NotePitch` key (instrument-aware), not bare `Int`.
- Claims `ArpeggioScheduler.interactDelegate: ((BlockPos) -> Unit)?` — actual signature
  returns `Boolean`, and there's also a `rotationConvergedDelegate` that gates dispatch,
  entirely unmentioned.
- Describes `PlayerController.interactWith` as a simple rotate-then-click with no mention
  of the multi-tick rotation-easing/convergence mechanism — the single most significant
  behavioral mechanism in the player-control subsystem, completely undocumented.
- Describes `MidiFilePlayer` as instrument-unaware (`List<TimedNoteEvent>` with no
  instrument field) — it now resolves instrument per event via `MidiChannelResolver`.
- **Explicitly lists `NbsPlayer` under "Not Yet Implemented"** — `NbsPlayer` exists, is
  fully wired into `MainScreen`, and is one of the two primary playback paths. This is the
  most actively misleading line in the doc and should be fixed first within this item.
- Claims config persists "via Cloth Config / AutoConfig" — actual implementation is plain
  Gson serialization with no Cloth Config dependency visible in `BlockBardConfig.kt`.
- States MC version 1.21.1 with Yarn mappings — actual `gradle.properties` targets 26.2
  with no mapping layer (unobfuscated Mojang names natively, per this Minecraft version's
  own packaging).

**Fix:** rewrite the affected sections rather than patch individual lines — given the
volume of drift, a full pass re-reading every current source file and rewriting the
corresponding `ARCHITECTURE.md` section is more reliable than line-editing a doc that's
already proven to drift silently. Add a line at the top noting the doc's last-verified
state (date or commit) so future readers can judge staleness risk themselves.

### 6.2 BLOCKBARD_PLAN.md is the pre-implementation design doc, not a living spec

Not inaccurate exactly, but easy to mistake for current-state documentation since nothing
in the doc itself flags it as historical. Confirm with whoever maintains this repo whether
it should be archived (e.g. moved to `docs/history/` or prefixed `[HISTORICAL]`) now that
`ARCHITECTURE.md` exists as the "what's actually built" reference, to prevent the same
confusion that affected this plan's own research phase.

---

## Verification methodology (apply to every item above)

This codebase has hit the same class of mistake twice already in its history: an edit
that looks correct in isolation but breaks at compile time because a type or import
wasn't checked against its actual current cross-file usage. Before considering any item
above complete:

1. **Re-read the actual current file** — not this plan's description of it — immediately
   before editing. Multiple edits may have landed between when this plan was written and
   when you act on it.
2. **Grep for every other usage of anything you change** — a changed type, removed
   function, or renamed field needs every call site checked, not just the one you're
   editing. `grep -rn "SymbolName" src --include="*.kt"` across the whole `src/` tree, not
   just the file you're touching.
3. **Check imports explicitly** — adding a call to a new extension function or top-level
   symbol from another package requires an explicit import unless it's same-package or
   covered by an existing wildcard import already present in that exact file. Don't assume.
4. **If you have actual Gradle/JDK access (unlike the environment this plan was written
   in), run `./gradlew compileKotlin compileClientKotlin` after every item**, not just at
   the end — catching one compile error at a time is dramatically cheaper than untangling
   several at once.

---

## Doc trust calibration

| Doc | Trust for | Don't trust for |
|---|---|---|
| Actual `.kt` source files | Everything — always the ground truth | — |
| `CLAUDE.md` | Process rules (verify APIs, no guessing, concise output) | Project specifics (it has none) |
| `ARCHITECTURE.md` | Rough orientation, source tree layout, package boundaries | Specific type signatures, NbsPlayer's existence, config mechanism, MC version |
| `BLOCKBARD_PLAN.md` | Original design intent, instrument reference tables (verify against `NoteUtils.kt` before trusting numbers) | Current implementation state — this is a pre-build plan, not a status doc |
| `README.md` | Not yet reviewed in this pass — check before relying on it | Unknown until reviewed |
