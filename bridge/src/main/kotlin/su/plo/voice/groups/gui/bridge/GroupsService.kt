package su.plo.voice.groups.gui.bridge

import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.groups.gui.protocol.BrowsePage
import su.plo.voice.groups.gui.protocol.GroupSnapshot
import su.plo.voice.groups.gui.protocol.PlayerRef
import su.plo.voice.groups.gui.protocol.ProtocolConstants
import java.util.UUID

data class ServiceResult(
    val success: Boolean,
    val translationKey: String,
    val args: List<String> = emptyList(),
)

interface GroupsService {
    fun isAvailable(): Boolean

    fun canBrowse(player: VoicePlayer): Boolean
    fun canCreate(player: VoicePlayer): Boolean
    fun canCreatePersistent(player: VoicePlayer): Boolean
    fun canCreatePermissions(player: VoicePlayer): Boolean

    fun browse(
        player: VoicePlayer,
        page: Int,
        pageSize: Int = ProtocolConstants.DEFAULT_PAGE_SIZE,
    ): BrowsePage

    fun currentGroup(player: VoicePlayer): GroupSnapshot?

    fun create(
        player: VoicePlayer,
        name: String?,
        password: String?,
        persistent: Boolean,
        permissions: List<String>,
    ): ServiceResult

    fun join(player: VoicePlayer, groupId: UUID, password: String?): ServiceResult
    fun leave(player: VoicePlayer): ServiceResult
    fun invite(player: VoicePlayer, targetName: String): ServiceResult
    fun delete(player: VoicePlayer): ServiceResult
    fun transfer(player: VoicePlayer, targetName: String): ServiceResult
    fun kick(player: VoicePlayer, targetName: String): ServiceResult
    fun ban(player: VoicePlayer, targetName: String): ServiceResult
    fun unban(player: VoicePlayer, targetName: String): ServiceResult
    fun setName(player: VoicePlayer, name: String): ServiceResult
    fun setPassword(player: VoicePlayer, password: String?): ServiceResult
    fun setPersistent(player: VoicePlayer, persistent: Boolean): ServiceResult
    fun setPermissions(player: VoicePlayer, permissions: List<String>?): ServiceResult
    fun inviteCandidates(player: VoicePlayer): List<PlayerRef>
}
