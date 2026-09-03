package su.plo.voice.groups.gui.protocol

import com.google.common.io.ByteArrayDataInput
import com.google.common.io.ByteArrayDataOutput
import su.plo.voice.proto.packets.Packet
import su.plo.voice.proto.packets.PacketHandler

/**
 * Single Plasmo Voice TCP packet that carries Groups GUI protocol payloads.
 * Handling is done via TcpPacketReceivedEvent / TcpClientPacketReceivedEvent; [handle] is a no-op.
 */
class GroupsGuiPacket() : Packet<PacketHandler> {
    var payload: ByteArray = ByteArray(0)

    constructor(message: GuiMessage) : this() {
        payload = GuiCodec.encode(message)
    }

    fun asMessage(): GuiMessage = GuiCodec.decode(payload)

    override fun read(input: ByteArrayDataInput) {
        val size = input.readInt()
        require(size in 0..ProtocolConstants.MAX_PAYLOAD_BYTES) {
            "Groups GUI payload too large: $size"
        }
        payload = ByteArray(size)
        input.readFully(payload)
    }

    override fun write(output: ByteArrayDataOutput) {
        output.writeInt(payload.size)
        output.write(payload)
    }

    override fun handle(handler: PacketHandler) {
        // no-op — processed by addon event listeners
    }
}
