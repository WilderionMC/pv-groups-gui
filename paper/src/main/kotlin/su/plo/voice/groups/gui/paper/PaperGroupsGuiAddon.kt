package su.plo.voice.groups.gui.paper

import su.plo.voice.api.addon.AddonLoaderScope
import su.plo.voice.api.addon.InjectPlasmoVoice
import su.plo.voice.api.addon.annotation.Addon
import su.plo.voice.api.addon.annotation.Dependency
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.event.connection.TcpPacketReceivedEvent
import su.plo.voice.groups.gui.bridge.GroupsGuiBridgeAddon
import su.plo.voice.groups.gui.protocol.GroupsGuiPacket

@Addon(
    id = "pv-addon-groups-gui",
    name = "PV Groups GUI",
    version = "1.0.0",
    authors = ["PV Groups GUI contributors"],
    scope = AddonLoaderScope.SERVER,
    dependencies = [Dependency(id = "pv-addon-groups")],
)
class PaperGroupsGuiAddon : GroupsGuiBridgeAddon() {
    @InjectPlasmoVoice
    override lateinit var voice: PlasmoVoiceServer

    @EventSubscribe
    fun onTcpPacket(event: TcpPacketReceivedEvent) {
        val packet = event.packet as? GroupsGuiPacket ?: return
        onGuiPacket(event.player, packet)
    }
}
