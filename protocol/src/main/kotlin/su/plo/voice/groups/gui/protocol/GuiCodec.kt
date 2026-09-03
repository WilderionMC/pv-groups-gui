package su.plo.voice.groups.gui.protocol

import com.google.common.io.ByteArrayDataInput
import com.google.common.io.ByteArrayDataOutput
import com.google.common.io.ByteStreams
import java.util.UUID

object GuiCodec {
    fun encode(message: GuiMessage): ByteArray {
        val out = ByteStreams.newDataOutput()
        when (message) {
            GuiMessage.C2S.Hello -> out.writeByte(Opcode.C2S_HELLO.toInt())
            is GuiMessage.C2S.Browse -> {
                out.writeByte(Opcode.C2S_BROWSE.toInt())
                out.writeInt(message.page)
            }
            is GuiMessage.C2S.Create -> {
                out.writeByte(Opcode.C2S_CREATE.toInt())
                writeNullableString(out, message.name)
                writeNullableString(out, message.password)
                out.writeBoolean(message.persistent)
                out.writeInt(message.permissions.size)
                message.permissions.forEach { out.writeUTF(it) }
            }
            is GuiMessage.C2S.Join -> {
                out.writeByte(Opcode.C2S_JOIN.toInt())
                writeUuid(out, message.groupId)
                writeNullableString(out, message.password)
            }
            GuiMessage.C2S.Leave -> out.writeByte(Opcode.C2S_LEAVE.toInt())
            is GuiMessage.C2S.Invite -> {
                out.writeByte(Opcode.C2S_INVITE.toInt())
                out.writeUTF(message.playerName)
            }
            GuiMessage.C2S.Delete -> out.writeByte(Opcode.C2S_DELETE.toInt())
            GuiMessage.C2S.Info -> out.writeByte(Opcode.C2S_INFO.toInt())
            is GuiMessage.C2S.Transfer -> {
                out.writeByte(Opcode.C2S_TRANSFER.toInt())
                out.writeUTF(message.playerName)
            }
            is GuiMessage.C2S.Kick -> {
                out.writeByte(Opcode.C2S_KICK.toInt())
                out.writeUTF(message.playerName)
            }
            is GuiMessage.C2S.Ban -> {
                out.writeByte(Opcode.C2S_BAN.toInt())
                out.writeUTF(message.playerName)
            }
            is GuiMessage.C2S.Unban -> {
                out.writeByte(Opcode.C2S_UNBAN.toInt())
                out.writeUTF(message.playerName)
            }
            GuiMessage.C2S.ListInviteCandidates -> out.writeByte(Opcode.C2S_LIST_INVITE.toInt())
            is GuiMessage.C2S.SetName -> {
                out.writeByte(Opcode.C2S_SET_NAME.toInt())
                out.writeUTF(message.name)
            }
            is GuiMessage.C2S.SetPassword -> {
                out.writeByte(Opcode.C2S_SET_PASSWORD.toInt())
                writeNullableString(out, message.password)
            }
            is GuiMessage.C2S.SetPersistent -> {
                out.writeByte(Opcode.C2S_SET_PERSISTENT.toInt())
                out.writeBoolean(message.persistent)
            }
            is GuiMessage.C2S.SetPermissions -> {
                out.writeByte(Opcode.C2S_SET_PERMISSIONS.toInt())
                val perms = message.permissions
                out.writeBoolean(perms != null)
                if (perms != null) {
                    out.writeInt(perms.size)
                    perms.forEach { out.writeUTF(it) }
                }
            }

            is GuiMessage.S2C.Hello -> {
                out.writeByte(Opcode.S2C_HELLO.toInt())
                out.writeInt(message.protocolVersion)
                out.writeBoolean(message.groupsAvailable)
                out.writeBoolean(message.canBrowse)
                out.writeBoolean(message.canCreate)
                out.writeBoolean(message.canCreatePersistent)
                out.writeBoolean(message.canCreatePermissions)
            }
            is GuiMessage.S2C.Browse -> {
                out.writeByte(Opcode.S2C_BROWSE.toInt())
                writeBrowsePage(out, message.page)
            }
            is GuiMessage.S2C.ActionResult -> {
                out.writeByte(Opcode.S2C_ACTION.toInt())
                out.writeBoolean(message.success)
                out.writeUTF(message.translationKey)
                out.writeInt(message.args.size)
                message.args.forEach { out.writeUTF(it) }
            }
            is GuiMessage.S2C.State -> {
                out.writeByte(Opcode.S2C_STATE.toInt())
                writeNullableGroup(out, message.current)
            }
            is GuiMessage.S2C.InviteCandidates -> {
                out.writeByte(Opcode.S2C_INVITE_CANDIDATES.toInt())
                out.writeInt(message.players.size)
                message.players.forEach { writePlayerRef(out, it) }
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): GuiMessage {
        val input = ByteStreams.newDataInput(bytes)
        return when (val opcode = input.readByte()) {
            Opcode.C2S_HELLO -> GuiMessage.C2S.Hello
            Opcode.C2S_BROWSE -> GuiMessage.C2S.Browse(input.readInt())
            Opcode.C2S_CREATE -> GuiMessage.C2S.Create(
                name = readNullableString(input),
                password = readNullableString(input),
                persistent = input.readBoolean(),
                permissions = List(input.readInt()) { input.readUTF() },
            )
            Opcode.C2S_JOIN -> GuiMessage.C2S.Join(readUuid(input), readNullableString(input))
            Opcode.C2S_LEAVE -> GuiMessage.C2S.Leave
            Opcode.C2S_INVITE -> GuiMessage.C2S.Invite(input.readUTF())
            Opcode.C2S_DELETE -> GuiMessage.C2S.Delete
            Opcode.C2S_INFO -> GuiMessage.C2S.Info
            Opcode.C2S_TRANSFER -> GuiMessage.C2S.Transfer(input.readUTF())
            Opcode.C2S_KICK -> GuiMessage.C2S.Kick(input.readUTF())
            Opcode.C2S_BAN -> GuiMessage.C2S.Ban(input.readUTF())
            Opcode.C2S_UNBAN -> GuiMessage.C2S.Unban(input.readUTF())
            Opcode.C2S_LIST_INVITE -> GuiMessage.C2S.ListInviteCandidates
            Opcode.C2S_SET_NAME -> GuiMessage.C2S.SetName(input.readUTF())
            Opcode.C2S_SET_PASSWORD -> GuiMessage.C2S.SetPassword(readNullableString(input))
            Opcode.C2S_SET_PERSISTENT -> GuiMessage.C2S.SetPersistent(input.readBoolean())
            Opcode.C2S_SET_PERMISSIONS -> GuiMessage.C2S.SetPermissions(
                if (input.readBoolean()) List(input.readInt()) { input.readUTF() } else null,
            )

            Opcode.S2C_HELLO -> GuiMessage.S2C.Hello(
                protocolVersion = input.readInt(),
                groupsAvailable = input.readBoolean(),
                canBrowse = input.readBoolean(),
                canCreate = input.readBoolean(),
                canCreatePersistent = input.readBoolean(),
                canCreatePermissions = input.readBoolean(),
            )
            Opcode.S2C_BROWSE -> GuiMessage.S2C.Browse(readBrowsePage(input))
            Opcode.S2C_ACTION -> GuiMessage.S2C.ActionResult(
                success = input.readBoolean(),
                translationKey = input.readUTF(),
                args = List(input.readInt()) { input.readUTF() },
            )
            Opcode.S2C_STATE -> GuiMessage.S2C.State(readNullableGroup(input))
            Opcode.S2C_INVITE_CANDIDATES -> GuiMessage.S2C.InviteCandidates(
                players = List(input.readInt()) { readPlayerRef(input) },
            )
            else -> error("Unknown groups-gui opcode: $opcode")
        }
    }

    private fun writeBrowsePage(out: ByteArrayDataOutput, page: BrowsePage) {
        out.writeInt(page.page)
        out.writeInt(page.totalPages)
        writeNullableUuid(out, page.currentGroupId)
        out.writeInt(page.groups.size)
        page.groups.forEach { writeGroup(out, it) }
    }

    private fun readBrowsePage(input: ByteArrayDataInput): BrowsePage {
        val page = input.readInt()
        val totalPages = input.readInt()
        val currentGroupId = readNullableUuid(input)
        val groups = List(input.readInt()) { readGroup(input) }
        return BrowsePage(page, totalPages, groups, currentGroupId)
    }

    private fun writeGroup(out: ByteArrayDataOutput, group: GroupSnapshot) {
        writeUuid(out, group.id)
        out.writeUTF(group.name)
        writeNullableString(out, group.ownerName)
        out.writeInt(group.onlinePlayers)
        out.writeInt(group.totalPlayers)
        out.writeBoolean(group.passwordProtected)
        out.writeBoolean(group.persistent)
        out.writeInt(group.permissions.size)
        group.permissions.forEach { out.writeUTF(it) }
        out.writeInt(group.members.size)
        group.members.forEach { writeMember(out, it) }
        out.writeInt(group.bannedPlayers.size)
        group.bannedPlayers.forEach { writePlayerRef(out, it) }
        out.writeBoolean(group.isCurrent)
        out.writeBoolean(group.isOwner)
        out.writeBoolean(group.canKick)
        out.writeBoolean(group.canBan)
        out.writeBoolean(group.canSetName)
        out.writeBoolean(group.canSetPassword)
        out.writeBoolean(group.canUnsetPassword)
        out.writeBoolean(group.canSetPersistent)
        out.writeBoolean(group.canSetPermissions)
        out.writeBoolean(group.canUnsetPermissions)
        out.writeBoolean(group.canTransfer)
    }

    private fun readGroup(input: ByteArrayDataInput): GroupSnapshot = GroupSnapshot(
        id = readUuid(input),
        name = input.readUTF(),
        ownerName = readNullableString(input),
        onlinePlayers = input.readInt(),
        totalPlayers = input.readInt(),
        passwordProtected = input.readBoolean(),
        persistent = input.readBoolean(),
        permissions = List(input.readInt()) { input.readUTF() },
        members = List(input.readInt()) { readMember(input) },
        bannedPlayers = List(input.readInt()) { readPlayerRef(input) },
        isCurrent = input.readBoolean(),
        isOwner = input.readBoolean(),
        canKick = input.readBoolean(),
        canBan = input.readBoolean(),
        canSetName = input.readBoolean(),
        canSetPassword = input.readBoolean(),
        canUnsetPassword = input.readBoolean(),
        canSetPersistent = input.readBoolean(),
        canSetPermissions = input.readBoolean(),
        canUnsetPermissions = input.readBoolean(),
        canTransfer = input.readBoolean(),
    )

    private fun writeMember(out: ByteArrayDataOutput, member: GroupMember) {
        writeUuid(out, member.uuid)
        out.writeUTF(member.name)
        out.writeBoolean(member.isOwner)
    }

    private fun readMember(input: ByteArrayDataInput): GroupMember = GroupMember(
        uuid = readUuid(input),
        name = input.readUTF(),
        isOwner = input.readBoolean(),
    )

    private fun writePlayerRef(out: ByteArrayDataOutput, player: PlayerRef) {
        writeUuid(out, player.uuid)
        out.writeUTF(player.name)
    }

    private fun readPlayerRef(input: ByteArrayDataInput): PlayerRef = PlayerRef(
        uuid = readUuid(input),
        name = input.readUTF(),
    )

    private fun writeNullableGroup(out: ByteArrayDataOutput, group: GroupSnapshot?) {
        out.writeBoolean(group != null)
        if (group != null) writeGroup(out, group)
    }

    private fun readNullableGroup(input: ByteArrayDataInput): GroupSnapshot? =
        if (input.readBoolean()) readGroup(input) else null

    private fun writeUuid(out: ByteArrayDataOutput, uuid: UUID) {
        out.writeLong(uuid.mostSignificantBits)
        out.writeLong(uuid.leastSignificantBits)
    }

    private fun readUuid(input: ByteArrayDataInput): UUID =
        UUID(input.readLong(), input.readLong())

    private fun writeNullableUuid(out: ByteArrayDataOutput, uuid: UUID?) {
        out.writeBoolean(uuid != null)
        if (uuid != null) writeUuid(out, uuid)
    }

    private fun readNullableUuid(input: ByteArrayDataInput): UUID? =
        if (input.readBoolean()) readUuid(input) else null

    private fun writeNullableString(out: ByteArrayDataOutput, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeUTF(value)
    }

    private fun readNullableString(input: ByteArrayDataInput): String? =
        if (input.readBoolean()) input.readUTF() else null

    private object Opcode {
        const val C2S_HELLO: Byte = 1
        const val C2S_BROWSE: Byte = 2
        const val C2S_CREATE: Byte = 3
        const val C2S_JOIN: Byte = 4
        const val C2S_LEAVE: Byte = 5
        const val C2S_INVITE: Byte = 6
        const val C2S_DELETE: Byte = 7
        const val C2S_INFO: Byte = 8
        const val C2S_TRANSFER: Byte = 9
        const val C2S_KICK: Byte = 10
        const val C2S_BAN: Byte = 11
        const val C2S_UNBAN: Byte = 13
        const val C2S_LIST_INVITE: Byte = 12
        const val C2S_SET_NAME: Byte = 14
        const val C2S_SET_PASSWORD: Byte = 15
        const val C2S_SET_PERSISTENT: Byte = 16
        const val C2S_SET_PERMISSIONS: Byte = 17

        const val S2C_HELLO: Byte = 100
        const val S2C_BROWSE: Byte = 101
        const val S2C_ACTION: Byte = 102
        const val S2C_STATE: Byte = 103
        const val S2C_INVITE_CANDIDATES: Byte = 104
    }
}
