package su.plo.voice.groups.gui.bridge

import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.groups.gui.protocol.GuiLang
import su.plo.voice.groups.gui.protocol.GuiMessage
import su.plo.voice.groups.gui.protocol.GroupsGuiPacket
import su.plo.voice.groups.gui.protocol.ProtocolConstants

class GuiRequestHandler(
    private val groups: GroupsService,
) {
    fun handle(player: VoicePlayer, message: GuiMessage.C2S): List<GuiMessage.S2C> {
        return when (message) {
            GuiMessage.C2S.Hello -> listOf(hello(player), state(player)) + inviteCandidates(player)
            is GuiMessage.C2S.Browse -> {
                if (!groups.isAvailable()) {
                    return listOf(unavailable())
                }
                if (!groups.canBrowse(player)) {
                    return listOf(
                        GuiMessage.S2C.ActionResult(false, GuiLang.ERROR_NO_PERMISSION_BROWSE),
                    )
                }
                val page = groups.browse(player, message.page)
                listOf(GuiMessage.S2C.Browse(page), state(player))
            }
            is GuiMessage.C2S.Create -> {
                val result = groups.create(
                    player,
                    message.name,
                    message.password,
                    message.persistent,
                    message.permissions,
                )
                listOf(resultMessage(result), state(player)) + browseRefresh(player) + inviteCandidates(player)
            }
            is GuiMessage.C2S.Join -> {
                val result = groups.join(player, message.groupId, message.password)
                listOf(resultMessage(result), state(player)) + browseRefresh(player) + inviteCandidates(player)
            }
            GuiMessage.C2S.Leave -> {
                val result = groups.leave(player)
                listOf(resultMessage(result), state(player)) + browseRefresh(player) + inviteCandidates(player)
            }
            is GuiMessage.C2S.Invite -> {
                val result = groups.invite(player, message.playerName)
                listOf(resultMessage(result), state(player)) + inviteCandidates(player)
            }
            GuiMessage.C2S.Delete -> {
                val result = groups.delete(player)
                listOf(resultMessage(result), state(player)) + browseRefresh(player) + inviteCandidates(player)
            }
            GuiMessage.C2S.Info -> listOf(state(player)) + inviteCandidates(player)
            is GuiMessage.C2S.Transfer -> {
                val result = groups.transfer(player, message.playerName)
                listOf(resultMessage(result), state(player)) + browseRefresh(player)
            }
            is GuiMessage.C2S.Kick -> {
                val result = groups.kick(player, message.playerName)
                listOf(resultMessage(result), state(player)) + browseRefresh(player) + inviteCandidates(player)
            }
            is GuiMessage.C2S.Ban -> {
                val result = groups.ban(player, message.playerName)
                listOf(resultMessage(result), state(player)) + browseRefresh(player) + inviteCandidates(player)
            }
            is GuiMessage.C2S.Unban -> {
                val result = groups.unban(player, message.playerName)
                listOf(resultMessage(result), state(player)) + browseRefresh(player) + inviteCandidates(player)
            }
            is GuiMessage.C2S.SetName -> {
                val result = groups.setName(player, message.name)
                listOf(resultMessage(result), state(player)) + browseRefresh(player)
            }
            is GuiMessage.C2S.SetPassword -> {
                val result = groups.setPassword(player, message.password)
                listOf(resultMessage(result), state(player)) + browseRefresh(player)
            }
            is GuiMessage.C2S.SetPersistent -> {
                val result = groups.setPersistent(player, message.persistent)
                listOf(resultMessage(result), state(player)) + browseRefresh(player)
            }
            is GuiMessage.C2S.SetPermissions -> {
                val result = groups.setPermissions(player, message.permissions)
                listOf(resultMessage(result), state(player)) + browseRefresh(player) + inviteCandidates(player)
            }
            GuiMessage.C2S.ListInviteCandidates -> inviteCandidates(player)
        }
    }

    private fun browseRefresh(player: VoicePlayer): List<GuiMessage.S2C> {
        if (!groups.canBrowse(player)) return emptyList()
        return listOf(GuiMessage.S2C.Browse(groups.browse(player, FIRST_PAGE)))
    }

    private fun inviteCandidates(player: VoicePlayer): List<GuiMessage.S2C> {
        if (!groups.isAvailable()) return emptyList()
        return listOf(GuiMessage.S2C.InviteCandidates(groups.inviteCandidates(player)))
    }

    private fun hello(player: VoicePlayer): GuiMessage.S2C.Hello {
        val available = groups.isAvailable()
        return GuiMessage.S2C.Hello(
            protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
            groupsAvailable = available,
            canBrowse = available && groups.canBrowse(player),
            canCreate = available && groups.canCreate(player),
            canCreatePersistent = available && groups.canCreatePersistent(player),
            canCreatePermissions = available && groups.canCreatePermissions(player),
        )
    }

    private fun state(player: VoicePlayer): GuiMessage.S2C.State =
        GuiMessage.S2C.State(if (groups.isAvailable()) groups.currentGroup(player) else null)

    private fun unavailable(): GuiMessage.S2C.ActionResult =
        GuiMessage.S2C.ActionResult(false, GuiLang.ERROR_GROUPS_MISSING)

    private fun resultMessage(result: ServiceResult): GuiMessage.S2C.ActionResult =
        GuiMessage.S2C.ActionResult(result.success, result.translationKey, result.args)

    companion object {
        private const val FIRST_PAGE = 1
    }
}

fun VoicePlayer.sendGui(messages: Collection<GuiMessage.S2C>) {
    messages.forEach { sendPacket(GroupsGuiPacket(it)) }
}
