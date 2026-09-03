package su.plo.voice.groups.gui.fabric

import io.wispforest.owo.ui.base.BaseUIModelScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.StackLayout
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import su.plo.voice.groups.gui.client.GroupsGuiController
import su.plo.voice.groups.gui.client.GuiClientState
import su.plo.voice.groups.gui.client.GuiStateListener
import su.plo.voice.groups.gui.client.StatusText
import su.plo.voice.groups.gui.protocol.GroupMember
import su.plo.voice.groups.gui.protocol.GroupSnapshot
import su.plo.voice.groups.gui.protocol.GuiLang
import su.plo.voice.groups.gui.protocol.PlayerRef
import java.util.UUID

/**
 * Cascading groups UI: main browse → create popup → current group → invite picker.
 */
class GroupsGuiScreen(
    private val parent: Screen?,
    private val controller: GroupsGuiController,
) : BaseUIModelScreen<FlowLayout>(
    FlowLayout::class.java,
    ResourceLocation(MOD_ID, MODEL_PATH),
), GuiStateListener {

    private enum class View {
        MAIN,
        CREATE,
        GROUP,
        INVITE,
        CONFIRM,
        SETTINGS,
        TRANSFER,
    }

    private sealed interface ConfirmAction {
        data object Delete : ConfirmAction
        data object Leave : ConfirmAction
        data class Kick(val playerName: String) : ConfirmAction
        data class Ban(val playerName: String) : ConfirmAction
        data class Transfer(val playerName: String) : ConfirmAction
    }

    private var state: GuiClientState = controller.state
    private var view: View = View.MAIN
    /** When true, stay on MAIN even if the player is still in a group ("All groups"). */
    private var browseWhileGrouped: Boolean = false
    private var inviteFilter: String = ""
    private var transferFilter: String = ""
    private var selectedInviteName: String? = null
    private var selectedTransferName: String? = null
    private var selectedGroupId: UUID? = null
    private var pendingConfirm: ConfirmAction? = null
    private var createPersistent: Boolean = false
    private var editPersistent: Boolean = false

    private lateinit var rootLayout: FlowLayout
    private lateinit var viewsHost: StackLayout
    private lateinit var viewMain: FlowLayout
    private lateinit var viewCreate: FlowLayout
    private lateinit var viewGroup: FlowLayout
    private lateinit var viewInvite: FlowLayout
    private lateinit var viewConfirm: FlowLayout
    private lateinit var viewSettings: FlowLayout
    private lateinit var viewTransfer: FlowLayout

    private lateinit var mainStatus: LabelComponent
    private lateinit var createStatus: LabelComponent
    private lateinit var settingsStatus: LabelComponent
    private lateinit var transferStatus: LabelComponent
    private lateinit var confirmTitle: LabelComponent
    private lateinit var confirmMessage: LabelComponent
    private lateinit var groupTitle: LabelComponent
    private lateinit var groupSubtitle: LabelComponent
    private lateinit var groupStatus: LabelComponent
    private lateinit var inviteStatus: LabelComponent

    private lateinit var groupsList: FlowLayout
    private lateinit var membersList: FlowLayout
    private lateinit var inviteList: FlowLayout
    private lateinit var transferList: FlowLayout

    private lateinit var nameField: TextBoxComponent
    private lateinit var passwordField: TextBoxComponent
    private lateinit var createPermissionsField: TextBoxComponent
    private lateinit var editNameField: TextBoxComponent
    private lateinit var editPasswordField: TextBoxComponent
    private lateinit var editPermissionsField: TextBoxComponent
    private lateinit var joinPasswordField: TextBoxComponent
    private lateinit var inviteFilterField: TextBoxComponent
    private lateinit var transferFilterField: TextBoxComponent
    private lateinit var createButton: ButtonComponent
    private lateinit var createPersistentButton: ButtonComponent
    private lateinit var openCreateButton: ButtonComponent
    private lateinit var joinButton: ButtonComponent
    private lateinit var confirmInviteButton: ButtonComponent
    private lateinit var disbandButton: ButtonComponent
    private lateinit var openInviteButton: ButtonComponent
    private lateinit var openTransferButton: ButtonComponent
    private lateinit var openSettingsButton: ButtonComponent
    private lateinit var saveSettingsButton: ButtonComponent
    private lateinit var removePasswordButton: ButtonComponent
    private lateinit var editPersistentButton: ButtonComponent
    private lateinit var clearPermissionsButton: ButtonComponent
    private lateinit var confirmTransferButton: ButtonComponent
    private lateinit var confirmYesButton: ButtonComponent
    private lateinit var confirmNoButton: ButtonComponent

    override fun build(root: FlowLayout) {
        rootLayout = root
        viewsHost = root.childById(StackLayout::class.java, "views")
        viewMain = root.childById(FlowLayout::class.java, "view-main")
        viewCreate = root.childById(FlowLayout::class.java, "view-create")
        viewGroup = root.childById(FlowLayout::class.java, "view-group")
        viewInvite = root.childById(FlowLayout::class.java, "view-invite")
        viewConfirm = root.childById(FlowLayout::class.java, "view-confirm")
        viewSettings = root.childById(FlowLayout::class.java, "view-settings")
        viewTransfer = root.childById(FlowLayout::class.java, "view-transfer")

        mainStatus = root.childById(LabelComponent::class.java, "main-status")
        createStatus = root.childById(LabelComponent::class.java, "create-status")
        settingsStatus = root.childById(LabelComponent::class.java, "settings-status")
        transferStatus = root.childById(LabelComponent::class.java, "transfer-status")
        confirmTitle = root.childById(LabelComponent::class.java, "confirm-title")
        confirmMessage = root.childById(LabelComponent::class.java, "confirm-message")
        groupTitle = root.childById(LabelComponent::class.java, "group-title")
        groupSubtitle = root.childById(LabelComponent::class.java, "group-subtitle")
        groupStatus = root.childById(LabelComponent::class.java, "group-status")
        inviteStatus = root.childById(LabelComponent::class.java, "invite-status")

        groupsList = root.childById(FlowLayout::class.java, "groups-list")
        membersList = root.childById(FlowLayout::class.java, "members-list")
        inviteList = root.childById(FlowLayout::class.java, "invite-list")
        transferList = root.childById(FlowLayout::class.java, "transfer-list")

        nameField = root.childById(TextBoxComponent::class.java, "name-field")
        passwordField = root.childById(TextBoxComponent::class.java, "password-field")
        createPermissionsField = root.childById(TextBoxComponent::class.java, "create-permissions-field")
        editNameField = root.childById(TextBoxComponent::class.java, "edit-name-field")
        editPasswordField = root.childById(TextBoxComponent::class.java, "edit-password-field")
        editPermissionsField = root.childById(TextBoxComponent::class.java, "edit-permissions-field")
        joinPasswordField = root.childById(TextBoxComponent::class.java, "join-password-field")
        inviteFilterField = root.childById(TextBoxComponent::class.java, "invite-filter-field")
        transferFilterField = root.childById(TextBoxComponent::class.java, "transfer-filter-field")
        createButton = root.childById(ButtonComponent::class.java, "create-button")
        createPersistentButton = root.childById(ButtonComponent::class.java, "create-persistent-button")
        openCreateButton = root.childById(ButtonComponent::class.java, "open-create-button")
        joinButton = root.childById(ButtonComponent::class.java, "join-button")
        confirmInviteButton = root.childById(ButtonComponent::class.java, "confirm-invite-button")
        disbandButton = root.childById(ButtonComponent::class.java, "disband-button")
        openInviteButton = root.childById(ButtonComponent::class.java, "open-invite-button")
        openTransferButton = root.childById(ButtonComponent::class.java, "open-transfer-button")
        openSettingsButton = root.childById(ButtonComponent::class.java, "open-settings-button")
        saveSettingsButton = root.childById(ButtonComponent::class.java, "save-settings-button")
        removePasswordButton = root.childById(ButtonComponent::class.java, "remove-password-button")
        editPersistentButton = root.childById(ButtonComponent::class.java, "edit-persistent-button")
        clearPermissionsButton = root.childById(ButtonComponent::class.java, "clear-permissions-button")
        confirmTransferButton = root.childById(ButtonComponent::class.java, "confirm-transfer-button")
        confirmYesButton = root.childById(ButtonComponent::class.java, "confirm-yes-button")
        confirmNoButton = root.childById(ButtonComponent::class.java, "confirm-no-button")

        nameField.setHint(Component.translatable(GuiLang.HINT_NAME))
        passwordField.setHint(Component.translatable(GuiLang.HINT_PASSWORD_CREATE))
        createPermissionsField.setHint(Component.translatable(GuiLang.HINT_PERMISSIONS))
        joinPasswordField.setHint(Component.translatable(GuiLang.HINT_PASSWORD))
        inviteFilterField.setHint(Component.translatable(GuiLang.HINT_INVITE))
        transferFilterField.setHint(Component.translatable(GuiLang.HINT_INVITE))
        editNameField.setHint(Component.translatable(GuiLang.HINT_NAME))
        editPasswordField.setHint(Component.translatable(GuiLang.HINT_PASSWORD_SET))
        editPermissionsField.setHint(Component.translatable(GuiLang.HINT_PERMISSIONS))
        createPersistentButton.hint(GuiLang.TOOLTIP_PERSISTENT)
        editPersistentButton.hint(GuiLang.TOOLTIP_PERSISTENT)
        clearPermissionsButton.hint(GuiLang.TOOLTIP_CLEAR_PERMISSIONS)
        inviteFilterField.onChanged().subscribe { value ->
            inviteFilter = value
            if (view == View.INVITE) rebuildInviteList()
        }
        transferFilterField.onChanged().subscribe { value ->
            transferFilter = value
            if (view == View.TRANSFER) rebuildTransferList()
        }

        openInviteButton.setMessage(symbol("+", ChatFormatting.GREEN))
        openTransferButton.setMessage(symbol("🗡", ChatFormatting.GREEN))
        openSettingsButton.setMessage(symbol("✎", ChatFormatting.YELLOW))
        disbandButton.setMessage(symbol("×", ChatFormatting.RED))

        openInviteButton.hint(GuiLang.TOOLTIP_INVITE)
        openTransferButton.hint(GuiLang.TOOLTIP_TRANSFER)
        openSettingsButton.hint(GuiLang.TOOLTIP_SETTINGS)
        disbandButton.hint(GuiLang.TOOLTIP_DISBAND)
        root.childById(ButtonComponent::class.java, "all-groups-button").hint(GuiLang.TOOLTIP_ALL_GROUPS)
        root.childById(ButtonComponent::class.java, "refresh-button").hint(GuiLang.TOOLTIP_REFRESH)
        openCreateButton.hint(GuiLang.TOOLTIP_CREATE)
        joinButton.hint(GuiLang.TOOLTIP_JOIN)
        createButton.hint(GuiLang.TOOLTIP_CREATE)
        root.childById(ButtonComponent::class.java, "create-back-button").hint(GuiLang.TOOLTIP_BACK)
        confirmInviteButton.hint(GuiLang.TOOLTIP_CONFIRM_INVITE)
        root.childById(ButtonComponent::class.java, "invite-back-button").hint(GuiLang.TOOLTIP_BACK)
        confirmYesButton.hint(GuiLang.TOOLTIP_CONFIRM_YES)
        confirmNoButton.hint(GuiLang.TOOLTIP_CONFIRM_NO)
        saveSettingsButton.hint(GuiLang.TOOLTIP_SAVE)
        removePasswordButton.hint(GuiLang.TOOLTIP_REMOVE_PASSWORD)
        confirmTransferButton.hint(GuiLang.TOOLTIP_CONFIRM_TRANSFER)
        root.childById(ButtonComponent::class.java, "settings-back-button").hint(GuiLang.TOOLTIP_BACK)
        root.childById(ButtonComponent::class.java, "transfer-back-button").hint(GuiLang.TOOLTIP_BACK)

        root.childById(ButtonComponent::class.java, "refresh-button").onPress {
            controller.onOpen()
        }
        openCreateButton.onPress {
            nameField.text("")
            passwordField.text("")
            createPermissionsField.text("")
            createPersistent = false
            syncPersistentButtons()
            createStatus.text(Component.empty())
            showView(View.CREATE)
        }
        createPersistentButton.onPress {
            if (!state.canCreatePersistent) return@onPress
            createPersistent = !createPersistent
            syncPersistentButtons()
        }
        createButton.onPress {
            val permissions = parsePermissions(createPermissionsField.value)
                .takeIf { state.canCreatePermissions }
                .orEmpty()
            controller.create(
                nameField.value,
                passwordField.value.ifBlank { null },
                createPersistent && state.canCreatePersistent,
                permissions,
            )
        }
        root.childById(ButtonComponent::class.java, "create-back-button").onPress {
            showView(View.MAIN)
        }
        joinButton.onPress {
            val selected = selectedGroup(state) ?: return@onPress
            if (isCurrentGroup(selected)) {
                browseWhileGrouped = false
                showView(View.GROUP)
            } else {
                browseWhileGrouped = false
                controller.join(selected, joinPasswordField.value.ifBlank { null })
            }
        }

        openInviteButton.onPress {
            selectedInviteName = null
            inviteFilter = ""
            inviteFilterField.text("")
            showView(View.INVITE)
        }
        openTransferButton.onPress {
            selectedTransferName = null
            transferFilter = ""
            transferFilterField.text("")
            showView(View.TRANSFER)
        }
        openSettingsButton.onPress {
            val current = state.current ?: return@onPress
            editNameField.text(current.name)
            editPasswordField.text("")
            editPermissionsField.text(current.permissions.joinToString(", "))
            editPersistent = current.persistent
            syncPersistentButtons()
            settingsStatus.text(Component.empty())
            showView(View.SETTINGS)
        }
        saveSettingsButton.onPress { saveSettings() }
        removePasswordButton.onPress { controller.clearPassword() }
        editPersistentButton.onPress {
            val current = state.current ?: return@onPress
            if (!current.canSetPersistent) return@onPress
            editPersistent = !editPersistent
            syncPersistentButtons()
        }
        clearPermissionsButton.onPress { controller.clearPermissions() }
        root.childById(ButtonComponent::class.java, "settings-back-button").onPress {
            showView(View.GROUP)
        }
        confirmTransferButton.onPress {
            selectedTransferName?.let { showConfirm(ConfirmAction.Transfer(it)) }
        }
        root.childById(ButtonComponent::class.java, "transfer-back-button").onPress {
            showView(View.GROUP)
        }
        disbandButton.onPress { showConfirm(ConfirmAction.Delete) }
        root.childById(ButtonComponent::class.java, "all-groups-button").onPress {
            browseWhileGrouped = true
            selectedGroupId = null
            controller.browse(1)
            showView(View.MAIN)
        }

        confirmInviteButton.onPress {
            selectedInviteName?.let { controller.invite(it) }
        }
        root.childById(ButtonComponent::class.java, "invite-back-button").onPress {
            showView(View.GROUP)
        }
        confirmYesButton.onPress { confirmPendingAction() }
        confirmNoButton.onPress { cancelConfirm() }


        // Detach all views; showView mounts exactly one
        viewsHost.clearChildren()

        controller.addListener(this)
        resolveInitialView()
        showView(view)
    }

    override fun init() {
        super.init()
        syncLabelWidths()
    }

    override fun removed() {
        controller.removeListener(this)
        super.removed()
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    override fun onGuiStateChanged(state: GuiClientState) {
        this.state = state
        if (!::rootLayout.isInitialized) return
        if (rootLayout.hasParent()) {
            rootLayout.queue { onStateSynced(state) }
        } else {
            onStateSynced(state)
        }
    }

    private fun onStateSynced(state: GuiClientState) {
        val next = when {
            view == View.CREATE && state.current == null -> View.CREATE
            state.pendingAction == GuiLang.STATUS_JOINING -> view
            state.pendingAction == GuiLang.STATUS_LEAVING -> view
            state.current == null -> {
                if (view != View.MAIN && view != View.CREATE) {
                    browseWhileGrouped = false
                }
                pendingConfirm = null
                if (view == View.CREATE) View.CREATE else View.MAIN
            }
            view == View.INVITE &&
                state.pendingAction == null &&
                state.status?.translationKey == GuiLang.SUCCESS_INVITE -> {
                selectedInviteName = null
                View.GROUP
            }
            view == View.TRANSFER &&
                state.pendingAction == null &&
                state.status?.translationKey == GuiLang.SUCCESS_TRANSFER -> {
                selectedTransferName = null
                View.GROUP
            }
            view == View.SETTINGS &&
                state.pendingAction == null &&
                settingsSaved(state.status?.translationKey) -> View.GROUP
            state.current != null && !browseWhileGrouped &&
                view != View.INVITE && view != View.CONFIRM &&
                view != View.SETTINGS && view != View.TRANSFER -> View.GROUP
            else -> view
        }
        if (next != view) {
            showView(next)
        } else {
            applyState(state)
        }
    }

    private fun resolveInitialView() {
        browseWhileGrouped = false
        view = if (state.current != null) View.GROUP else View.MAIN
    }

    private fun showView(next: View) {
        view = next
        if (!::viewsHost.isInitialized) return
        viewsHost.clearChildren()
        when (next) {
            View.MAIN -> viewsHost.child(viewMain)
            View.CREATE -> viewsHost.child(viewCreate)
            View.GROUP -> viewsHost.child(viewGroup)
            View.INVITE -> viewsHost.child(viewInvite)
            View.CONFIRM -> viewsHost.child(viewConfirm)
            View.SETTINGS -> viewsHost.child(viewSettings)
            View.TRANSFER -> viewsHost.child(viewTransfer)
        }
        applyState(state)
    }

    private fun applyState(state: GuiClientState) {
        syncLabelWidths()
        val busy = state.pendingAction != null
        val status = state.status

        when (view) {
            View.MAIN -> {
                mainStatus.text(if (status != null) statusComponent(status) else Component.empty())
                rebuildGroups(state, busy)
                openCreateButton.active(!busy && state.canCreate)
                updateJoinButton(state, busy)
            }
            View.CREATE -> {
                createStatus.text(if (status != null) statusComponent(status) else Component.empty())
                createButton.active(!busy && state.canCreate)
                createPersistentButton.active(!busy && state.canCreatePersistent)
                createPermissionsField.setEditable(!busy && state.canCreatePermissions)
                syncPersistentButtons()
            }
            View.GROUP -> {
                val current = state.current
                groupTitle.text(
                    if (current != null) {
                        Component.literal(current.name)
                    } else {
                        Component.literal("—")
                    },
                )
                groupSubtitle.text(
                    if (current != null) {
                        Component.translatable(
                            GuiLang.STATUS_IN_GROUP,
                            current.onlinePlayers.toString(),
                        )
                    } else {
                        Component.translatable(GuiLang.STATUS_NOT_IN_GROUP)
                    },
                )
                groupStatus.text(if (status != null) statusComponent(status) else Component.empty())
                rebuildMembers(state, busy)
                val canManage = current != null
                openInviteButton.active(!busy && canManage)
                openTransferButton.active(!busy && current?.canTransfer == true)
                openSettingsButton.active(
                    !busy && current != null && canOpenSettings(current),
                )
                disbandButton.active(!busy && canManage)
            }
            View.SETTINGS -> {
                settingsStatus.text(if (status != null) statusComponent(status) else Component.empty())
                val current = state.current
                saveSettingsButton.active(
                    !busy && current != null && (
                        current.canSetName ||
                            current.canSetPassword ||
                            current.canSetPersistent ||
                            current.canSetPermissions
                        ),
                )
                removePasswordButton.active(!busy && current?.canUnsetPassword == true)
                editPersistentButton.active(!busy && current?.canSetPersistent == true)
                editPermissionsField.setEditable(!busy && current?.canSetPermissions == true)
                clearPermissionsButton.active(!busy && current?.canUnsetPermissions == true)
                syncPersistentButtons()
            }
            View.TRANSFER -> {
                transferStatus.text(if (status != null) statusComponent(status) else Component.empty())
                rebuildTransferList()
                confirmTransferButton.active(!busy && selectedTransferName != null)
            }
            View.INVITE -> {
                inviteStatus.text(if (status != null) statusComponent(status) else Component.empty())
                rebuildInviteList()
                confirmInviteButton.active(!busy && selectedInviteName != null)
            }
            View.CONFIRM -> {
                confirmYesButton.active(!busy && pendingConfirm != null)
                confirmNoButton.active(!busy)
            }
        }
    }

    private fun showConfirm(action: ConfirmAction) {
        pendingConfirm = action
        val (titleKey, message) = confirmContent(action)
        confirmTitle.text(Component.translatable(titleKey))
        confirmMessage.text(message)
        syncLabelWidths()
        showView(View.CONFIRM)
    }

    private fun confirmContent(action: ConfirmAction): Pair<String, Component> =
        when (action) {
            ConfirmAction.Delete -> GuiLang.TITLE_CONFIRM_DELETE to
                Component.translatable(GuiLang.CONFIRM_DELETE)
            ConfirmAction.Leave -> GuiLang.TITLE_CONFIRM_LEAVE to
                Component.translatable(GuiLang.CONFIRM_LEAVE)
            is ConfirmAction.Kick -> GuiLang.TITLE_CONFIRM_KICK to
                Component.translatable(GuiLang.CONFIRM_KICK, action.playerName)
            is ConfirmAction.Ban -> GuiLang.TITLE_CONFIRM_BAN to
                Component.translatable(GuiLang.CONFIRM_BAN, action.playerName)
            is ConfirmAction.Transfer -> GuiLang.TITLE_CONFIRM_TRANSFER to
                Component.translatable(GuiLang.CONFIRM_TRANSFER, action.playerName)
        }

    private fun cancelConfirm() {
        pendingConfirm = null
        showView(View.GROUP)
    }

    private fun confirmPendingAction() {
        val action = pendingConfirm ?: return
        pendingConfirm = null
        when (action) {
            ConfirmAction.Delete -> controller.delete()
            ConfirmAction.Leave -> {
                browseWhileGrouped = false
                controller.leave()
            }
            is ConfirmAction.Kick -> controller.kick(action.playerName)
            is ConfirmAction.Ban -> controller.ban(action.playerName)
            is ConfirmAction.Transfer -> controller.transfer(action.playerName)
        }
        showView(View.GROUP)
    }

    private fun settingsSaved(key: String?): Boolean =
        key == GuiLang.SUCCESS_SET_NAME ||
            key == GuiLang.SUCCESS_SET_PASSWORD ||
            key == GuiLang.SUCCESS_PASSWORD_UNSET ||
            key == GuiLang.SUCCESS_SET_PERSISTENT ||
            key == GuiLang.SUCCESS_SET_PERMISSIONS ||
            key == GuiLang.SUCCESS_PERMISSIONS_UNSET

    private fun saveSettings() {
        val current = state.current ?: return
        val newName = editNameField.value.trim()
        val newPassword = editPasswordField.value.trim()
        val newPermissions = parsePermissions(editPermissionsField.value)
        if (current.canSetName && newName.isNotBlank() && newName != current.name) {
            controller.setName(newName)
        }
        if (current.canSetPassword && newPassword.isNotBlank()) {
            controller.setPassword(newPassword)
        }
        if (current.canSetPersistent && editPersistent != current.persistent) {
            controller.setPersistent(editPersistent)
        }
        if (current.canSetPermissions && newPermissions.toSet() != current.permissions.toSet()) {
            controller.setPermissions(newPermissions.ifEmpty { null })
        }
    }

    private fun canOpenSettings(current: GroupSnapshot): Boolean =
        current.canSetName ||
            current.canSetPassword ||
            current.canUnsetPassword ||
            current.canSetPersistent ||
            current.canSetPermissions ||
            current.canUnsetPermissions

    private fun syncPersistentButtons() {
        if (!::createPersistentButton.isInitialized) return
        createPersistentButton.setMessage(
            Component.translatable(
                if (createPersistent) GuiLang.BUTTON_PERSISTENT_ON else GuiLang.BUTTON_PERSISTENT_OFF,
            ),
        )
        editPersistentButton.setMessage(
            Component.translatable(
                if (editPersistent) GuiLang.BUTTON_PERSISTENT_ON else GuiLang.BUTTON_PERSISTENT_OFF,
            ),
        )
    }

    private fun parsePermissions(raw: String): List<String> =
        raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun rebuildTransferList() {
        transferList.clearChildren()
        val members = transferCandidates()
        if (members.isEmpty()) {
            transferList.child(expandEmpty(GuiLang.STATUS_TRANSFER_EMPTY))
            confirmTransferButton.active(false)
            return
        }

        val busy = state.pendingAction != null
        for (member in members) {
            transferList.child(transferRow(member, busy))
        }
        confirmTransferButton.active(!busy && selectedTransferName != null)
    }

    private fun transferCandidates(): List<GroupMember> {
        val current = state.current ?: return emptyList()
        val selfUuid = minecraft?.player?.uuid
        val filter = transferFilter.trim()
        return current.members
            .asSequence()
            .filter { member -> !member.isOwner && member.uuid != selfUuid }
            .filter { member ->
                filter.isEmpty() || member.name.contains(filter, ignoreCase = true)
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private fun transferRow(member: GroupMember, busy: Boolean): FlowLayout {
        val name = member.name
        val selected = selectedTransferName.equals(name, ignoreCase = true)
        val row = model.expandTemplate(
            FlowLayout::class.java,
            "invite-row",
            mapOf("name" to name),
        )
        prependHead(row, member.uuid)

        val button = row.childById(ButtonComponent::class.java, "invite-pick-btn")
        val prefix = if (selected) Component.literal("> ") else Component.empty()
        button.setMessage(
            Component.empty()
                .append(prefix)
                .append(Component.literal(name)),
        )
        button.active(!busy)
        button.onPress {
            selectedTransferName = name
            rebuildTransferList()
        }
        return row
    }

    private fun rebuildGroups(state: GuiClientState, busy: Boolean) {
        groupsList.clearChildren()
        val groups = state.browse?.groups.orEmpty()
        if (groups.isEmpty()) {
            selectedGroupId = null
            groupsList.child(
                expandEmpty(if (state.loading) GuiLang.STATUS_LOADING else GuiLang.STATUS_EMPTY),
            )
            return
        }

        if (selectedGroupId != null && groups.none { it.id == selectedGroupId }) {
            selectedGroupId = null
        }

        for (group in groups) {
            groupsList.child(groupRow(group, busy))
        }
    }

    private fun groupRow(group: GroupSnapshot, busy: Boolean): FlowLayout {
        val selected = selectedGroupId == group.id
        val nameLine = groupListLabel(group)
        val row = model.expandTemplate(
            FlowLayout::class.java,
            "group-row",
            mapOf("label" to nameLine.string),
        )

        val pick = row.childById(ButtonComponent::class.java, "group-pick-btn")
        val prefix = if (selected) Component.literal("> ") else Component.empty()
        pick.setMessage(Component.empty().append(prefix).append(nameLine))
        pick.hint(GuiLang.TOOLTIP_SELECT_GROUP)
        pick.active(!busy)
        pick.onPress {
            selectedGroupId = group.id
            rebuildGroups(state, busy)
            updateJoinButton(state, busy)
        }

        return row
    }

    private fun groupListLabel(group: GroupSnapshot): Component {
        val prefix = if (group.passwordProtected) {
            symbol("⛨ ", ChatFormatting.GREEN)
        } else {
            Component.empty()
        }
        return Component.empty()
            .append(prefix)
            .append(
                Component.translatable(
                    GuiLang.LIST_ROW,
                    group.name,
                    group.onlinePlayers.toString(),
                ),
            )
    }

    private fun selectedGroup(state: GuiClientState): GroupSnapshot? =
        selectedGroupId?.let { id -> state.browse?.groups?.find { it.id == id } }

    private fun isCurrentGroup(group: GroupSnapshot): Boolean =
        state.current?.id == group.id || group.isCurrent

    private fun updateJoinButton(state: GuiClientState, busy: Boolean) {
        if (!::joinButton.isInitialized) return
        val selected = selectedGroup(state)
        when {
            selected == null -> {
                joinButton.active(false)
                joinButton.setMessage(Component.translatable(GuiLang.BUTTON_JOIN))
                joinButton.hint(GuiLang.TOOLTIP_JOIN)
            }
            isCurrentGroup(selected) -> {
                joinButton.active(!busy)
                joinButton.setMessage(Component.translatable(GuiLang.BUTTON_GO))
                joinButton.hint(GuiLang.TOOLTIP_GO)
            }
            else -> {
                joinButton.active(!busy)
                joinButton.setMessage(Component.translatable(GuiLang.BUTTON_JOIN))
                joinButton.hint(GuiLang.TOOLTIP_JOIN)
            }
        }
    }

    private fun rebuildMembers(state: GuiClientState, busy: Boolean) {
        membersList.clearChildren()
        val current = state.current
        if (current == null) {
            membersList.child(expandEmpty(GuiLang.STATUS_MEMBERS_EMPTY))
            return
        }

        val members = current.members.sortedWith(
            compareByDescending<GroupMember> { it.isOwner }.thenBy { it.name.lowercase() },
        )
        val banned = if (current.canBan) {
            current.bannedPlayers.sortedBy { it.name.lowercase() }
        } else {
            emptyList()
        }

        if (members.isEmpty() && banned.isEmpty()) {
            membersList.child(expandEmpty(GuiLang.STATUS_MEMBERS_EMPTY))
            return
        }

        val selfUuid = minecraft?.player?.uuid
        for (member in members) {
            membersList.child(memberRow(member, current, busy, selfUuid))
        }
        for (player in banned) {
            membersList.child(bannedRow(player, busy))
        }
    }

    private fun memberRow(
        member: GroupMember,
        current: GroupSnapshot,
        busy: Boolean,
        selfUuid: UUID?,
    ): FlowLayout {
        val row = model.expandTemplate(
            FlowLayout::class.java,
            "member-row",
            mapOf("name" to member.name),
        )
        prependHead(row, member.uuid)

        val name = if (member.isOwner) {
            Component.empty()
                .append(symbol("🗡 ", ChatFormatting.GREEN))
                .append(Component.literal(member.name))
        } else {
            Component.literal(member.name)
        }
        row.childById(LabelComponent::class.java, "member-name").apply {
            text(name)
            maxWidth((membersList.width() - 56 - HEAD_SIZE).coerceAtLeast(48))
        }

        val isSelf = selfUuid != null && member.uuid == selfUuid
        val kick = row.childById(ButtonComponent::class.java, "kick-btn")
        val ban = row.childById(ButtonComponent::class.java, "ban-btn")
        kick.setMessage(symbol("-", ChatFormatting.YELLOW))
        ban.setMessage(symbol("×", ChatFormatting.RED))

        when {
            // Own row: yellow "-" leaves the group (replaces bottom Leave)
            isSelf -> {
                kick.hint(GuiLang.TOOLTIP_LEAVE)
                kick.active(!busy)
                kick.onPress { showConfirm(ConfirmAction.Leave) }
                row.removeChild(ban)
            }
            else -> {
                if (current.canKick) {
                    kick.hint(GuiLang.TOOLTIP_KICK)
                    kick.active(!busy)
                    kick.onPress { showConfirm(ConfirmAction.Kick(member.name)) }
                } else {
                    row.removeChild(kick)
                }
                if (current.canBan) {
                    ban.hint(GuiLang.TOOLTIP_BAN)
                    ban.active(!busy)
                    ban.onPress { showConfirm(ConfirmAction.Ban(member.name)) }
                } else {
                    row.removeChild(ban)
                }
            }
        }
        return row
    }

    private fun bannedRow(player: PlayerRef, busy: Boolean): FlowLayout {
        val row = model.expandTemplate(
            FlowLayout::class.java,
            "banned-row",
            mapOf("name" to player.name),
        )
        prependHead(row, player.uuid)

        row.childById(LabelComponent::class.java, "banned-name").apply {
            text(
                Component.empty()
                    .append(symbol("🔥 ", ChatFormatting.RED))
                    .append(Component.literal(player.name)),
            )
            maxWidth((membersList.width() - 36 - HEAD_SIZE).coerceAtLeast(48))
        }

        val unban = row.childById(ButtonComponent::class.java, "unban-btn")
        unban.setMessage(symbol("+", ChatFormatting.GREEN))
        unban.hint(GuiLang.TOOLTIP_UNBAN)
        unban.active(!busy)
        unban.onPress { controller.unban(player.name) }
        return row
    }

    private fun rebuildInviteList() {
        inviteList.clearChildren()
        val players = clientOnlinePlayers()
        if (players.isEmpty()) {
            inviteList.child(expandEmpty(GuiLang.STATUS_INVITE_EMPTY))
            confirmInviteButton.active(false)
            return
        }

        val busy = state.pendingAction != null
        for (player in players) {
            inviteList.child(inviteRow(player, busy))
        }
        confirmInviteButton.active(!busy && selectedInviteName != null)
    }

    private fun clientOnlinePlayers(): List<PlayerInfo> {
        val connection = minecraft?.connection ?: return emptyList()
        val self = minecraft?.player?.uuid
        val memberIds = state.current?.members?.map { it.uuid }?.toSet().orEmpty()
        val filter = inviteFilter.trim()

        return connection.listedOnlinePlayers
            .asSequence()
            .filter { info ->
                val id = info.profile.id
                id != self && id !in memberIds
            }
            .filter { info ->
                filter.isEmpty() || info.profile.name.contains(filter, ignoreCase = true)
            }
            .sortedBy { it.profile.name.lowercase() }
            .take(MAX_INVITE_VISIBLE)
            .toList()
    }

    private fun inviteRow(player: PlayerInfo, busy: Boolean): FlowLayout {
        val name = player.profile.name
        val uuid = player.profile.id
        val selected = selectedInviteName.equals(name, ignoreCase = true)

        val row = model.expandTemplate(
            FlowLayout::class.java,
            "invite-row",
            mapOf("name" to name),
        )
        prependHead(row, uuid)

        val button = row.childById(ButtonComponent::class.java, "invite-pick-btn")
        val prefix = if (selected) Component.literal("> ") else Component.empty()
        button.setMessage(
            Component.empty()
                .append(prefix)
                .append(Component.literal(name)),
        )
        button.active(!busy)
        button.onPress {
            selectedInviteName = name
            rebuildInviteList()
        }
        return row
    }

    private fun prependHead(row: FlowLayout, uuid: UUID) {
        // PlayerFaceWidget is 26.2-only.
    }

    private fun expandEmpty(key: String): LabelComponent =
        model.expandTemplate(
            LabelComponent::class.java,
            "empty-label",
            mapOf("key" to key),
        )

    private fun syncLabelWidths() {
        if (!::mainStatus.isInitialized) return
        val panelW = if (::viewsHost.isInitialized && viewsHost.width() > 0) {
            viewsHost.width()
        } else {
            PANEL_WIDTH
        }
        val w = (panelW - 24).coerceAtLeast(80)
        mainStatus.maxWidth(w)
        createStatus.maxWidth(w)
        settingsStatus.maxWidth(w)
        transferStatus.maxWidth(w)
        confirmMessage.maxWidth(w)
        confirmTitle.maxWidth(w)
        groupSubtitle.maxWidth(w)
        groupStatus.maxWidth(w)
        inviteStatus.maxWidth(w)
    }

    private fun ButtonComponent.hint(key: String) {
        setTooltip(Tooltip.create(Component.translatable(key)))
    }


    private fun symbol(text: String, color: ChatFormatting): Component =
        Component.literal(text).withStyle(color)

    private fun statusComponent(status: StatusText): Component =
        Component.translatable(status.translationKey, *status.args.toTypedArray())

    companion object {
        private const val MOD_ID = "pv-addon-groups-gui"
        private const val MODEL_PATH = "groups_gui"
        private const val MAX_INVITE_VISIBLE = 64
        /** 3× default 8px text object sprite. */
        private const val HEAD_SIZE = 24
        /** Compact heads in group browse preview strip. */
        private const val PREVIEW_HEAD_SIZE = 20
        /** Fixed panel size in scaled pixels (GUI scale applied by owo-ui). */
        private const val PANEL_WIDTH = 320
        private const val PANEL_HEIGHT = 280
    }
}
