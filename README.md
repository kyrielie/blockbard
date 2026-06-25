# BlockBard

> Automated NoteBlock organ performer for Minecraft Fabric 26.2

Load a MIDI or NBS file, let BlockBard scan your noteblock organ, auto-tune every block, and play your song hands-free.

## Features

- **MIDI & NBS playback** — drop `.mid`/`.midi`/`.nbs` files into `config/blockbard/midis/` and pick them from the in-game GUI
- **Auto-scan** — detects all noteblocks in a configurable radius (1–10), classifies them as PLAYABLE, SILENCED, or MOB_HEAD
- **Auto-tune** — right-clicks each block the minimum number of times to reach the target pitch, no manual clicking required
- **Instrument-aware mapping** — prefers blocks whose native instrument range already covers a MIDI note; falls back to octave-shifting
- **Live keyboard play** — keys `1`–`9` trigger individual notes in real time (configurable MIDI note mapping)
- **MIDI device input** — plug in a USB MIDI keyboard and play your noteblock organ live
- **HUD overlay** — shows playback status, tempo, and organ coverage (`H` to toggle)
- **Shuffle & tempo control** — `0.5×`–`2.0×` tempo scaling and shuffle through your file list
- **ModMenu integration** — open the config screen from ModMenu (optional)

## Controls

| Key | Action |
|-----|--------|
| `B` | Open BlockBard GUI |
| `H` | Toggle HUD overlay |
| `1`–`9` | Play configured notes live |

## Workflow

1. Build your noteblock organ (harp, bass, bell — any instruments)
2. Open BlockBard (`B`) → **Scan** to detect blocks
3. Click **Center** — BlockBard finds the best stand position and walks you there
4. Select a MIDI/NBS file from the list
5. Click **Tune** — blocks are auto-tuned to match the song's notes
6. Click **▶ Play** — sit back and listen

## Building

```bash
# Requires Java 25 and network access to Fabric/Mojang Maven
./gradlew build
# Output: build/libs/blockbard-<version>.jar
```

## Dependencies

- Fabric Loader ≥ 0.19.3
- Minecraft 26.2
- Fabric API
- Fabric Language Kotlin
- ModMenu (optional, recommended)

## Source layout

```
src/
  main/kotlin/kyrielie/blockbard/
    organ/          # Pure logic: registry, tuner, mapper, scheduler, shifter
    util/           # Note math, MIDI helpers, binary readers
  client/kotlin/kyrielie/blockbard/client/
    BlockBardClient # ClientModInitializer entrypoint
    organ/          # OrganScanner (needs Minecraft client API)
    player/         # PlayerController — centering, rotation, interaction
    playback/       # MidiFilePlayer, NbsFileLoader/NbsPlayer
    gui/            # MainScreen, PlaybackHud, ModMenuIntegration
    input/          # KeyboardInputHandler, MidiInputHandler
    config/         # BlockBardConfig + ConfigManager (Gson)
```

## Notes on Minecraft 26.2

- Run `./gradlew genSources` and check `NoteBlockInstrument` enum constants — any new trumpet variants added in 26.x need entries in `NoteUtils.kt`'s `midiBase` extension.
- The `NoteBlock.INSTRUMENT` and `NoteBlock.NOTE` blockstate property names are Yarn-mapped — verify with generated sources if you see `NoSuchFieldError` at runtime.
