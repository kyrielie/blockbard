package kyrielie.blockbard.client.gui

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import kyrielie.blockbard.client.config.BlockBardYaclConfig

class BlockBardModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> BlockBardYaclConfig.createScreen(parent) }
}