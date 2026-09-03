package su.plo.voice.groups.gui.client

import su.plo.voice.api.addon.AddonInitializer
import su.plo.voice.api.addon.AddonLoaderScope
import su.plo.voice.api.addon.InjectPlasmoVoice
import su.plo.voice.api.addon.annotation.Addon
import su.plo.voice.api.client.PlasmoVoiceClient
import su.plo.voice.api.client.event.audio.capture.AudioCaptureProcessedEvent
import su.plo.voice.api.client.event.audio.source.AudioSourceWriteEvent
import su.plo.voice.api.client.event.connection.UdpClientPacketReceivedEvent
import su.plo.voice.api.client.event.connection.TcpClientPacketReceivedEvent
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo
import su.plo.voice.proto.packets.udp.clientbound.SelfAudioInfoPacket
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

    @EventSubscribe
    fun onSourceWrite(event: AudioSourceWriteEvent) {
        val source = event.source
        if (source.sourceLine.name != GROUPS_LINE) return
        if (source.effectiveVolume <= 0f) return

        val sourceInfo = source.sourceInfo
        if (sourceInfo !is PlayerSourceInfo) return

        val playerId = sourceInfo.playerInfo.playerId
        val level = GroupsSpeakingActivityTracker.calculateAudioLevel(event.samples)
        GroupsSpeakingActivityTracker.markSpeaking(playerId, level)
    }

    @EventSubscribe
    fun onAudioCaptureProcessed(event: AudioCaptureProcessedEvent) {
        val mono = event.processed.mono ?: return
        GroupsSpeakingActivityTracker.updateLocalAudioLevel(
            GroupsSpeakingActivityTracker.calculateAudioLevel(mono),
        )
    }

    @EventSubscribe
    fun onUdpPacket(event: UdpClientPacketReceivedEvent) {
        if (event.packet !is SelfAudioInfoPacket) return
        val selfId = voiceClient.serverConnection
            .flatMap { it.localPlayer }
            .map { it.playerId }
            .orElse(null) ?: return
        GroupsSpeakingActivityTracker.markSpeaking(
            selfId,
            GroupsSpeakingActivityTracker.lastLocalAudioLevel,
        )
    }

    companion object {
        private const val GROUPS_LINE = "groups"
        @JvmStatic
        var INSTANCE: GroupsGuiClientAddon? = null
            private set

        @JvmStatic
        var clientScheduler: (Runnable) -> Unit = { it.run() }
    }
}
