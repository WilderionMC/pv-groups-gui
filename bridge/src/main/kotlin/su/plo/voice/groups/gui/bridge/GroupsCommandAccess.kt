package su.plo.voice.groups.gui.bridge

import su.plo.slib.api.command.McCommandSource
import su.plo.voice.groups.GroupsAddon
import su.plo.voice.groups.command.CommandHandler
import su.plo.voice.groups.command.SubCommand
import java.lang.reflect.Field

/**
 * Reuses pv-addon-groups [CommandHandler] transport:
 * [McCommandManager.registeredCommands] → reflect private `subCommands` →
 * [SubCommand.checkCanExecute] / [CommandHandler.flags].
 *
 * Permission suffixes for join/browse bypass are taken from groups command sources
 * (JoinCommand / BrowseCommand), not via relocated `kotlin.Pair` reflection.
 */
class GroupsCommandAccess(
    private val addon: GroupsAddon,
) {
    private val handler: CommandHandler? by lazy { resolveHandler() }
    private val subCommands: Map<String, SubCommand> by lazy { resolveSubCommands() }

    fun canExecute(command: String, source: McCommandSource): Boolean =
        subCommands[command]?.checkCanExecute(source) == true

    /**
     * Same as groups `McCommandSource.hasAddonPermission`.
     */
    fun hasAddonPermission(source: McCommandSource, suffix: String): Boolean =
        source.hasPermission(ROOT_WILDCARD) || source.hasPermission("$PERM_PREFIX.$suffix")

    /**
     * Same as groups `McCommandSource.hasFlagPermission`.
     */
    fun hasFlagPermission(source: McCommandSource, flag: String): Boolean =
        source.hasPermission(ROOT_WILDCARD) || source.hasPermission("$PERM_PREFIX.flag.$flag")

    fun flags(): Set<String> =
        handler?.flags?.keys.orEmpty()

    /** Join bypass passwords/bans/group filters: join.all / join.* (JoinCommand). */
    fun canBypassJoinRestrictions(source: McCommandSource): Boolean =
        JOIN_BYPASS_SUFFIXES.any { hasAddonPermission(source, it) }

    /** Browse visibility of password/permission-gated groups (BrowseCommand). */
    fun canBrowseAll(source: McCommandSource): Boolean =
        BROWSE_ALL_SUFFIXES.any { hasAddonPermission(source, it) }

    private fun resolveHandler(): CommandHandler? {
        val commands = addon.voiceServer.minecraftServer.commandManager.registeredCommands
        val byName = commands["groups"] as? CommandHandler
        if (byName != null) return byName
        return commands.values.firstOrNull { it is CommandHandler } as? CommandHandler
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveSubCommands(): Map<String, SubCommand> {
        val commandHandler = handler ?: return emptyMap()
        val field = findField(commandHandler.javaClass, "subCommands") ?: return emptyMap()
        field.isAccessible = true
        val raw = field.get(commandHandler) as? Map<*, *> ?: return emptyMap()
        return raw
            .mapNotNull { (key, value) ->
                val name = key as? String ?: return@mapNotNull null
                val sub = value as? SubCommand ?: return@mapNotNull null
                name to sub
            }
            .toMap()
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    companion object {
        private const val PERM_PREFIX = "pv.addon.groups"
        private const val ROOT_WILDCARD = "$PERM_PREFIX.*"

        /** Non-base join permissions from JoinCommand.permissions. */
        private val JOIN_BYPASS_SUFFIXES = listOf("join.all", "join.*")

        /** Non-base browse permissions from BrowseCommand.permissions. */
        private val BROWSE_ALL_SUFFIXES = listOf("browse.all", "browse.*")
    }
}
