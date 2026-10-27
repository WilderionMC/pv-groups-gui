package su.plo.voice.groups.gui.client

import su.plo.voice.groups.gui.protocol.BrowsePage
import su.plo.voice.groups.gui.protocol.GroupSnapshot
import su.plo.voice.groups.gui.protocol.GuiLang
import su.plo.voice.groups.gui.protocol.GuiMessage
import su.plo.voice.groups.gui.protocol.PlayerRef
import java.util.concurrent.CopyOnWriteArrayList

data class StatusText(
    val translationKey: String,
    val args: List<String> = emptyList(),
)

data class GuiClientState(
    var connected: Boolean = false,
    var groupsAvailable: Boolean = false,
    var canBrowse: Boolean = false,
    var canCreate: Boolean = false,
    var canCreatePersistent: Boolean = false,
    var canCreatePermissions: Boolean = false,
    var status: StatusText? = null,
    var browse: BrowsePage? = null,
    var current: GroupSnapshot? = null,
    var inviteCandidates: List<PlayerRef> = emptyList(),
    /** Soft initial sync only — does not lock the whole UI. */
    var loading: Boolean = false,
    /** Lang key of the in-flight action, or null when idle. */
    var pendingAction: String? = null,
    var savingSettings: Boolean = false,
)

fun interface GuiStateListener {
    fun onGuiStateChanged(state: GuiClientState)
}

/**
 * Platform-agnostic controller: networking + state. Screens subscribe and render.
 */
