package su.plo.voice.groups.gui.velocity

import su.plo.voice.api.addon.AddonLoaderScope
import su.plo.voice.api.addon.InjectPlasmoVoice
import su.plo.voice.api.addon.annotation.Addon
import su.plo.voice.api.addon.annotation.Dependency
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.api.proxy.PlasmoVoiceProxy
import su.plo.voice.api.proxy.event.connection.TcpPacketReceivedEvent
import su.plo.voice.groups.gui.bridge.GroupsGuiBridgeAddon
import su.plo.voice.groups.gui.protocol.GroupsGuiPacket

@Addon(
    id = "pv-addon-groups-gui",
    name = "PV Groups GUI",
    version = "1.0.0",
    authors = ["PV Groups GUI contributors"],
    scope = AddonLoaderScope.PROXY,
    dependencies = [Dependency(id = "pv-addon-groups")],
)
class VelocityGroupsGuiAddon : GroupsGuiBridgeAddon() {
    @InjectPlasmoVoice
    override lateinit var voice: PlasmoVoiceProxy
    override val cancelAfterHandle: Boolean = true

    @EventSubscribe
    fun onTcpPacket(event: TcpPacketReceivedEvent) {
        val packet = event.packet as? GroupsGuiPacket ?: return
        val handled = onGuiPacket(event.player, packet)
        if (shouldCancel(handled)) {
            event.isCancelled = true
        }
    }
}
