package kyrielie.blockbard.client.input

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.midi.InstrumentRemapper
import kyrielie.blockbard.client.midi.MidiInstrumentMap
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
import kyrielie.blockbard.util.MidiDeviceMatch
import kyrielie.blockbard.util.MidiDeviceMatcher
import org.slf4j.LoggerFactory
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.Transmitter

/** One entry of the Java MIDI device list, for `/bb midi list`. */
data class MidiPortInfo(val name: String, val description: String, val vendor: String, val canSend: Boolean)

sealed class MidiConnectResult {
    data class Connected(val name: String) : MidiConnectResult()
    data class NotFound(val available: List<String>) : MidiConnectResult()
    data class Ambiguous(val candidates: List<String>) : MidiConnectResult()
    data class Failed(val name: String, val reason: String) : MidiConnectResult()
}

object MidiInputHandler {

    private val logger = LoggerFactory.getLogger("BlockBard/MIDI")
    private var device: MidiDevice? = null
    private var transmitter: Transmitter? = null
    private var openedDeviceName: String? = null

    /** Ticks between silent reconnect attempts for a saved device (a Bluetooth keyboard may connect late). */
    private const val RETRY_INTERVAL_TICKS = 200
    private var retryTicker = 0
    private var retryFailureLogged = false

    // Written on the MIDI thread, read on the client thread (status command).
    @Volatile
    var messageCount: Long = 0
        private set
    @Volatile
    var noteOnCount: Long = 0
        private set
    @Volatile
    var lastNote: Int = -1
        private set

    /** Every device Java can see, input or not. Used for diagnosis when the keyboard is missing. */
    fun listPorts(): List<MidiPortInfo> = runCatching { MidiSystem.getMidiDeviceInfo().toList() }
        .getOrDefault(emptyList())
        .map { info ->
            val canSend = runCatching { MidiSystem.getMidiDevice(info).maxTransmitters != 0 }.getOrDefault(false)
            MidiPortInfo(info.name, info.description, info.vendor, canSend)
        }

    /** Names of devices that can send MIDI to us (maxTransmitters != 0; -1 means unlimited). */
    fun listInputDevices(): List<String> = listPorts().filter { it.canSend }.map { it.name }.distinct()

    /** Connects to the best match for [query]; see [MidiDeviceMatcher] for the rules. */
    fun connect(query: String): MidiConnectResult {
        val inputs = listInputDevices()
        val name = when (val m = MidiDeviceMatcher.match(inputs, query)) {
            is MidiDeviceMatch.Found -> m.name
            is MidiDeviceMatch.Ambiguous -> return MidiConnectResult.Ambiguous(m.candidates)
            MidiDeviceMatch.NotFound -> return MidiConnectResult.NotFound(inputs)
        }
        disconnect()
        // A physical keyboard is often listed twice under the same name (an input port and an
        // output port). Pick the entry that can actually transmit, not just the first with the name.
        val dev = MidiDeviceMatcher.selectSender(
            MidiSystem.getMidiDeviceInfo().map { MidiSystem.getMidiDevice(it) },
            name,
            nameOf = { it.deviceInfo.name },
            canSend = { it.maxTransmitters != 0 }
        ) ?: return MidiConnectResult.NotFound(inputs)
        return try {
            dev.open()
            val tx = dev.transmitter
            tx.receiver = object : Receiver {
                override fun send(message: MidiMessage, timeStamp: Long) = onMessage(message)
                override fun close() {}
            }
            device = dev
            transmitter = tx
            openedDeviceName = name
            messageCount = 0
            noteOnCount = 0
            lastNote = -1
            logger.info("BlockBard: connected to MIDI device '$name'")
            MidiConnectResult.Connected(name)
        } catch (e: Exception) {
            logger.warn("BlockBard: failed to connect to MIDI device '$name': ${e.message}")
            runCatching { dev.close() }
            MidiConnectResult.Failed(name, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun onMessage(message: MidiMessage) {
        val n = ++messageCount
        if (n == 1L) logger.info("BlockBard: first MIDI message received (status=0x${"%02X".format(message.status)})")
        if (message is ShortMessage && message.command == ShortMessage.NOTE_ON && message.data2 > 0) {
            noteOnCount++
            lastNote = message.data1
            // The keyboard acts as GM program 0 (piano), exactly as MidiChannelResolver treats a
            // file's piano channel: the preferred instrument is HARP, passed through the user's
            // instrument remap (a muted piano drops the note). Block choice, instrument fallback
            // and octave shifting happen in the scheduler (see LiveNoteResolver).
            val preferred = InstrumentRemapper.remap(MidiInstrumentMap.primaryFor(0)) ?: return
            ArpeggioScheduler.enqueue(NoteRequest(message.data1, instrument = preferred, live = true))
        }
        // NOTE_OFF is ignored: live play fires a note-on click only.
    }

    fun disconnect() {
        runCatching { transmitter?.close() }
        runCatching { device?.close() }
        transmitter = null
        device = null
        openedDeviceName = null
    }

    /** Startup attempt. Logs the visible inputs so a missing device is diagnosable from latest.log. */
    fun autoConnect() {
        val inputs = listInputDevices()
        logger.info("BlockBard: MIDI input devices visible to Java: ${if (inputs.isEmpty()) "(none)" else inputs.joinToString(", ") { "'$it'" }}")
        val saved = ConfigManager.config.midiDeviceName
        if (saved.isNullOrBlank()) {
            logger.info("BlockBard: no MIDI device saved; use /bb midi list then /bb midi connect <name>")
            return
        }
        when (val r = connect(saved)) {
            is MidiConnectResult.Connected -> {}
            is MidiConnectResult.NotFound -> logger.warn("BlockBard: saved MIDI device '$saved' not found; will keep retrying")
            is MidiConnectResult.Ambiguous -> logger.warn("BlockBard: saved MIDI device '$saved' matches several devices: ${r.candidates}")
            is MidiConnectResult.Failed -> logger.warn("BlockBard: saved MIDI device '$saved' failed to open: ${r.reason}")
        }
    }

    /** Called every client tick. Retries a saved-but-unconnected device every [RETRY_INTERVAL_TICKS]. */
    fun tickRetry() {
        if (isConnected) return
        val saved = ConfigManager.config.midiDeviceName
        if (saved.isNullOrBlank()) return
        if (++retryTicker < RETRY_INTERVAL_TICKS) return
        retryTicker = 0
        when (val r = connect(saved)) {
            is MidiConnectResult.Connected -> retryFailureLogged = false
            else -> if (!retryFailureLogged) {
                retryFailureLogged = true
                logger.info("BlockBard: MIDI device '$saved' still unavailable ($r); retrying every ${RETRY_INTERVAL_TICKS / 20}s")
            }
        }
    }

    val isConnected: Boolean get() = transmitter != null
    val connectedDeviceName: String? get() = openedDeviceName
}
