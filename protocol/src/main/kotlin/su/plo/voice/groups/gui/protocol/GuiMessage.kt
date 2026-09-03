package su.plo.voice.groups.gui.protocol

import java.util.UUID

data class PlayerRef(
    val uuid: UUID,
    val name: String,
)

data class GroupMember(
    val uuid: UUID,
    val name: String,
    val isOwner: Boolean,
)

data class GroupSnapshot(
    val id: UUID,
    val name: String,
    val ownerName: String?,
    val onlinePlayers: Int,
    val totalPlayers: Int,
    val passwordProtected: Boolean,
    val persistent: Boolean,
    val permissions: List<String> = emptyList(),
    val members: List<GroupMember>,
    val bannedPlayers: List<PlayerRef> = emptyList(),
    val isCurrent: Boolean,
    val isOwner: Boolean,
    val canKick: Boolean,
    val canBan: Boolean,
    val canSetName: Boolean = false,
    val canSetPassword: Boolean = false,
    val canUnsetPassword: Boolean = false,
    val canSetPersistent: Boolean = false,
    val canSetPermissions: Boolean = false,
    val canUnsetPermissions: Boolean = false,
    val canTransfer: Boolean = false,
)

data class BrowsePage(
    val page: Int,
    val totalPages: Int,
    val groups: List<GroupSnapshot>,
    val currentGroupId: UUID?,
)

sealed interface GuiMessage {
    sealed interface C2S : GuiMessage {
        data object Hello : C2S
        data class Browse(val page: Int) : C2S
        data class Create(
            val name: String?,
            val password: String?,
            val persistent: Boolean,
            val permissions: List<String>,
        ) : C2S
        data class Join(val groupId: UUID, val password: String?) : C2S
        data object Leave : C2S
        data class Invite(val playerName: String) : C2S
        data object Delete : C2S
        data object Info : C2S
        data class Transfer(val playerName: String) : C2S
        data class Kick(val playerName: String) : C2S
        data class Ban(val playerName: String) : C2S
        data class Unban(val playerName: String) : C2S
        data object ListInviteCandidates : C2S
        data class SetName(val name: String) : C2S
        /** Null clears the group password. */
        data class SetPassword(val password: String?) : C2S
        data class SetPersistent(val persistent: Boolean) : C2S
        /** Null or empty clears the permissions filter. */
        data class SetPermissions(val permissions: List<String>?) : C2S
    }

    sealed interface S2C : GuiMessage {
        data class Hello(
            val protocolVersion: Int,
            val groupsAvailable: Boolean,
            val canBrowse: Boolean,
            val canCreate: Boolean,
            val canCreatePersistent: Boolean = false,
            val canCreatePermissions: Boolean = false,
        ) : S2C

        data class Browse(val page: BrowsePage) : S2C

        data class ActionResult(
            val success: Boolean,
            val translationKey: String,
            val args: List<String> = emptyList(),
        ) : S2C

        data class State(
            val current: GroupSnapshot?,
        ) : S2C

        data class InviteCandidates(
            val players: List<PlayerRef>,
        ) : S2C
    }
}
