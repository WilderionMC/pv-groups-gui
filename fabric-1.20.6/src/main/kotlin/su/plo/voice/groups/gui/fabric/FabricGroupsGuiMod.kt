package su.plo.voice.groups.gui.fabric

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import su.plo.voice.api.client.PlasmoVoiceClient
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon
import su.plo.voice.groups.gui.protocol.GuiLang

class FabricGroupsGuiMod : ClientModInitializer {
    private val addon = GroupsGuiClientAddon()

    override fun onInitializeClient() {
        PlasmoVoiceClient.getAddonsLoader().load(addon)

        GroupsGuiClientAddon.clientScheduler = { runnable ->
            Minecraft.getInstance().execute(runnable)
        }

        addon.screenOpener = GroupsGuiScreenOpenerImpl()

        val openKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                GuiLang.KEY_OPEN,
                InputConstants.Type.KEYSYM,
                DEFAULT_OPEN_KEY,
                KEY_CATEGORY,
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openKey.consumeClick()) {
                if (client.player != null) {
                    addon.openGui()
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_OPEN_KEY = GLFW.GLFW_KEY_G
        private const val KEY_CATEGORY = "PV Groups GUI"
    }
}
