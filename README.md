# BlockBard

Client side fabric mod.
It plays noteblocks in minecraft. Inspired by Disc-Jockey and SongPlayer.

---

## Key Features

- Supports MIDI and NBS files. Jukebox controls, change tempo, queue and skip songs, loop.
- You place the noteblocks, it tries to tune and play them even if you didn't place enough of each instrument.
- Client side mod, works on most servers. Not intended for use in Creative mode.
- Could be considered 'hacking' or 'autoclicking' based on some server rules, please get permission to use, use at your own risk.
- Virtual keyboard and MIDI keyboard input. There's no sustain and it sounds terrible, why would anyone want this.

---

## Controls & Keybindings

| Key | Default Action |
|:---:|:---|
| <kbd>B</kbd> | Open BlockBard GUI |
| <kbd>H</kbd> | Toggle HUD Overlay |
| <kbd>1</kbd>–<kbd>9</kbd> | Play mapped notes live (active when GUI is open) |

*Keybindings can be customized in Minecraft's standard **Options → Controls → Key Binds** menu.*

### In-Game Client Commands

BlockBard provides full Fabric client commands registered under `/blockbard` (or alias `/bb`):

| Command | Description |
|:---|:---|
| `/bb play <song>` | Starts playing the specified song (supports tab-completion). Refused while tuning is in progress. |
| `/bb preview <song>` | Auditions the song locally using client sound without placing blocks. |
| `/bb stop` | Halts world playback, tuning, and previews. |
| `/bb scan` | Scans surrounding blocks and reports playable noteblocks. |
| `/bb center` | Centers standing position on organ and reports reachability. |
| `/bb tempo <0.1–5.0>` | Sets live playback tempo scale. |
| `/bb repeat <off\|all\|one>` | Sets what happens when the playlist ends (replaces `/bb loop`). |
| `/bb shuffle <on\|off>` | Turns shuffle on or off. |
| `/bb download <url> [name]` | Downloads a `.mid` or `.nbs` file from the web asynchronously. |
| `/bb materials` | Toggles the Survival Materials shopping list HUD overlay. |
| `/bb remap <source> <target\|mute\|reset>` | Remaps or mutes note block instruments. |
| `/bb remap list` / `clear` | Views or resets active instrument remap rules. |

---

## Step-by-Step Workflow (This was written by an AI who has no idea how to play minecraft, just do what you think is best.)

1. **Build Your Organ**: Place noteblocks with appropriate foundation blocks beneath them (e.g., wood planks for Bass, wool for Guitar, dirt for Harp, packed ice for Chime, copper for Trumpet). Ensure the space directly above each noteblock is air.
2. **Open BlockBard**: Press <kbd>B</kbd> to open the mod interface.
3. **Scan & Center**:
   - In the **Organ** tab, click **Scan** to detect all noteblocks within range.
   - Click **Center** to calculate reach distances and facing angles from your current standing position.
4. **Choose a Track**:
   - In the **Files** tab, select a `.mid` or `.nbs` file from your `config/blockbard/midis/` directory.
   - Review the pre-flight readiness report in the organ view. If notes are missing, consult the material hints to add required blocks.
5. **Auto-Tune & Play**:
   - Click **Tune & Play** (or double-click any song). BlockBard calculates the minimal delta clicks, aims and right-clicks blocks to tune them, verifies pitches against the server, and immediately transitions to left-click world playback.
   - For complete chromatic organs (25 blocks per instrument covering notes 0–24), tuning is skipped entirely and playback starts instantly.
6. **Playback & Queue Management**:
   - Manage playback with the bottom transport bar: **▶ Play / ❚❚ Pause** (freezes and resumes at exact millisecond), **⏹ Stop** (terminates playback, resets position to 0:00, clears scheduler queue and cancels auto-advance), **|◀ Prev / ▶| Next**, **Tempo - / +**, the **Loop** button (cycles Repeat Off, All, One) and the **Shuf** button (shuffle on or off).
   - Switch to the **Queue** tab to view upcoming tracks, durations, and summaries. In the Files tab, **Next** puts a track right after the current song and **Last** adds it to the end of your queue; **Queue view** adds everything shown in the list. Tracks you queue always play first, in every repeat mode, and can be moved to the top (**Top**), reordered with the arrows or removed with `[✕]`. The rest of the list is the playlist order (random when Shuffle is on); **+Q** on one of those rows copies it into your queue, and **🔀 Reshuffle** gives a new random order. A single click on a row only highlights it; double-click plays it now.
   - Press <kbd>H</kbd> in-game to toggle the on-screen HUD overlay showing song progress, ticks, tempo, and notes played.

---

## Architecture & Source Layout

BlockBard is split cleanly between a pure logic common module (`src/main`) and a client-only module (`src/client`):

