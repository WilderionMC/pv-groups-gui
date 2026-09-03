package su.plo.voice.groups.gui.protocol

import su.plo.voice.proto.packets.PacketDirection
import su.plo.voice.proto.packets.PacketRegistry
import su.plo.voice.proto.packets.tcp.PacketTcpCodec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/**
 * Plasmo Voice does not expose a public TCP packet registration API.
 * We inject into the private [PacketTcpCodec] registry once per JVM.
 *
 * Compatible with PV 2.1.16+ registry signature:
 * register(id, direction, Class, Supplier).
 */
object PacketRegistryHook {
    private val registered = AtomicBoolean(false)

    @JvmStatic
    fun ensureRegistered() {
        if (!registered.compareAndSet(false, true)) return

        val field = PacketTcpCodec::class.java.getDeclaredField("PACKETS")
        field.isAccessible = true
        val registry = field.get(null) as PacketRegistry

        if (registry.getType(GroupsGuiPacket()) >= 0) return

        registry.register(
            ProtocolConstants.PACKET_ID,
            PacketDirection.ANY,
            GroupsGuiPacket::class.java,
            Supplier { GroupsGuiPacket() },
        )
    }
}
