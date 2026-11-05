package kyrielie.blockbard.client.util

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.util.BlockBardLogBuffer
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Chat and log feedback that does not need a screen. Same format as the messages the GUI
 * sends: always written to the log buffer, and to chat when chat feedback is enabled.
 */
object Notifier {
    fun info(msg: String) {
        BlockBardLogBuffer.info("GUI", msg)
        send("§f$msg")
    }

    fun warn(msg: String) {
        BlockBardLogBuffer.warn("GUI", msg)
        send("§c$msg")
    }

    private fun send(styled: String) {
        if (ConfigManager.config.enableChatFeedback) {
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§b[BlockBard] $styled"))
        }
    }
}