class GroupsGuiController(
    private val send: (GuiMessage.C2S) -> Unit,
    private val runOnClientThread: (Runnable) -> Unit,
) {
    private val listeners = CopyOnWriteArrayList<GuiStateListener>()
    private val settingsChanges = java.util.ArrayDeque<GuiMessage.C2S>()
    @Volatile
    var state: GuiClientState = GuiClientState()
        private set

    fun addListener(listener: GuiStateListener) {
        listeners += listener
        listener.onGuiStateChanged(state)
    }

    fun removeListener(listener: GuiStateListener) {
        listeners -= listener
    }

    fun onOpen() {
        if (state.savingSettings) return
        update {
            loading = true
            pendingAction = null
            status = null
        }
        send(GuiMessage.C2S.Hello)
        send(GuiMessage.C2S.Browse(FIRST_PAGE))
        send(GuiMessage.C2S.ListInviteCandidates)
    }

    fun browse(page: Int) {
        send(GuiMessage.C2S.Browse(page))
    }

    fun create(name: String?, password: String?, persistent: Boolean, permissions: List<String> = emptyList()) {
        update {
            pendingAction = GuiLang.STATUS_CREATING
            status = StatusText(GuiLang.STATUS_CREATING)
        }
        send(
            GuiMessage.C2S.Create(
                name = name?.ifBlank { null },
                password = password?.ifBlank { null },
                persistent = persistent,
                permissions = permissions,
            )
        )
    }

    fun join(group: GroupSnapshot, password: String?) {
        update {
            pendingAction = GuiLang.STATUS_JOINING
            status = StatusText(GuiLang.STATUS_JOINING)
        }
        send(GuiMessage.C2S.Join(group.id, password?.ifBlank { null }))
    }

    fun leave() {
        update {
            pendingAction = GuiLang.STATUS_LEAVING
            status = StatusText(GuiLang.STATUS_LEAVING)
        }
        send(GuiMessage.C2S.Leave)
    }

    fun invite(playerName: String) {
        if (playerName.isBlank()) return
        update {
            pendingAction = GuiLang.STATUS_INVITING
            status = StatusText(GuiLang.STATUS_INVITING)
        }
        send(GuiMessage.C2S.Invite(playerName.trim()))
    }

    fun kick(playerName: String) {
        if (playerName.isBlank()) return
        update {
            pendingAction = GuiLang.STATUS_KICKING
            status = StatusText(GuiLang.STATUS_KICKING)
        }
        send(GuiMessage.C2S.Kick(playerName.trim()))
    }

    fun ban(playerName: String) {
        if (playerName.isBlank()) return
        update {
            pendingAction = GuiLang.STATUS_BANNING
            status = StatusText(GuiLang.STATUS_BANNING)
        }
        send(GuiMessage.C2S.Ban(playerName.trim()))
    }

    fun unban(playerName: String) {
        if (playerName.isBlank()) return
        update {
            pendingAction = GuiLang.STATUS_UNBANNING
            status = StatusText(GuiLang.STATUS_UNBANNING)
        }
        send(GuiMessage.C2S.Unban(playerName.trim()))
    }

    fun refreshInviteCandidates() {
        send(GuiMessage.C2S.ListInviteCandidates)
    }

    fun dismissStatus() {
        if (state.pendingAction == null && state.status != null) update { status = null }
    }

    fun delete() {
        update {
            pendingAction = GuiLang.STATUS_DELETING
            status = StatusText(GuiLang.STATUS_DELETING)
        }
        send(GuiMessage.C2S.Delete)
    }

    fun transfer(playerName: String) {
        if (playerName.isBlank()) return
        update {
            pendingAction = GuiLang.STATUS_TRANSFERRING
            status = StatusText(GuiLang.STATUS_TRANSFERRING)
        }
        send(GuiMessage.C2S.Transfer(playerName.trim()))
    }

    fun setName(name: String) {
        if (name.isBlank()) return
        update {
            pendingAction = GuiLang.STATUS_SETTING_NAME
            status = StatusText(GuiLang.STATUS_SETTING_NAME)
        }
        send(GuiMessage.C2S.SetName(name.trim()))
    }

    fun setPassword(password: String?) {
        update {
            pendingAction = GuiLang.STATUS_SETTING_PASSWORD
            status = StatusText(GuiLang.STATUS_SETTING_PASSWORD)
        }
        send(GuiMessage.C2S.SetPassword(password?.ifBlank { null }))
    }

    fun clearPassword() {
        setPassword(null)
    }

    fun setPersistent(persistent: Boolean) {
        update {
            pendingAction = GuiLang.STATUS_SETTING_PERSISTENT
            status = StatusText(GuiLang.STATUS_SETTING_PERSISTENT)
        }
        send(GuiMessage.C2S.SetPersistent(persistent))
    }

    fun setPermissions(permissions: List<String>?) {
        update {
            pendingAction = GuiLang.STATUS_SETTING_PERMISSIONS
            status = StatusText(GuiLang.STATUS_SETTING_PERMISSIONS)
        }
        send(GuiMessage.C2S.SetPermissions(permissions))
    }

    fun clearPermissions() {
        setPermissions(null)
    }

    /** Apply settings in order; a rejected change stops the remaining requests. */
    fun saveSettings(changes: List<GuiMessage.C2S>) {
        if (state.pendingAction != null || changes.isEmpty()) return
        require(changes.all { it is GuiMessage.C2S.SetName || it is GuiMessage.C2S.SetPassword ||
            it is GuiMessage.C2S.SetPersistent || it is GuiMessage.C2S.SetPermissions })
        settingsChanges.addAll(changes)
        sendNextSetting()
    }

    private fun sendNextSetting() {
        update {
            savingSettings = true
            pendingAction = GuiLang.STATUS_SETTINGS
            status = StatusText(GuiLang.STATUS_SETTINGS)
        }
        send(settingsChanges.removeFirst())
    }

    fun refreshInfo() {
        send(GuiMessage.C2S.Info)
    }

    fun handle(message: GuiMessage.S2C) {
        runOnClientThread {
            when (message) {
                is GuiMessage.S2C.Hello -> update {
                    connected = true
                    groupsAvailable = message.groupsAvailable
                    canBrowse = message.canBrowse
                    canCreate = message.canCreate
                    canCreatePersistent = message.canCreatePersistent
                    canCreatePermissions = message.canCreatePermissions
                    loading = false
                    if (!message.groupsAvailable) {
                        status = StatusText(GuiLang.STATUS_GROUPS_UNAVAILABLE)
                    }
                }
                is GuiMessage.S2C.Browse -> if (state.browse != message.page || state.loading) update {
                    browse = message.page
                    loading = false
                }
                is GuiMessage.S2C.ActionResult -> {
                    val wasSaving = state.savingSettings
                    if (state.savingSettings && message.success && settingsChanges.isNotEmpty()) {
                        sendNextSetting()
                        return@runOnClientThread
                    }
                    val saved = state.savingSettings && message.success
                    settingsChanges.clear()
                    update {
                        status = if (saved) StatusText(GuiLang.SUCCESS_SETTINGS) else StatusText(message.translationKey, message.args)
                        pendingAction = null
                        savingSettings = false
                        loading = false
                    }
                    if (message.success || wasSaving) {
                        refreshBrowse()
                    }
                }
                is GuiMessage.S2C.State -> if (state.current != message.current || state.loading) update {
                    current = message.current
                    if (!savingSettings) pendingAction = null
                    loading = false
                }
                is GuiMessage.S2C.InviteCandidates -> if (state.inviteCandidates != message.players) update {
                    inviteCandidates = message.players
                }
            }
        }
    }

    private fun refreshBrowse() {
        val page = state.browse?.page ?: FIRST_PAGE
        send(GuiMessage.C2S.Browse(page))
    }

    private fun update(block: GuiClientState.() -> Unit) {
        val next = state.copy().apply(block)
        state = next
        listeners.forEach { it.onGuiStateChanged(next) }
    }

    companion object {
        private const val FIRST_PAGE = 1
    }
}