```
src/
├── main/kotlin/kyrielie/blockbard/
│   ├── organ/
│   │   ├── ArpeggioScheduler.kt    # Rate-limited note dispatch queue, multi-voice & history ring buffer
│   │   ├── InstrumentShifter.kt    # Pitch-shifting & octave transposition engine
│   │   ├── MidiToOrganMapper.kt    # Note-to-block assignment & tune target calculator
│   │   ├── MobHeadBlocks.kt        # Mob skull definitions for ambient noteblocks
│   │   ├── NoteBlockEntry.kt       # Block position, instrument, note, and reach data
│   │   ├── NoteBlockRegistry.kt    # Central repository of scanned organ blocks
│   │   ├── NoteBlockTuner.kt       # Latency-compensated tuning state machine
│   │   └── OrganMap.kt             # Precomputed reach and aim data from player position
│   └── util/
│       ├── BlockBardLogBuffer.kt   # In-memory circular log buffer for GUI inspection
│       ├── DebugLog.kt             # High-performance conditional logging
│       ├── InstrumentMaterials.kt  # Foundation block material mapping hints
│       ├── LittleEndianReader.kt   # Stream extensions with strict EOF validation
│       ├── NoteUtils.kt            # Pitch math, MIDI conversions, and instrument ranges
│       └── VecMath.kt              # Vector to yaw/pitch angular math
└── client/kotlin/kyrielie/blockbard/client/
    ├── BlockBardClient.kt          # Mod initializer, tick listeners & keybind registration
    ├── command/
    │   └── BlockBardCommands.kt    # Fabric client commands (/blockbard and /bb)
    ├── config/
    │   ├── BlockBardConfig.kt      # Configuration data model & JSON manager
    │   └── BlockBardYaclConfig.kt  # Yet Another Config Lib (YACL) GUI configuration screen
    ├── gui/
    │   ├── BlockBardModMenuIntegration.kt # ModMenu entrypoint
    │   ├── MainScreen.kt           # Primary tabbed GUI (Files, Queue, Organ, Logs)
    │   ├── QueueListWidget.kt      # Upcoming track queue with shuffle removal
    │   ├── SongListWidget.kt       # Filterable song library list with favorite stars
    │   ├── LogListWidget.kt        # In-game log viewer with clipboard copying
    │   ├── VirtualKeyboardWidget.kt# Interactive on-screen piano keyboard
    │   ├── MaterialsShoppingOverlay.kt # Survival materials item-stack HUD overlay
    │   ├── PlaybackHud.kt          # On-screen HUD overlay via HudElementRegistry
    │   └── VisualizerState.kt      # Real-time note visualizer tracking
    ├── input/
    │   ├── KeyboardInputHandler.kt # 1-9 note trigger dispatcher
    │   └── MidiInputHandler.kt     # USB/Hardware MIDI receiver
    ├── midi/
    │   ├── InstrumentRemapper.kt   # Runtime instrument substitution and muting
    │   ├── MidiChannelResolver.kt  # GM program to NoteBlockInstrument resolution
    │   ├── MidiInstrumentMap.kt    # Instrument mapping & percussion tables
    │   └── OrganReadinessChecker.kt# Song coverage vs organ analysis
    ├── organ/
    │   └── OrganScanner.kt         # Client-side world block scanner (cubic & spherical)
    ├── playback/
    │   ├── MidiFilePlayer.kt       # Sequence loader & scheduler driver
    │   ├── MidiTempoMap.kt         # Dynamic tempo event mapping (accelerandos/ritardandos)
    │   ├── NbsFileLoader.kt        # NBS parser & synchronized NbsPlayer
    │   ├── SongAudioPreviewer.kt   # Client-side sound engine audio auditioning
    │   └── SongMetadataCache.kt    # Duration, tempo, and summary metadata caching
    ├── player/
    │   ├── KickDiagnostics.kt      # Post-disconnect analysis & send history dumping
    │   ├── PlayerController.kt     # Rotation interpolation, left-click & right-click actions
    │   └── SoundVerifier.kt        # Game sound listener for runtime playback verification
    └── util/
        └── SongDownloader.kt       # Asynchronous HTTP/HTTPS song download utility
```

---

## Technical Specifications (Minecraft 26.x Series)

- **Supported Minecraft Versions**: Minecraft 26.x (`>=26.1 <27.0`, including 26.1, 26.2, and 26.3).
- **Mapping Layer**: None. Starting with 26.1, Minecraft ships deobfuscated with official Mojang names directly in runtime and development environments.
- **Java Version**: Java 25 (`JavaCompile.options.release = 25`, `jvmTarget = JVM_25`).
- **Dependencies**:
  - Fabric Loader ≥ `0.19.3`
  - Fabric API (wildcard `*`, tested on `0.153.0+26.2`)
  - Fabric Language Kotlin ≥ `1.13.12+kotlin.2.4.0`
  - ModMenu (optional soft dependency) ≥ `20.0.0-beta.4`
  - Gson `2.10.1`

I'm pretty sure you need YACL yet another config lib. Install that too.

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
