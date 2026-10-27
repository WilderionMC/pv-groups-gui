package su.plo.voice.groups.gui.client

import su.plo.voice.api.addon.AddonInitializer
import su.plo.voice.api.addon.AddonLoaderScope
import su.plo.voice.api.addon.InjectPlasmoVoice
import su.plo.voice.api.addon.annotation.Addon
import su.plo.voice.api.client.PlasmoVoiceClient
import su.plo.voice.api.client.event.connection.TcpClientPacketReceivedEvent
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.groups.gui.protocol.GuiMessage
import su.plo.voice.groups.gui.protocol.GroupsGuiPacket
import su.plo.voice.groups.gui.protocol.PacketRegistryHook

@Addon(
    id = "pv-addon-groups-gui",
    name = "PV Groups GUI",
    version = "1.0.0",
    authors = ["PV Groups GUI contributors"],
    scope = AddonLoaderScope.CLIENT,
)
class GroupsGuiClientAddon : AddonInitializer {

    @InjectPlasmoVoice
    private lateinit var voiceClient: PlasmoVoiceClient

    lateinit var controller: GroupsGuiController
        private set

    @Volatile
    var screenOpener: GroupsGuiScreenOpener? = null

    var onReady: (PlasmoVoiceClient) -> Unit = {}

    fun plasmoVoiceClient(): PlasmoVoiceClient? =
        if (::voiceClient.isInitialized) voiceClient else null

    override fun onAddonInitialize() {
        PacketRegistryHook.ensureRegistered()
        INSTANCE = this

        controller = GroupsGuiController(
            send = { message ->
                val connection = voiceClient.serverConnection.orElse(null) ?: return@GroupsGuiController
                connection.sendPacket(GroupsGuiPacket(message), false)
            },
            runOnClientThread = { runnable -> clientScheduler(runnable) },
        )
        onReady(voiceClient)
    }

    fun openGui() {
        val opener = screenOpener ?: return
        opener.openGroupsGui(controller)
        controller.onOpen()
    }

    @EventSubscribe
    fun onTcpPacket(event: TcpClientPacketReceivedEvent) {
        val packet = event.packet as? GroupsGuiPacket ?: return
        event.isCancelled = true
        val message = runCatching { packet.asMessage() }.getOrNull() ?: return
        if (message !is GuiMessage.S2C) return
        controller.handle(message)
    }

    companion object {
        @JvmStatic
        var INSTANCE: GroupsGuiClientAddon? = null
            private set

        @JvmStatic
        var clientScheduler: (Runnable) -> Unit = { it.run() }
    }
}
