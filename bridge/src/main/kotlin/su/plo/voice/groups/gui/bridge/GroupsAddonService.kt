package su.plo.voice.groups.gui.bridge

import com.google.common.collect.Sets
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.entity.player.McGameProfile
import su.plo.voice.api.PlasmoVoice
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.groups.GroupsAddon
import su.plo.voice.groups.GroupsManager
import su.plo.voice.groups.group.Group
import su.plo.voice.groups.gui.protocol.BrowsePage
import su.plo.voice.groups.gui.protocol.GroupMember
import su.plo.voice.groups.gui.protocol.GroupSnapshot
import su.plo.voice.groups.gui.protocol.GuiLang
import su.plo.voice.groups.gui.protocol.PlayerRef
import su.plo.voice.groups.gui.protocol.ProtocolConstants
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Typed adapter to [pv-addon-groups] via [GroupsAddon] / [GroupsManager].
 * Permissions reuse groups [CommandHandler] + [SubCommand.checkCanExecute].
 */
class GroupsAddonService(
    private val voice: PlasmoVoice,
) : GroupsService {

    @Volatile
    private var cachedAddon: GroupsAddon? = null

    @Volatile
    private var cachedCommands: GroupsCommandAccess? = null

    override fun isAvailable(): Boolean = resolve() != null

    override fun canBrowse(player: VoicePlayer): Boolean =
        commands().hasAddonPermission(player.instance, "browse")

    override fun canCreate(player: VoicePlayer): Boolean =
        commands().canExecute("create", player.instance)

    override fun canCreatePersistent(player: VoicePlayer): Boolean =
        commands().hasFlagPermission(player.instance, "persistent")

    override fun canCreatePermissions(player: VoicePlayer): Boolean =
        commands().hasFlagPermission(player.instance, "permissions")

    override fun browse(player: VoicePlayer, page: Int, pageSize: Int): BrowsePage {
        val manager = requireManager()
        val source = player.instance
        val currentId = manager.groupByPlayer[player.instance.uuid]?.id
        val cmds = commands()

        val visible = manager.groups.values
            .asSequence()
            .filter { group ->
                cmds.canBrowseAll(source) || group.hasPermission(source)
            }
            .sortedByDescending { it.onlinePlayerCount }
            .toList()

        val chunks = visible.chunked(pageSize.coerceAtLeast(1)).ifEmpty { listOf(emptyList()) }
        val safePage = page.coerceIn(1, chunks.size)
        val slice = chunks[safePage - 1]

        return BrowsePage(
            page = safePage,
            totalPages = chunks.size,
            groups = slice.map { toSnapshot(it, player, currentId) },
            currentGroupId = currentId,
        )
    }

    override fun currentGroup(player: VoicePlayer): GroupSnapshot? {
        val manager = resolve()?.groupManager ?: return null
        val group = manager.groupByPlayer[player.instance.uuid] ?: return null
        return toSnapshot(group, player, group.id)
    }

    override fun create(
        player: VoicePlayer,
        name: String?,
        password: String?,
        persistent: Boolean,
        permissions: List<String>,
    ): ServiceResult {
        if (!canCreate(player)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_CREATE)
        }

        val cmds = commands()
        val source = player.instance
        val manager = requireAddon().groupManager
        val resolvedName = name?.takeIf { it.isNotBlank() } ?: defaultName(player)

        if (resolvedName.length !in ProtocolConstants.MIN_GROUP_NAME_LENGTH..ProtocolConstants.MAX_GROUP_NAME_LENGTH) {
            return fail(GuiLang.ERROR_NAME_LENGTH)
        }

        if (password != null && !cmds.hasFlagPermission(source, "password")) {
            return fail(GuiLang.ERROR_NO_PERMISSION_FLAG_PASSWORD)
        }
        if (persistent && !cmds.hasFlagPermission(source, "persistent")) {
            return fail(GuiLang.ERROR_NO_PERMISSION_FLAG_PERSISTENT)
        }
        if (permissions.isNotEmpty() && !cmds.hasFlagPermission(source, "permissions")) {
            return fail(GuiLang.ERROR_NO_PERMISSION_FLAG_PERMISSIONS)
        }

        return try {
            val playerSet = manager.sourceLine.playerSetManager!!.createBroadcastSet()
            val group = Group(
                playerSet,
                UUID.randomUUID(),
                resolvedName,
                password,
                persistent,
                Sets.newConcurrentHashSet(),
                CopyOnWriteArrayList(),
                player.instance.gameProfile,
            )
            if (permissions.isNotEmpty()) {
                group.permissionsFilter = HashSet(permissions)
            }

            manager.groups[group.id] = group
            manager.join(player, group)
            ok(GuiLang.SUCCESS_CREATE, resolvedName)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_CREATE_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun join(player: VoicePlayer, groupId: UUID, password: String?): ServiceResult {
        val cmds = commands()
        if (!cmds.canExecute("join", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_JOIN)
        }

        val manager = requireManager()
        val group = manager.groups[groupId]
            ?: return fail(GuiLang.ERROR_GROUP_NOT_FOUND)

        val current = manager.groupByPlayer[player.instance.uuid]
        if (current != null && current.id == groupId) {
            return fail(GuiLang.ERROR_ALREADY_JOINED)
        }

        if (!cmds.canBypassJoinRestrictions(player.instance)) {
            if (group.isBanned(player.instance.uuid)) {
                return fail(GuiLang.ERROR_BANNED)
            }
            if (!group.hasPermission(player.instance)) {
                return fail(GuiLang.ERROR_JOIN_NO_PERMISSION)
            }
            val expected = group.password
            if (expected != null && expected != password) {
                return fail(GuiLang.ERROR_WRONG_PASSWORD)
            }
        }

        return try {
            if (current != null) {
                manager.leave(player)
            }
            manager.join(player, group)
            ok(GuiLang.SUCCESS_JOIN, group.name)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_JOIN_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun leave(player: VoicePlayer): ServiceResult {
        // LeaveCommand.checkCanExecute only checks "in group"; permission is hasAddonPermission("leave").
        if (!commands().hasAddonPermission(player.instance, "leave")) {
            return fail(GuiLang.ERROR_NO_PERMISSION_LEAVE)
        }
        val manager = requireManager()
        val current = manager.groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        return try {
            manager.leave(player)
            ok(GuiLang.SUCCESS_LEAVE, current.name)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_LEAVE_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun invite(player: VoicePlayer, targetName: String): ServiceResult {
        val manager = requireManager()
        val group = manager.groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        if (!commands().canExecute("invite", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_INVITE)
        }

        if (targetName.equals(player.instance.name, ignoreCase = true)) {
            return fail(GuiLang.ERROR_INVITE_SELF)
        }

        val target = voicePlayerByName(targetName)
            ?: return fail(GuiLang.ERROR_PLAYER_NOT_FOUND, targetName)

        val targetGroup = manager.groupByPlayer[target.instance.uuid]
        if (targetGroup != null && targetGroup.id == group.id) {
            return fail(GuiLang.ERROR_INVITE_ALREADY)
        }

        if (group.isBanned(target.instance.uuid)) {
            return fail(GuiLang.ERROR_INVITE_TARGET_BANNED)
        }

        return try {
            player.instance.sendMessage(
                McTextComponent.translatable(
                    "pv.addon.groups.command.invite.success",
                    target.instance.name,
                ),
            )

            printGroupsDivider(target.instance)
            target.instance.sendMessage(
                McTextComponent.translatable(
                    "pv.addon.groups.format.invite",
                    player.instance.name,
                    group.inlineChatComponent(),
                ),
            )
            target.instance.sendMessage(group.joinButtonWithPassword())
            printGroupsDivider(target.instance)

            ok(GuiLang.SUCCESS_INVITE, targetName)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_INVITE_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun delete(player: VoicePlayer): ServiceResult {
        val manager = requireManager()
        manager.groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        if (!commands().canExecute("delete", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_DELETE)
        }

        val group = manager.groupByPlayer[player.instance.uuid]!!
        return try {
            // Same as GroupsManager.deleteGroup, without calling it (relocated kotlin.Unit).
            manager.groups.remove(group.id)?.let { removed ->
                removed.playersIds.toList().forEach { manager.leave(it) }
            }
            ok(GuiLang.SUCCESS_DELETE)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_DELETE_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun transfer(player: VoicePlayer, targetName: String): ServiceResult {
        val manager = requireManager()
        val group = manager.groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        if (!commands().canExecute("transfer", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_TRANSFER)
        }

        val target = voicePlayerByName(targetName)
            ?: return fail(GuiLang.ERROR_PLAYER_NOT_FOUND, targetName)

        if (manager.groupByPlayer[target.instance.uuid]?.id != group.id) {
            return fail(GuiLang.ERROR_TARGET_NOT_MEMBER)
        }

        return try {
            group.owner = target.instance.gameProfile
            ok(GuiLang.SUCCESS_TRANSFER, targetName)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_TRANSFER_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun kick(player: VoicePlayer, targetName: String): ServiceResult {
        val manager = requireManager()
        val group = manager.groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        if (!commands().canExecute("kick", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_KICK)
        }

        val target = voicePlayerByName(targetName)
            ?: return fail(GuiLang.ERROR_PLAYER_NOT_FOUND, targetName)

        if (!group.playersIds.contains(target.instance.uuid)) {
            return fail(GuiLang.ERROR_TARGET_NOT_MEMBER)
        }

        return try {
            manager.kick(group, target)
            ok(GuiLang.SUCCESS_KICK, targetName)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_KICK_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun ban(player: VoicePlayer, targetName: String): ServiceResult {
        val manager = requireManager()
        val group = manager.groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        if (!commands().canExecute("ban", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_BAN)
        }

        val target = voicePlayerByName(targetName)
            ?: return fail(GuiLang.ERROR_PLAYER_NOT_FOUND, targetName)

        if (group.isBanned(target.instance.uuid)) {
            return fail(GuiLang.ERROR_ALREADY_BANNED, targetName)
        }

        return try {
            manager.ban(group, target)
            ok(GuiLang.SUCCESS_BAN, targetName)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_BAN_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun unban(player: VoicePlayer, targetName: String): ServiceResult {
        val manager = requireManager()
        val group = manager.groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        // UnbanCommand uses ban.* permission prefix via ManagementCommand.
        if (!commands().canExecute("unban", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_BAN)
        }

        val target = group.bannedPlayers
            .firstOrNull { it.name.equals(targetName, ignoreCase = true) }
            ?: return fail(GuiLang.ERROR_NOT_BANNED, targetName)

        return try {
            manager.unban(group, target)
            ok(GuiLang.SUCCESS_UNBAN, target.name)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_UNBAN_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun setName(player: VoicePlayer, name: String): ServiceResult {
        requireManager().groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        val cmds = commands()
        if (!cmds.canExecute("set", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_SET)
        }
        if (!cmds.hasFlagPermission(player.instance, "name")) {
            return fail(GuiLang.ERROR_NO_PERMISSION_FLAG_NAME)
        }

        val group = requireManager().groupByPlayer[player.instance.uuid]!!
        val trimmed = name.trim()
        if (trimmed.length !in ProtocolConstants.MIN_GROUP_NAME_LENGTH..ProtocolConstants.MAX_GROUP_NAME_LENGTH) {
            return fail(GuiLang.ERROR_NAME_LENGTH)
        }
        if (group.name == trimmed) {
            return fail(GuiLang.ERROR_IDENTICAL_VALUE)
        }

        return try {
            group.name = trimmed
            ok(GuiLang.SUCCESS_SET_NAME, trimmed)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_SET_NAME_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun setPassword(player: VoicePlayer, password: String?): ServiceResult {
        val group = requireManager().groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        val cmds = commands()
        if (password == null) {
            if (!cmds.canExecute("unset", player.instance)) {
                return fail(GuiLang.ERROR_NO_PERMISSION_UNSET)
            }
            if (!cmds.hasFlagPermission(player.instance, "password")) {
                return fail(GuiLang.ERROR_NO_PERMISSION_FLAG_PASSWORD)
            }
            if (group.password == null) {
                return fail(GuiLang.ERROR_PASSWORD_NOT_SET)
            }
            return try {
                group.password = null
                group.notifyPlayersTranslatable("pv.addon.groups.notifications.password_unset")
                ok(GuiLang.SUCCESS_PASSWORD_UNSET)
            } catch (e: Exception) {
                fail(GuiLang.ERROR_SET_PASSWORD_FAILED, e.message ?: e.javaClass.simpleName)
            }
        }

        if (!cmds.canExecute("set", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_SET)
        }
        if (!cmds.hasFlagPermission(player.instance, "password")) {
            return fail(GuiLang.ERROR_NO_PERMISSION_FLAG_PASSWORD)
        }
        if (group.password == password) {
            return fail(GuiLang.ERROR_IDENTICAL_VALUE)
        }

        return try {
            group.password = password
            group.notifyPlayersTranslatable("pv.addon.groups.notifications.password_changed")
            ok(GuiLang.SUCCESS_SET_PASSWORD)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_SET_PASSWORD_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun setPersistent(player: VoicePlayer, persistent: Boolean): ServiceResult {
        val group = requireManager().groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        val cmds = commands()
        if (!cmds.canExecute("set", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_SET)
        }
        if (!cmds.hasFlagPermission(player.instance, "persistent")) {
            return fail(GuiLang.ERROR_NO_PERMISSION_FLAG_PERSISTENT)
        }
        if (group.persistent == persistent) {
            return fail(GuiLang.ERROR_IDENTICAL_VALUE)
        }

        return try {
            group.persistent = persistent
            group.notifyPlayersTranslatable(
                if (persistent) {
                    "pv.addon.groups.notifications.persistent_true"
                } else {
                    "pv.addon.groups.notifications.persistent_false"
                },
            )
            ok(GuiLang.SUCCESS_SET_PERSISTENT)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_SET_PERSISTENT_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun setPermissions(player: VoicePlayer, permissions: List<String>?): ServiceResult {
        val manager = requireManager()
        val group = manager.groupByPlayer[player.instance.uuid]
            ?: return fail(GuiLang.ERROR_NOT_IN_GROUP)

        val cmds = commands()
        val cleared = permissions == null || permissions.isEmpty()
        if (cleared) {
            if (!cmds.canExecute("unset", player.instance)) {
                return fail(GuiLang.ERROR_NO_PERMISSION_UNSET)
            }
            if (!cmds.hasFlagPermission(player.instance, "permissions")) {
                return fail(GuiLang.ERROR_NO_PERMISSION_FLAG_PERMISSIONS)
            }
            if (group.permissionsFilter.isEmpty()) {
                return fail(GuiLang.ERROR_PERMISSIONS_NOT_SET)
            }
            return try {
                group.permissionsFilter.clear()
                group.notifyPlayersTranslatable("pv.addon.groups.notifications.permissions_unset")
                ok(GuiLang.SUCCESS_PERMISSIONS_UNSET)
            } catch (e: Exception) {
                fail(GuiLang.ERROR_SET_PERMISSIONS_FAILED, e.message ?: e.javaClass.simpleName)
            }
        }

        if (!cmds.canExecute("set", player.instance)) {
            return fail(GuiLang.ERROR_NO_PERMISSION_SET)
        }
        if (!cmds.hasFlagPermission(player.instance, "permissions")) {
            return fail(GuiLang.ERROR_NO_PERMISSION_FLAG_PERMISSIONS)
        }

        val newValue = permissions.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        if (group.permissionsFilter == newValue) {
            return fail(GuiLang.ERROR_IDENTICAL_VALUE)
        }

        return try {
            group.permissionsFilter = newValue
            group.notifyPlayersTranslatable("pv.addon.groups.notifications.permissions_changed")
            group.onlinePlayers
                .filter { !group.hasPermission(it.instance) }
                .forEach { target ->
                    manager.leave(target)
                    target.instance.sendMessage(
                        McTextComponent.translatable("pv.addon.groups.notifications.kicked"),
                    )
                    group.notifyPlayersTranslatable(
                        "pv.addon.groups.notifications.player_kicked",
                        target.instance.name,
                    )
                }
            ok(GuiLang.SUCCESS_SET_PERMISSIONS)
        } catch (e: Exception) {
            fail(GuiLang.ERROR_SET_PERMISSIONS_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun inviteCandidates(player: VoicePlayer): List<PlayerRef> {
        val addon = resolve() ?: return emptyList()
        val group = addon.groupManager.groupByPlayer[player.instance.uuid] ?: return emptyList()

        val online = addon.getVisibleOnlinePlayers(player)
        val inGroup = group.onlinePlayers.map { it.instance.uuid }.toSet()

        return online
            .asSequence()
            .filter { it.instance.uuid != player.instance.uuid }
            .filter { it.instance.uuid !in inGroup }
            .filter { !group.isBanned(it.instance.uuid) }
            .map { PlayerRef(it.instance.uuid, it.instance.name) }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private fun ok(key: String, vararg args: String) =
        ServiceResult(true, key, args.toList())

    private fun fail(key: String, vararg args: String) =
        ServiceResult(false, key, args.toList())

    private fun requireAddon(): GroupsAddon =
        resolve() ?: error("pv-addon-groups is not loaded")

    private fun requireManager(): GroupsManager =
        requireAddon().groupManager

    private fun commands(): GroupsCommandAccess {
        cachedCommands?.let { return it }
        val access = GroupsCommandAccess(requireAddon())
        cachedCommands = access
        return access
    }

    private fun resolve(): GroupsAddon? {
        cachedAddon?.let { return it }

        val addonManager = voice.addonManager
        if (!addonManager.isLoaded(GROUPS_ADDON_ID)) return null

        val addon = addonManager.getAddon(GROUPS_ADDON_ID)
            .flatMap { it.instance }
            .filter { it is GroupsAddon }
            .map { it as GroupsAddon }
            .orElse(null)
            ?: return null

        if (!isGroupManagerReady(addon)) return null

        return addon.also {
            cachedAddon = it
            cachedCommands = GroupsCommandAccess(it)
        }
    }

    private fun isGroupManagerReady(addon: GroupsAddon): Boolean =
        runCatching { addon.groupManager }.isSuccess

    private fun toSnapshot(group: Group, player: VoicePlayer, currentId: UUID?): GroupSnapshot {
        val cmds = commands()
        val source = player.instance
        val ownerId = group.owner?.id
        val members = group.onlinePlayers.mapNotNull { vp ->
            runCatching {
                GroupMember(
                    uuid = vp.instance.uuid,
                    name = vp.instance.name,
                    isOwner = ownerId != null && vp.instance.uuid == ownerId,
                )
            }.getOrNull()
        }.sortedWith(compareByDescending<GroupMember> { it.isOwner }.thenBy { it.name.lowercase() })

        val banned = group.bannedPlayers
            .map { profileToRef(it) }
            .sortedBy { it.name.lowercase() }

        val canSet = cmds.canExecute("set", source)
        val canUnset = cmds.canExecute("unset", source)
        val canFlagPassword = cmds.hasFlagPermission(source, "password")
        val canFlagPersistent = cmds.hasFlagPermission(source, "persistent")
        val canFlagPermissions = cmds.hasFlagPermission(source, "permissions")
        val permissions = group.permissionsFilter.sorted()

        return GroupSnapshot(
            id = group.id,
            name = group.name,
            ownerName = group.owner?.name,
            onlinePlayers = group.onlinePlayerCount,
            totalPlayers = group.playersIds.size,
            passwordProtected = group.password != null,
            persistent = group.persistent,
            permissions = permissions,
            members = members,
            bannedPlayers = banned,
            isCurrent = currentId == group.id,
            isOwner = group.isOwner(player),
            canKick = cmds.canExecute("kick", source),
            canBan = cmds.canExecute("ban", source),
            canSetName = canSet && cmds.hasFlagPermission(source, "name"),
            canSetPassword = canSet && canFlagPassword,
            canUnsetPassword = canUnset && canFlagPassword && group.password != null,
            canSetPersistent = canSet && canFlagPersistent,
            canSetPermissions = canSet && canFlagPermissions,
            canUnsetPermissions = canUnset && canFlagPermissions && permissions.isNotEmpty(),
            canTransfer = cmds.canExecute("transfer", source),
        )
    }

    private fun defaultName(player: VoicePlayer): String =
        "${player.instance.name}'s group"

    private fun profileToRef(profile: McGameProfile): PlayerRef =
        PlayerRef(profile.id, profile.name)

    private fun voicePlayerByName(name: String): VoicePlayer? {
        val server = voice as? PlasmoVoiceServer ?: return null
        return server.playerManager.getPlayerByName(name).orElse(null)
    }

    private fun printGroupsDivider(source: su.plo.slib.api.command.McCommandSource) {
        source.sendMessage(McTextComponent.translatable("pv.addon.groups.divider"))
    }

    companion object {
        private const val GROUPS_ADDON_ID = "pv-addon-groups"
    }
}
