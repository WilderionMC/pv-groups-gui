package su.plo.voice.groups.gui.bridge

import su.plo.voice.api.PlasmoVoice
import su.plo.voice.api.addon.AddonInitializer
import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.groups.gui.protocol.GuiMessage
import su.plo.voice.groups.gui.protocol.GroupsGuiPacket
import su.plo.voice.groups.gui.protocol.PacketRegistryHook

abstract class GroupsGuiBridgeAddon : AddonInitializer {

    protected lateinit var groups: GroupsService
        private set
    protected lateinit var requests: GuiRequestHandler
        private set

    protected abstract val voice: PlasmoVoice

    /** When true, cancel proxy TcpPacketReceivedEvent so backend never sees our packet. */
    protected open val cancelAfterHandle: Boolean = false

    override fun onAddonInitialize() {
        PacketRegistryHook.ensureRegistered()
        groups = GroupsAddonService(voice)
        requests = GuiRequestHandler(groups)
        onBridgeReady()
    }

    protected open fun onBridgeReady() {}

    protected fun onGuiPacket(player: VoicePlayer, packet: GroupsGuiPacket): Boolean {
        val message = runCatching { packet.asMessage() }.getOrNull() ?: return false
        if (message !is GuiMessage.C2S) return false
        val responses = requests.handle(player, message)
        player.sendGui(responses)
        return true
    }

    protected fun shouldCancel(handled: Boolean): Boolean = handled && cancelAfterHandle
}
