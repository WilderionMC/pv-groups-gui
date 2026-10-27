package su.plo.voice.groups.gui.ui

import net.fabricmc.api.ClientModInitializer

class FabricGroupsGuiMod : ClientModInitializer {
    override fun onInitializeClient() = PlasmoClientRuntime.load()
}
