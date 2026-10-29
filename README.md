# BlockBard

> **Automated NoteBlock Organ Performer & Live Synthesizer for Minecraft Fabric 26.2**

BlockBard is an advanced client-side Minecraft mod designed to turn physical noteblock arrangements into fully automated, concert-ready musical organs. Load standard MIDI (`.mid`/`.midi`) or Note Block Studio (`.nbs`) files, and BlockBard will scan your surrounding blocks, calculate optimal pitch assignments, automatically tune each block to the required pitch, and execute high-precision musical playback without altering block tunings.

---

## Key Features

- 🎵 **MIDI & NBS File Playback**: Drop `.mid`, `.midi`, or `.nbs` files into `config/blockbard/midis/` and control playback directly from the in-game GUI.
- 🔍 **Intelligent Organ Scanning**: Detects and catalogues all noteblocks within a configurable radius (1–10 blocks), classifying them into `PLAYABLE`, `SILENCED` (obstructed above), and `MOB_HEAD` (custom ambient heads).
- 🎯 **Pre-Flight Readiness Checking**: Validates instrument and note coverage immediately upon selecting a file, providing material placement hints (e.g., gold block for Bell, bone block for Xylophone, copper for Trumpet) for missing instruments.
- ⚙️ **Automated Non-Destructive Tuning**: Right-clicks each noteblock the exact number of times required to reach target pitch using an optimistic, latency-compensated tuning state machine with verification and retry limits.
- 🛡️ **Anti-Cheat & Rate Limit Hardening**:
  - **Left-Click (Attack) Playback**: Uses `startDestroyBlock()` and `stopDestroyBlock()` to trigger noteblock sounds without altering their tuned state (`NOTE` property cycle). Right-click is reserved strictly for tuning.
  - **Eased Rotation Smoothing**: Progressively rotates player camera toward target blocks (capped at `35°/tick` by default) rather than snapping instantly, preventing anti-cheat rotation flags.
  - **Token-Bucket Rate Limiter**: Implements an 8 interacts / 310ms token bucket matching Paper's server-side limits, with configurable burst caps (`1–8` notes/tick) and rotation convergence gates.
- 🔊 **Sound Verification & Diagnostics**:
  - **`SoundVerifier`**: Mixin-free verification via Minecraft's `SoundEventListener`, decoding sound pitch ($12 + 12 \cdot \log_2(\text{pitch})$) and matching audible sounds against scheduled note expectations.
  - **`KickDiagnostics`**: Listens for disconnect events and correlates server disconnect reasons with recent interact timestamps, target blocks, and token budget states.
- 🎹 **Live Performance & Input**:
  - **Keyboard Play**: Trigger notes in real time using number keys `1`–`9` while in the BlockBard GUI.
  - **USB MIDI Keyboard Input**: Connect physical MIDI instruments via `javax.sound.midi` with thread-safe queue synchronization.
- 🔁 **Playback Modes & HUD**:
  - **Loop Modes**: Support for `NONE`, `LOOP_ONE`, `LOOP_ALL`, and `SHUFFLE_ALL` (with duplicate avoidance history).
  - **HUD Overlay**: On-screen overlay rendered via Fabric's `HudElementRegistry` (`H` to toggle) showing song progress, ticks, tempo, and organ coverage.
  - **Dynamic Tempo Scaling**: Live `0.5×`–`2.0×` tempo slider affecting both MIDI and NBS playback in real time.

---

## Controls & Keybindings

| Key | Default Action |
|:---:|:---|
| <kbd>B</kbd> | Open BlockBard GUI |
| <kbd>H</kbd> | Toggle HUD Overlay |
| <kbd>1</kbd>–<kbd>9</kbd> | Play mapped notes live (active when GUI is open) |

*Keybindings can be customized in Minecraft's standard **Options → Controls → Key Binds** menu.*

---

## Step-by-Step Workflow

1. **Build Your Organ**: Place noteblocks with appropriate foundation blocks beneath them (e.g., wood planks for Bass, wool for Guitar, dirt for Harp, packed ice for Chime, copper for Trumpet). Ensure the space directly above each noteblock is air.
2. **Open BlockBard**: Press <kbd>B</kbd> to open the mod interface.
3. **Scan & Center**:
   - In the **Organ** tab, click **Scan** to detect all noteblocks within range.
   - Click **Center** to calculate reach distances and facing angles from your current standing position.
