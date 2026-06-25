package kyrielie.blockbard.client.input

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
import org.slf4j.LoggerFactory
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.Transmitter

object MidiInputHandler {

    private val logger = LoggerFactory.getLogger("BlockBard/MIDI")
    private var transmitter: Transmitter? = null
    private var openedDeviceName: String? = null

    fun listInputDevices(): List<String> = MidiSystem.getMidiDeviceInfo()
        .mapNotNull { info ->
            val device = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: return@mapNotNull null
            if (device.maxTransmitters != 0) info.name else null
        }

    fun connect(deviceName: String): Boolean {
        disconnect()
        val info = MidiSystem.getMidiDeviceInfo().firstOrNull { it.name == deviceName } ?: return false
        val device = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: return false
        return try {
            device.open()
            transmitter = device.transmitter
            transmitter?.receiver = object : Receiver {
                override fun send(message: MidiMessage, timeStamp: Long) {
                    if (message is ShortMessage) {
                        when (message.command) {
                            ShortMessage.NOTE_ON -> {
                                if (message.data2 > 0) {
                                    ArpeggioScheduler.enqueue(NoteRequest(message.data1))
                                }
                            }
                            ShortMessage.NOTE_OFF -> { /* live play ignores off events */ }
                        }
                    }
                }
                override fun close() {}
            }
            openedDeviceName = deviceName
            logger.info("BlockBard: connected to MIDI device '$deviceName'")
            true
        } catch (e: Exception) {
            logger.warn("BlockBard: failed to connect to MIDI device '$deviceName': ${e.message}")
            false
        }
    }

    fun disconnect() {
        transmitter?.close()
        transmitter = null
        openedDeviceName = null
    }

    fun autoConnect() {
        val saved = ConfigManager.config.midiDeviceName ?: return
        if (!connect(saved)) {
            logger.warn("BlockBard: saved MIDI device '$saved' not found")
            // TODO: show in-game warning toast
        }
    }

    val isConnected: Boolean get() = transmitter != null
    val connectedDeviceName: String? get() = openedDeviceName
}
