package kyrielie.blockbard.client.config

import dev.isxander.yacl3.api.*
import dev.isxander.yacl3.api.controller.*
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.InstrumentShifter
import kyrielie.blockbard.organ.ShiftMode
import kyrielie.blockbard.util.DebugLog
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

object BlockBardYaclConfig {

    fun createScreen(parent: Screen?): Screen {
        val cfg = ConfigManager.config

        return YetAnotherConfigLib.createBuilder()
            .title(Component.literal("BlockBard Configuration"))
            .save {
                ConfigManager.save()
                // Sync live runtime state
                DebugLog.enabled = cfg.debugLogging
                ArpeggioScheduler.maxNotesPerTick = cfg.maxNotesPerTick
                InstrumentShifter.mode = cfg.shiftModeEnum()
            }
            .category(
                ConfigCategory.createBuilder()
                    .name(Component.literal("General"))
                    .tooltip(Component.literal("General playback and visualizer settings"))
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Debug Logging"))
                            .description(OptionDescription.of(Component.literal("Route detailed hot-path debug logs to the game console.")))
                            .binding(Binding.generic(false, { cfg.debugLogging }, { cfg.debugLogging = it }))
                            .controller(TickBoxControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("In-Game HUD Overlay"))
                            .description(OptionDescription.of(Component.literal("Display track title and playback status in the in-game HUD.")))
                            .binding(Binding.generic(false, { cfg.hudEnabled }, { cfg.hudEnabled = it }))
                            .controller(TickBoxControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Chat Notifications"))
                            .description(OptionDescription.of(Component.literal("When enabled, status messages are printed to chat. When disabled (recommended), info is kept in the BlockBard UI and debug logs without cluttering chat.")))
                            .binding(Binding.generic(false, { cfg.enableChatFeedback }, { cfg.enableChatFeedback = it }))
                            .controller(TickBoxControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<LoopMode>()
                            .name(Component.literal("Loop Mode"))
                            .description(OptionDescription.of(Component.literal("Playback progression behavior when a song completes.")))
                            .binding(Binding.generic(LoopMode.NONE, { cfg.loopModeEnum() }, { cfg.loopMode = it.name }))
                            .controller { opt -> EnumDropdownControllerBuilder.create(opt) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Int>()
                            .name(Component.literal("Scan Radius"))
                            .description(OptionDescription.of(Component.literal("Block radius around player to search for noteblocks.")))
                            .binding(Binding.generic(5, { cfg.scanRadius }, { cfg.scanRadius = it }))
                            .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(1, 15).step(1) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Spherical Scan"))
                            .description(OptionDescription.of(Component.literal("Scan within a sphere instead of a bounding cube.")))
                            .binding(Binding.generic(true, { cfg.sphericalScan }, { cfg.sphericalScan = it }))
                            .controller(TickBoxControllerBuilder::create)
                            .build()
                    )
                    .build()
            )
            .category(
                ConfigCategory.createBuilder()
                    .name(Component.literal("Tuning & Dynamics"))
                    .tooltip(Component.literal("Noteblock tuning and dynamic range behavior"))
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Fast Tuning"))
                            .description(OptionDescription.of(Component.literal("Batch clicks per tick for fast tuning on servers that allow it.")))
                            .binding(Binding.generic(true, { cfg.fastTuning }, { cfg.fastTuning = it }))
                            .controller(TickBoxControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Int>()
                            .name(Component.literal("Max Tuning Clicks Per Tick"))
                            .description(OptionDescription.of(Component.literal("Rate limit for clicks dispatched in a single game tick.")))
                            .binding(Binding.generic(1, { cfg.maxTuningClicksPerTick }, { cfg.maxTuningClicksPerTick = it }))
                            .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(1, 10).step(1) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Dynamic Retuning"))
                            .description(OptionDescription.of(Component.literal("Allows retuning noteblocks on-the-fly during playback for greater musical range with fewer blocks.")))
                            .binding(Binding.generic(false, { cfg.dynamicRetuning }, { cfg.dynamicRetuning = it }))
                            .controller(TickBoxControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Int>()
                            .name(Component.literal("Max Dynamic Retune Clicks"))
                            .description(OptionDescription.of(Component.literal("Maximum click step allowed per dynamic retune transition.")))
                            .binding(Binding.generic(2, { cfg.dynamicRetuningMaxClicks }, { cfg.dynamicRetuningMaxClicks = it }))
                            .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(1, 5).step(1) }
                            .build()
                    )
                    .build()
            )
            .category(
                ConfigCategory.createBuilder()
                    .name(Component.literal("Anticheat & Camera"))
                    .tooltip(Component.literal("Camera smoothing and anticheat evasion settings"))
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Smooth Camera Rotation"))
                            .description(OptionDescription.of(Component.literal("Smoothly rotates player view toward target blocks to evade anticheat flags.")))
                            .binding(Binding.generic(false, { cfg.smoothCameraRotation }, { cfg.smoothCameraRotation = it }))
                            .controller(TickBoxControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Float>()
                            .name(Component.literal("Max Rotation Degrees / Tick"))
                            .description(OptionDescription.of(Component.literal("Maximum yaw/pitch delta allowed per tick when easing view.")))
                            .binding(Binding.generic(35f, { cfg.maxRotationDegreesPerTick }, { cfg.maxRotationDegreesPerTick = it }))
                            .controller { opt -> FloatSliderControllerBuilder.create(opt).range(10f, 180f).step(5f) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Float>()
                            .name(Component.literal("Convergence Threshold (degrees)"))
                            .description(OptionDescription.of(Component.literal("How close camera must be to target angle before clicking.")))
                            .binding(Binding.generic(2f, { cfg.rotationConvergenceThresholdDegrees }, { cfg.rotationConvergenceThresholdDegrees = it }))
                            .controller { opt -> FloatSliderControllerBuilder.create(opt).range(0.5f, 10f).step(0.5f) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Center Moves Player"))
                            .description(OptionDescription.of(Component.literal("When enabled, the Center button moves the player to the center of the standing block. When disabled, it recalibrates the reach map from your current position without moving you.")))
                            .binding(Binding.generic(false, { cfg.centerMovesPlayer }, { cfg.centerMovesPlayer = it }))
                            .controller(TickBoxControllerBuilder::create)
                            .build()
                    )
                    .build()
            )
            .category(
                ConfigCategory.createBuilder()
                    .name(Component.literal("Audio & Organ"))
                    .tooltip(Component.literal("Instrument mapping and arpeggio limits"))
                    .option(
                        Option.createBuilder<Int>()
                            .name(Component.literal("Max Notes Per Tick"))
                            .description(OptionDescription.of(Component.literal("Maximum note interactions sent per tick (1–64).")))
                            .binding(Binding.generic(8, { cfg.maxNotesPerTick }, { cfg.maxNotesPerTick = it }))
                            .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(1, 64).step(1) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<ShiftMode>()
                            .name(Component.literal("Instrument Shift Mode"))
                            .description(OptionDescription.of(Component.literal("How notes are matched or shifted if exact noteblocks are missing.")))
                            .binding(Binding.generic(ShiftMode.BEST_EFFORT, { cfg.shiftModeEnum() }, { cfg.shiftMode = it.name }))
                            .controller { opt -> EnumDropdownControllerBuilder.create(opt) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Int>()
                            .name(Component.literal("Max Octave Shift"))
                            .description(OptionDescription.of(Component.literal("Maximum octaves a note can be shifted up or down.")))
                            .binding(Binding.generic(1, { cfg.maxOctaveShift }, { cfg.maxOctaveShift = it }))
                            .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(0, 3).step(1) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Report Shifts in HUD"))
                            .description(OptionDescription.of(Component.literal("Show notes shifted in HUD/chat.")))
                            .binding(Binding.generic(true, { cfg.reportShiftsInHud }, { cfg.reportShiftsInHud = it }))
                            .controller(TickBoxControllerBuilder::create)
                            .build()
                    )
                    .build()
            )
            .build()
            .generateScreen(parent)
    }
}