4. **Choose a Track**:
   - In the **Files** tab, select a `.mid` or `.nbs` file from your `config/blockbard/midis/` directory.
   - Review the pre-flight readiness report in the organ view. If notes are missing, consult the material hints to add required blocks.
5. **Auto-Tune**:
   - Click **Tune** in the **Organ** tab. BlockBard will aim at each block, send the required right-click pulses, verify the final pitches against the server, and confirm when complete.
6. **Play**:
   - Switch to the **Files** tab and press **Play**. Use the tempo buttons (<kbd>Tempo -</kbd> / <kbd>Tempo +</kbd>) and loop controls (<kbd>Loop</kbd>) to manage playback.

---

## Architecture & Source Layout

BlockBard is split cleanly between a pure logic common module (`src/main`) and a client-only module (`src/client`):

```
src/
├── main/kotlin/kyrielie/blockbard/
│   ├── organ/
│   │   ├── ArpeggioScheduler.kt    # Rate-limited note dispatch queue & history ring buffer
│   │   ├── InstrumentShifter.kt    # Pitch-shifting & octave transposition engine
│   │   ├── MidiToOrganMapper.kt    # Note-to-block assignment & tune target calculator
│   │   ├── MobHeadBlocks.kt        # Mob skull definitions for ambient noteblocks
│   │   ├── NoteBlockEntry.kt       # Block position, instrument, note, and reach data
│   │   ├── NoteBlockRegistry.kt    # Central repository of scanned organ blocks
│   │   ├── NoteBlockTuner.kt       # Latency-compensated tuning state machine
│   │   └── OrganMap.kt             # Precomputed reach and aim data from player position
│   └── util/
│       ├── DebugLog.kt             # High-performance conditional logging
│       ├── InstrumentMaterials.kt  # Foundation block material mapping hints
│       ├── LittleEndianReader.kt   # Stream extensions with strict EOF validation
│       ├── NoteUtils.kt            # Pitch math, MIDI conversions, and instrument ranges
│       └── VecMath.kt              # Vector to yaw/pitch angular math
└── client/kotlin/kyrielie/blockbard/client/
    ├── BlockBardClient.kt          # Mod initializer, tick listeners & keybind registration
    ├── config/
    │   └── BlockBardConfig.kt      # Configuration data model & JSON manager
    ├── gui/
    │   ├── BlockBardModMenuIntegration.kt # ModMenu entrypoint
    │   ├── MainScreen.kt           # Primary tabbed GUI (Files, Organ, Settings)
    │   └── PlaybackHud.kt          # On-screen HUD overlay via HudElementRegistry
    ├── input/
    │   ├── KeyboardInputHandler.kt # 1-9 note trigger dispatcher
    │   └── MidiInputHandler.kt     # USB/Hardware MIDI receiver
    ├── midi/
    │   ├── MidiChannelResolver.kt  # GM program to NoteBlockInstrument resolution
    │   ├── MidiInstrumentMap.kt    # Instrument mapping & percussion tables
    │   └── OrganReadinessChecker.kt# Song coverage vs organ analysis
    ├── organ/
    │   └── OrganScanner.kt         # Client-side world block scanner
    ├── playback/
    │   ├── MidiFilePlayer.kt       # Sequence loader & scheduler driver
    │   └── NbsFileLoader.kt        # NBS parser & synchronized NbsPlayer
    └── player/
        ├── KickDiagnostics.kt      # Post-disconnect analysis & send history dumping
        ├── PlayerController.kt     # Rotation interpolation, left-click & right-click actions
        └── SoundVerifier.kt        # Game sound listener for runtime playback verification
```

---

## Technical Specifications (Minecraft 26.2)

- **Mapping Layer**: None. Minecraft 26.2 ships deobfuscated with official Mojang names directly in runtime and development environments.
- **Java Version**: Java 25 (`JavaCompile.options.release = 25`, `jvmTarget = JVM_25`).
- **Dependencies**:
  - Fabric Loader ≥ `0.19.3`
  - Fabric API ≥ `0.153.0+26.2`
  - Fabric Language Kotlin ≥ `1.13.12+kotlin.2.4.0`
  - ModMenu (optional soft dependency) ≥ `20.0.0-beta.4`
  - Gson `2.10.1`

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/kyrielie/blockbard.git
cd blockbard

# Build the mod JAR (requires Java 25 installed)
./gradlew build

# Built artifact location:
# build/libs/blockbard-1.0.0.jar
```

To run a client development environment with the mod loaded:
```bash
./gradlew runClient
```
