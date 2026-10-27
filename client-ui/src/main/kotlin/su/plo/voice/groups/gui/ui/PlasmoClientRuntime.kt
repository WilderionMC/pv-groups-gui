package su.plo.voice.groups.gui.ui

import su.plo.lib.mod.client.gui.screen.GuiScreen
import su.plo.lib.mod.client.gui.screen.ScreenWrapper
import su.plo.voice.api.client.PlasmoVoiceClient
import su.plo.voice.api.client.config.hotkey.Hotkey
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon
import su.plo.voice.groups.gui.client.GroupsGuiScreenOpener
import su.plo.voice.groups.gui.protocol.GuiLang
import java.util.concurrent.Executor
import java.util.Properties

/** Keeps Minecraft names out of the distributed bytecode, including on intermediary/SRG loaders. */
object PlasmoClientRuntime {
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        // Plasmo exposes its widgets but no client-thread executor. This one stable field
        // is checked against every pinned Plasmo client JAR by verifyClientCompatibility.
        val field = GuiScreen::class.java.getDeclaredField("minecraft")
        field.isAccessible = true
        val executor = field.get(null) as Executor
        GroupsGuiClientAddon.clientScheduler = { executor.execute(it) }
        val addon = GroupsGuiClientAddon()
        addon.screenOpener = GroupsGuiScreenOpener { controller ->
            ScreenWrapper.openScreen(GroupsGuiScreen(controller))
        }
        addon.onReady = { voice -> registerHotkey(voice, addon) }
        PlasmoVoiceClient.getAddonsLoader().load(addon)
        loaded = true
    }

    private fun registerHotkey(voice: PlasmoVoiceClient, addon: GroupsGuiClientAddon) {
        val properties = javaClass.getResourceAsStream("/groups-gui-target.properties")!!.use {
            Properties().apply { load(it) }
        }
        val hotkeys = voice.hotkeys
        if (!hotkeys.getHotkey(GuiLang.KEY_OPEN).isPresent) {
            hotkeys.register(
                GuiLang.KEY_OPEN,
                listOf(Hotkey.Type.KEYSYM.getOrCreate(properties.getProperty("openKey").toInt())),
                GuiLang.KEY_CATEGORY,
                false,
            )
        }
        // ConfigHotkeys stores a copy; register() returns the inactive default object.
        hotkeys.getHotkey(GuiLang.KEY_OPEN).get().addPressListener { action ->
            if (action == Hotkey.Action.DOWN && voice.serverConnection.isPresent) addon.openGui()
        }
    }
}
