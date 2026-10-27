package su.plo.voice.groups.gui.ui

import su.plo.lib.mod.client.gui.components.Button
import su.plo.lib.mod.client.gui.TooltipData
import su.plo.lib.mod.client.gui.components.TextFieldWidget
import su.plo.lib.mod.client.gui.screen.GuiScreen
import su.plo.lib.mod.client.gui.screen.ScreenWrapper
import su.plo.lib.mod.client.render.RenderUtil
import su.plo.lib.mod.client.render.gui.GuiRenderContext
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.chat.style.McTextStyle
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon
import su.plo.voice.groups.gui.client.GroupsGuiController
import su.plo.voice.groups.gui.client.GuiClientState
import su.plo.voice.groups.gui.client.GuiStateListener
import su.plo.voice.groups.gui.protocol.GroupSnapshot
import su.plo.voice.groups.gui.protocol.GroupMember
import su.plo.voice.groups.gui.protocol.GuiLang
import su.plo.voice.groups.gui.protocol.GuiMessage
import su.plo.voice.groups.gui.protocol.ProtocolConstants
import su.plo.voice.groups.gui.protocol.PlayerRef
import java.awt.Color
import java.util.UUID

/** Compact Plasmo widgets with an independently scrolling content area. */
class GroupsGuiScreen(private val controller: GroupsGuiController) : GuiScreen(), GuiStateListener {
    private enum class View { MAIN, JOIN, CREATE, GROUP, MANAGE, INVITE, SETTINGS, TRANSFER, CONFIRM }

    private var state = controller.state
    private var view = if (state.current == null) View.MAIN else View.GROUP
    private var browsing = false
    private var subscribed = false
    private var dirty = true
    private var selectedGroup: UUID? = null
    private var selectedPlayer: String? = null
    private var persistent = false
    private var advanced = false
    private var removePassword = false
    private var clearPermissions = false
    private var confirmText = McTextComponent.empty()
    private var confirmTitle = GuiLang.SCREEN_TITLE
    private var confirmButton = GuiLang.BUTTON_CONFIRM_YES
    private var confirmReturn = View.GROUP
    private var confirmAction: (() -> Unit)? = null
    private val drafts = mutableMapOf<String, String>()
    private val fields = mutableMapOf<String, TextFieldWidget>()
    private val scrollPositions = mutableMapOf<View, Double>()
    private var list: GroupsGuiList? = null
    private var panel = GroupsGuiPanelLayout.fit(320, 240, 280)
    private var bodyTop = 0
    private var bodyBottom = 0
    private var playerConfirmButton: Button? = null
    private var tooltip: TooltipData? = null
    private var refreshTicks = 0
    private var passwordsVisible = false
    private var noticeLines = emptyList<String>()
    private var noticeY = 0
    private val busy get() = state.pendingAction != null
    private val narrowBrowse get() = panel.width < 270

    override fun getTitle(): McTextComponent = tr(GuiLang.SCREEN_TITLE)

    override fun init() {
        if (!subscribed) {
            subscribed = true
            controller.addListener(this)
        }
        rebuild()
    }

    override fun removed() {
        controller.removeListener(this)
        subscribed = false
        focused = null
    }

    override fun tick() {
        fields.values.forEach { it.tick() }
        if (++refreshTicks >= 40 && !busy && state.connected) {
            refreshTicks = 0
            if (view == View.MAIN && state.canBrowse) controller.browse(state.browse?.page ?: 1)
            else controller.refreshInfo()
        }
    }

    override fun onGuiStateChanged(state: GuiClientState) {
        val groupChanged = state.current?.id != this.state.current?.id
        if (this.state.savingSettings && !state.savingSettings && state.status?.translationKey == GuiLang.SUCCESS_SETTINGS) {
            drafts["password"] = ""
            removePassword = false
            clearPermissions = false
        }
        val success = this.state.pendingAction != null && state.pendingAction == null && state.status?.translationKey in setOf(
            GuiLang.SUCCESS_INVITE, GuiLang.SUCCESS_TRANSFER,
        )
        this.state = state
        when {
            state.current == null && view !in setOf(View.CREATE, View.MAIN, View.JOIN) -> show(View.MAIN)
            state.current != null && groupChanged && !browsing && view in setOf(View.MAIN, View.CREATE, View.JOIN) -> show(View.GROUP)
            success && view in setOf(View.INVITE, View.TRANSFER) -> show(View.GROUP)
        }
        dirty = true
    }

    override fun render(context: GuiRenderContext, mouseX: Int, mouseY: Int, delta: Float) {
        if (dirty) rebuild()
        tooltip = null
        context.fill(0, 0, width, height, Color(0, 0, 0, 100))
        context.fill(panel.x - 2, panel.y - 2, panel.right + 2, panel.bottom + 2, Color(10, 10, 12))
        context.fill(panel.x - 1, panel.y - 1, panel.right + 1, panel.bottom + 1, Color(65, 65, 70))
        context.fill(panel.x, panel.y, panel.right, panel.bottom, Color(39, 39, 43))
        context.fill(panel.contentX - 1, bodyTop - 1, panel.right - 9, bodyBottom + 1, Color(150, 150, 155))
        context.fill(panel.contentX - 1, bodyTop - 1, panel.right - 10, bodyBottom, Color(12, 12, 14))
        context.fill(panel.contentX + 1, bodyTop + 1, panel.right - 11, bodyBottom - 1, Color(55, 55, 57))
        val title = if (view == View.GROUP) McTextComponent.literal(state.current?.name.orEmpty()) else tr(titleKey())
        context.drawCenteredString(
            RenderUtil.stringToWidth(RenderUtil.getFormattedString(title), (panel.width - 58).coerceAtLeast(1)),
            width / 2, panel.y + 10, Color.WHITE,
        )
        if (view == View.GROUP) context.drawCenteredString(tr(GuiLang.STATUS_IN_GROUP,
            state.current?.onlinePlayers?.toString() ?: "0"), width / 2, panel.y + 24, Color(155, 155, 160))
        if (view == View.MAIN && state.browse?.groups?.isNotEmpty() == true) context.drawCenteredString(tr(GuiLang.STATUS_PAGE,
            (state.browse?.page ?: 1).toString(), (state.browse?.totalPages ?: 1).toString()), width / 2, panel.bottom - 18, Color.LIGHT_GRAY)
        val noticeColor = if (state.status?.translationKey?.startsWith("${GuiLang.PREFIX}.success.") == true) Color(150, 220, 165) else Color(240, 185, 115)
        noticeLines.forEachIndexed { index, line -> context.drawString(line, panel.contentX, noticeY + index * 10, noticeColor) }
        if (view == View.INVITE || view == View.TRANSFER) {
            val caption = selectedPlayer?.let { tr(GuiLang.STATUS_SELECTED_PLAYER, it) } ?: tr(GuiLang.HINT_INVITE)
            context.drawString(RenderUtil.stringToWidth(RenderUtil.getFormattedString(caption), panel.contentWidth),
                panel.contentX, panel.bottom - 48, Color.LIGHT_GRAY)
        }
        super.render(context, mouseX, mouseY, delta)
        tooltip?.let { minecraftScreen.renderTooltipWrapped(context, it) }
    }

    private fun rebuild() {
        val focusedName = fields.entries.firstOrNull { it.value.isFocused }?.key
        list?.let { scrollPositions[view] = it.offset }
        focused = null
        clearWidgets()
        fields.clear()
        panel = GroupsGuiPanelLayout.fit(width, height, when (view) { View.CONFIRM -> 180; View.JOIN -> 200; View.CREATE -> 240; else -> 280 })
        bodyTop = panel.y + when (view) {
            View.GROUP -> 42
            View.MAIN -> if (narrowBrowse) 80 else 56
            View.INVITE, View.TRANSFER -> 62
            else -> 34
        }
        noticeY = bodyTop
        noticeLines = state.status?.takeUnless { view == View.JOIN && it.translationKey == GuiLang.ERROR_WRONG_PASSWORD }
            ?.let { PlasmoText.wrap(RenderUtil.getFormattedString(tr(it.translationKey, *it.args.toTypedArray())), panel.contentWidth).take(2) }.orEmpty()
        bodyTop += if (noticeLines.isEmpty()) 0 else noticeLines.size * 10 + 4
        bodyBottom = when (view) {
            View.GROUP -> panel.toolbarY - 9
            View.MAIN -> panel.bottom - 30
            View.INVITE, View.TRANSFER -> panel.bottom - 54
            else -> panel.bottom - 40
        }.coerceAtLeast(bodyTop + 10)
        val viewport = GroupsGuiList(this, (panel.contentWidth - 20).coerceAtLeast(1), bodyTop + 3, bodyBottom - 3)
        list = viewport
        playerConfirmButton = null
        addRenderWidget(makeButton(McTextComponent.literal("×"), true, 16, tr(GuiLang.BUTTON_CLOSE)) {
            ScreenWrapper.openScreen(null)
        }.apply { x = panel.right - 23; y = panel.y + 5; height = 16 })
        addRenderWidget(viewport)
        when (view) {
            View.MAIN -> browse()
            View.JOIN -> joinGroup()
            View.CREATE -> create()
            View.GROUP -> group()
            View.MANAGE -> manage()
            View.INVITE -> players(false)
            View.TRANSFER -> players(true)
            View.SETTINGS -> settings()
            View.CONFIRM -> {
                label(confirmText)
                footer(confirmButton, !busy && confirmAction != null,
                    if (confirmButton == GuiLang.BUTTON_LEAVE) GuiLang.BUTTON_STAY else GuiLang.BUTTON_CANCEL) {
                    val pending = confirmAction
                    confirmAction = null
                    show(View.GROUP)
                    pending?.invoke()
                }
            }
        }
        viewport.offset = scrollPositions[view] ?: 0.0
        focusedName?.let { name -> fields[name]?.let {
            focused = if (viewport.focusField(it)) viewport else it
        } }
        dirty = false
    }

    private fun browse() {
        val page = state.browse
        val controlsY = panel.y + if (narrowBrowse) 54 else 30
        val createWidth = if (narrowBrowse) (panel.contentWidth - 6) / 2 else 54
        val refreshWidth = if (narrowBrowse) createWidth else 60
        rootButton(tr(GuiLang.BUTTON_CREATE), !busy && state.canCreate,
            panel.right - 10 - refreshWidth - 4 - createWidth, controlsY, createWidth) { openCreate() }
        rootButton(tr(GuiLang.BUTTON_REFRESH), !busy,
            panel.right - 10 - refreshWidth, controlsY, refreshWidth) { controller.onOpen() }
        rootField("groupSearch", GuiLang.HINT_GROUP_SEARCH, panel.y + 30, 64,
            fieldWidth = if (narrowBrowse) panel.contentWidth else panel.contentWidth - createWidth - refreshWidth - 8) {
            scrollPositions[View.MAIN] = 0.0; list?.offset = 0.0; dirty = true
        }
        val filter = drafts["groupSearch"].orEmpty().trim()
        val entries = page?.groups.orEmpty().filter { it.name.contains(filter, ignoreCase = true) }
            .sortedByDescending { it.id == state.current?.id }
        if (page == null || page.groups.isEmpty()) {
            label(tr(if (state.loading) GuiLang.STATUS_LOADING else GuiLang.STATUS_EMPTY))
            if (!state.loading) label(tr(GuiLang.HINT_EMPTY_GROUPS), Color.LIGHT_GRAY)
        } else if (entries.isEmpty()) label(tr(GuiLang.STATUS_NO_MATCHES))
        entries.forEach { entry ->
            val current = entry.isCurrent || entry.id == state.current?.id
            list!!.addGroup(entry, makeButton(tr(if (current) GuiLang.BUTTON_GO else GuiLang.BUTTON_JOIN),
                !busy && state.canBrowse, 54) {
                browsing = false
                if (current) show(View.GROUP)
                else if (entry.passwordProtected) {
                    selectedGroup = entry.id
                    drafts["joinPassword"] = ""; passwordsVisible = false
                    controller.dismissStatus()
                    show(View.JOIN)
                } else controller.join(entry, null)
            }, current)
        }
        page?.takeIf { it.totalPages > 1 }?.let {
            rootButton(tr(GuiLang.BUTTON_PREV), !busy && it.page > 1, panel.contentX, panel.bottom - 25, 20) { controller.browse(it.page - 1) }
            rootButton(tr(GuiLang.BUTTON_NEXT), !busy && it.page < it.totalPages, panel.right - 30, panel.bottom - 25, 20) { controller.browse(it.page + 1) }
        }
    }

    private fun joinGroup() {
        val selected = state.browse?.groups?.find { it.id == selectedGroup }
        if (selected == null) {
            label(tr(GuiLang.ERROR_GROUP_NOT_FOUND))
            footer(GuiLang.BUTTON_BACK, !busy) { browsing = true; show(View.MAIN) }
            return
        }
        label(McTextComponent.literal(selected.name))
        if (state.status?.translationKey == GuiLang.ERROR_WRONG_PASSWORD) label(tr(GuiLang.HINT_PASSWORD_RETRY), Color(245, 170, 120))
        field("joinPassword", GuiLang.FIELD_PASSWORD, GuiLang.HINT_PASSWORD, 64) {
            if (state.status?.translationKey == GuiLang.ERROR_WRONG_PASSWORD) controller.dismissStatus()
        }
        footer(GuiLang.BUTTON_JOIN, !busy, GuiLang.BUTTON_CANCEL) { browsing = false; controller.join(selected, drafts["joinPassword"]) }
    }

    private fun openCreate() {
        controller.dismissStatus()
        drafts.clear(); persistent = false; advanced = false; passwordsVisible = false; show(View.CREATE)
    }

    private fun create() {
        field("name", GuiLang.FIELD_NAME, GuiLang.HINT_NAME)
        field("password", GuiLang.FIELD_PASSWORD, GuiLang.HINT_PASSWORD_CREATE, 64)
        if (state.canCreatePermissions || state.canCreatePersistent) {
            action(if (advanced) GuiLang.BUTTON_ADVANCED_HIDE else GuiLang.BUTTON_ADVANCED, !busy) {
                advanced = !advanced; dirty = true
            }
            if (advanced) {
                if (state.canCreatePermissions) {
                    label(tr(GuiLang.HINT_PERMISSIONS_HELP))
                    field("permissions", GuiLang.FIELD_PERMISSIONS, GuiLang.HINT_PERMISSIONS, 1024)
                }
                if (state.canCreatePersistent) {
                    label(tr(GuiLang.HINT_PERSISTENT))
                    action(if (persistent) GuiLang.BUTTON_PERSISTENT_ON else GuiLang.BUTTON_PERSISTENT_OFF, !busy) {
                        persistent = !persistent; dirty = true
                    }
                }
            }
        }
        footer(GuiLang.BUTTON_CREATE, !busy && state.canCreate) {
            browsing = false
            controller.create(drafts["name"], drafts["password"], persistent && state.canCreatePersistent,
                if (state.canCreatePermissions) permissions() else emptyList())
        }
    }

    private fun group() {
        val current = state.current ?: return
        val buttonWidth = ((panel.contentWidth - 6) / 2).coerceAtLeast(1)
        rootButton(tr(GuiLang.BUTTON_CONFIRM_INVITE), !busy, panel.contentX, panel.toolbarY, buttonWidth) {
            selectedPlayer = null; drafts["filter"] = ""; show(View.INVITE); controller.refreshInviteCandidates()
        }
        rootButton(tr(GuiLang.BUTTON_MANAGE), !busy, panel.contentX + buttonWidth + 6, panel.toolbarY, buttonWidth) {
            show(View.MANAGE)
        }
        rootButton(tr(GuiLang.BUTTON_ALL_GROUPS), !busy, panel.contentX, panel.toolbarY + 22, buttonWidth) {
            browsing = true; show(View.MAIN); controller.browse(1)
        }
        rootButton(tr(GuiLang.TOOLTIP_LEAVE), !busy, panel.contentX + buttonWidth + 6, panel.toolbarY + 22, buttonWidth) {
            confirm(GuiLang.CONFIRM_LEAVE) { browsing = false; controller.leave() }
        }
        val self = localPlayerId()
        current.members.sortedWith(compareByDescending<GroupMember> { it.isOwner }.thenBy { it.name.lowercase() }).forEach { member ->
            val buttons = mutableListOf<Button>()
            if (member.uuid == self) {
                buttons += makeButton(tr(GuiLang.BUTTON_LEAVE), !busy, 44, tr(GuiLang.TOOLTIP_LEAVE)) {
                    confirm(GuiLang.CONFIRM_LEAVE) { browsing = false; controller.leave() }
                }
            } else if (!member.isOwner) {
                if (current.canKick) buttons += makeButton(tr(GuiLang.BUTTON_KICK), !busy, 34, tr(GuiLang.TOOLTIP_KICK)) {
                    confirm(GuiLang.CONFIRM_KICK, member.name) { controller.kick(member.name) }
                }
                if (current.canBan) buttons += makeButton(tr(GuiLang.BUTTON_BAN), !busy, 30, tr(GuiLang.TOOLTIP_BAN)) {
                    confirm(GuiLang.CONFIRM_BAN, member.name) { controller.ban(member.name) }
                }
            }
            val name = McTextComponent.empty().append(
                if (member.isOwner) symbol("★ ", McTextStyle.GREEN) else McTextComponent.empty(), McTextComponent.literal(member.name))
            list!!.addPlayer(member.uuid, member.name, name, buttons)
        }
        if (current.canBan && current.bannedPlayers.isNotEmpty()) {
            label(tr(GuiLang.SECTION_BANNED))
            current.bannedPlayers.forEach { player ->
                list!!.addPlayer(player.uuid, player.name, McTextComponent.literal(player.name), listOf(
                    makeButton(tr(GuiLang.BUTTON_UNBAN), !busy, 48) { controller.unban(player.name) }))
            }
        }
    }

    private fun manage() {
        val current = state.current ?: return
        action(GuiLang.TITLE_SETTINGS, !busy && canEdit(current)) {
            drafts["name"] = current.name
            drafts["password"] = ""
            drafts["permissions"] = current.permissions.joinToString(", ")
            persistent = current.persistent
            removePassword = false
            clearPermissions = false
            passwordsVisible = false
            show(View.SETTINGS)
        }
        if (!canEdit(current)) label(tr(GuiLang.HINT_DENIED), Color.LIGHT_GRAY)
        action(GuiLang.BUTTON_TRANSFER, !busy && current.canTransfer) {
            selectedPlayer = null; drafts["filter"] = ""; show(View.TRANSFER)
        }
        if (!current.canTransfer) label(tr(GuiLang.HINT_DENIED), Color.LIGHT_GRAY)
        if (current.isOwner) action(GuiLang.BUTTON_DELETE, !busy) {
            confirm(GuiLang.CONFIRM_DELETE) { controller.delete() }
        }
        rootButton(tr(GuiLang.BUTTON_BACK), !busy, panel.contentX, panel.bottom - 30, panel.contentWidth) { show(View.GROUP) }
    }

    private fun players(transfer: Boolean) {
        rootField("filter", GuiLang.HINT_INVITE, panel.y + 32, 64) { refreshPlayers(transfer) }
        playerConfirmButton = footer(if (transfer) GuiLang.BUTTON_CONFIRM_TRANSFER else GuiLang.BUTTON_CONFIRM_INVITE,
            !busy && selectedPlayer != null) {
            selectedPlayer?.let { name ->
                if (transfer) confirm(GuiLang.CONFIRM_TRANSFER, name) { controller.transfer(name) }
                else controller.invite(name)
            }
        }
        refreshPlayers(transfer)
    }

    private fun refreshPlayers(transfer: Boolean) {
        val offset = list!!.offset
        list!!.clearEntries()
        val current = state.current
        val memberIds = current?.members?.map { it.uuid }?.toSet().orEmpty()
        val filter = drafts["filter"].orEmpty().trim()
        val candidates = if (transfer) {
            current?.members.orEmpty().filter { !it.isOwner && it.uuid != localPlayerId() }.map { PlayerRef(it.uuid, it.name) }
        } else {
            state.inviteCandidates.filter { it.uuid !in memberIds && it.uuid != localPlayerId() }
        }
        val names = candidates.filter { it.name.contains(filter, ignoreCase = true) }.sortedBy { it.name.lowercase() }
        if (names.none { it.name == selectedPlayer }) selectedPlayer = null
        if (names.isEmpty()) label(tr(if (transfer) GuiLang.STATUS_TRANSFER_EMPTY else GuiLang.STATUS_INVITE_EMPTY))
        names.forEach { player ->
            val name = (if (selectedPlayer == player.name) "> " else "") + player.name
            list!!.addControl(makeButton(McTextComponent.literal(name), !busy, (list!!.containerWidth - 24).coerceAtLeast(1),
                selected = selectedPlayer == player.name) {
                selectedPlayer = player.name; refreshPlayers(transfer)
            }, player.uuid, player.name)
        }
        playerConfirmButton?.isActive = !busy && selectedPlayer != null
        list!!.offset = offset
    }

    private fun settings() {
        val current = state.current ?: return
        label(tr(if (current.passwordProtected) GuiLang.STATUS_PASSWORD_ON else GuiLang.STATUS_PASSWORD_OFF), Color.LIGHT_GRAY)
        field("name", GuiLang.FIELD_NAME, GuiLang.HINT_NAME, editable = current.canSetName)
        field("password", GuiLang.FIELD_PASSWORD, GuiLang.HINT_PASSWORD_SET, 64, current.canSetPassword) {
            if (removePassword) { removePassword = false; dirty = true }
        }
        field("permissions", GuiLang.FIELD_PERMISSIONS, GuiLang.HINT_PERMISSIONS, 1024, current.canSetPermissions) {
            if (clearPermissions) { clearPermissions = false; dirty = true }
        }
        action(if (persistent) GuiLang.BUTTON_PERSISTENT_ON else GuiLang.BUTTON_PERSISTENT_OFF,
            !busy && current.canSetPersistent) { persistent = !persistent; dirty = true }
        action(GuiLang.BUTTON_REMOVE_PASSWORD, !busy && current.canUnsetPassword) {
            drafts["password"] = ""; removePassword = true; dirty = true
        }
        action(GuiLang.BUTTON_CLEAR_PERMISSIONS, !busy && current.canUnsetPermissions) {
            drafts["permissions"] = ""; clearPermissions = true; dirty = true
        }
        if (removePassword) label(tr(GuiLang.STATUS_PASSWORD_REMOVE))
        if (clearPermissions) label(tr(GuiLang.STATUS_PERMISSIONS_CLEAR))
        footer(GuiLang.BUTTON_SAVE, !busy && canEdit(current), GuiLang.BUTTON_CANCEL) {
            val name = drafts["name"].orEmpty().trim()
            val password = drafts["password"].orEmpty()
            val permissions = permissions()
            val changes = mutableListOf<GuiMessage.C2S>()
            if (current.canSetName && name.isNotEmpty() && name != current.name) changes += GuiMessage.C2S.SetName(name)
            if (removePassword && current.canUnsetPassword) changes += GuiMessage.C2S.SetPassword(null)
            else if (current.canSetPassword && password.isNotEmpty()) changes += GuiMessage.C2S.SetPassword(password)
            if (current.canSetPersistent && persistent != current.persistent) changes += GuiMessage.C2S.SetPersistent(persistent)
            if (clearPermissions && current.canUnsetPermissions) changes += GuiMessage.C2S.SetPermissions(null)
            else if (current.canSetPermissions && permissions.toSet() != current.permissions.toSet()) changes += GuiMessage.C2S.SetPermissions(permissions)
            controller.saveSettings(changes)
        }
    }

    private fun canEdit(group: GroupSnapshot) = group.canSetName || group.canSetPassword || group.canUnsetPassword ||
        group.canSetPersistent || group.canSetPermissions || group.canUnsetPermissions

    private fun confirm(key: String, name: String? = null, action: () -> Unit) {
        confirmReturn = view
        confirmTitle = when (key) {
            GuiLang.CONFIRM_DELETE -> GuiLang.TITLE_CONFIRM_DELETE
            GuiLang.CONFIRM_LEAVE -> GuiLang.TITLE_CONFIRM_LEAVE
            GuiLang.CONFIRM_TRANSFER -> GuiLang.TITLE_CONFIRM_TRANSFER
            GuiLang.CONFIRM_KICK -> GuiLang.TITLE_CONFIRM_KICK
            else -> GuiLang.TITLE_CONFIRM_BAN
        }
        confirmButton = when (key) {
            GuiLang.CONFIRM_DELETE -> GuiLang.BUTTON_DELETE
            GuiLang.CONFIRM_LEAVE -> GuiLang.BUTTON_LEAVE
            GuiLang.CONFIRM_TRANSFER -> GuiLang.BUTTON_CONFIRM_TRANSFER
            GuiLang.CONFIRM_KICK -> GuiLang.BUTTON_CONFIRM_KICK
            else -> GuiLang.BUTTON_CONFIRM_BAN
        }
        val groupName = state.current?.name.orEmpty()
        confirmText = when (key) {
            GuiLang.CONFIRM_DELETE, GuiLang.CONFIRM_LEAVE -> tr(key, groupName)
            GuiLang.CONFIRM_TRANSFER -> tr(key, name.orEmpty(), groupName)
            else -> tr(key, name.orEmpty())
        }
        confirmAction = action
        show(View.CONFIRM)
    }

    private fun show(next: View) {
        list?.let { scrollPositions[view] = it.offset }
        list = null
        view = next
        dirty = true
    }

    private fun field(id: String, title: String, hint: String, maxLength: Int = ProtocolConstants.MAX_GROUP_NAME_LENGTH,
                      editable: Boolean = true, changed: () -> Unit = {}) {
        label(tr(title), Color(170, 180, 195))
        val widget = HintTextField(0, 0, list!!.containerWidth, tr(hint))
        widget.maxLength = maxLength
        widget.value = drafts[id].orEmpty()
        widget.isEditable = editable && !busy
        if (id == "password" || id == "joinPassword") passwordFormatter(widget)
        widget.setResponder { value -> drafts[id] = value; changed() }
        fields[id] = widget
        list!!.addControl(widget)
        if (id == "password" || id == "joinPassword") action(if (passwordsVisible) GuiLang.BUTTON_HIDE_PASSWORD else GuiLang.BUTTON_SHOW_PASSWORD, !busy) {
            passwordsVisible = !passwordsVisible; dirty = true
        }
    }

    private fun label(text: McTextComponent, color: Color = Color.WHITE) = list!!.addText(text, color)
    private fun action(key: String, enabled: Boolean, run: () -> Unit) = action(tr(key), enabled, run)
    private fun action(text: McTextComponent, enabled: Boolean, run: () -> Unit) {
        val hint = if (!enabled && !busy) McTextComponent.empty().append(text, McTextComponent.literal("\n"), tr(GuiLang.HINT_DENIED)) else text
        list!!.addControl(makeButton(text, enabled, list!!.containerWidth, hint, run = run))
    }

    private fun makeButton(text: McTextComponent, enabled: Boolean, width: Int,
                           hint: McTextComponent = text, selected: Boolean = false, run: () -> Unit): Button {
        val message = McTextComponent.literal(RenderUtil.stringToWidth(RenderUtil.getFormattedString(text), (width - 8).coerceAtLeast(1)))
        return object : Button(0, 0, width, 20, message, { run() }, { _, x, y ->
            tooltip = TooltipData(hint, x, y)
        }) {
            override fun renderButton(context: GuiRenderContext, mouseX: Int, mouseY: Int, delta: Float) {
                super.renderButton(context, mouseX, mouseY, delta)
                if (selected) {
                    context.fill(x, y, x + this.width, y + 1, Color(135, 220, 175))
                    context.fill(x, y + height - 1, x + this.width, y + height, Color(135, 220, 175))
                    context.fill(x, y, x + 2, y + height, Color(135, 220, 175))
                }
            }
        }.apply { isActive = enabled }
    }

    private fun rootButton(text: McTextComponent, enabled: Boolean, x: Int, y: Int, width: Int,
                           hint: McTextComponent = text, run: () -> Unit): Button =
        addRenderWidget(makeButton(text, enabled, width, hint, run = run).apply { this.x = x; this.y = y })

    private fun rootField(id: String, hint: String, y: Int, maxLength: Int,
                          fieldWidth: Int = panel.contentWidth, password: Boolean = false, changed: () -> Unit = {}) {
        val widget = HintTextField(panel.contentX, y, fieldWidth, tr(hint))
        widget.maxLength = maxLength
        widget.value = drafts[id].orEmpty()
        widget.isEditable = !busy
        if (password) passwordFormatter(widget)
        widget.setResponder { value -> drafts[id] = value; changed() }
        fields[id] = widget
        addRenderWidget(widget)
    }

    private fun passwordFormatter(widget: TextFieldWidget) {
        widget.setFormatter { text, _ -> McTextComponent.literal(if (passwordsVisible) text else "*".repeat(text.length)) }
    }

    fun showHint(text: McTextComponent, x: Int, y: Int) { tooltip = TooltipData(text, x, y) }

    private fun footer(primary: String, enabled: Boolean, back: String = GuiLang.BUTTON_BACK, run: () -> Unit): Button {
        val buttonWidth = ((panel.contentWidth - 6) / 2).coerceIn(1, 130)
        rootButton(tr(back), !busy, width / 2 + 3, panel.bottom - 30, buttonWidth) { goBack() }
        return rootButton(tr(primary), enabled, width / 2 - buttonWidth - 3, panel.bottom - 30, buttonWidth, run = run)
    }

    private fun goBack() {
        when (view) {
            View.MAIN -> ScreenWrapper.openScreen(null)
            View.GROUP -> { browsing = true; show(View.MAIN) }
            View.CREATE, View.JOIN -> { controller.dismissStatus(); browsing = true; show(View.MAIN) }
            View.CONFIRM -> show(confirmReturn)
            else -> show(if (state.current == null) View.MAIN else View.GROUP)
        }
    }

    private class HintTextField(x: Int, y: Int, width: Int, val hint: McTextComponent) :
        TextFieldWidget(x, y, width, 20, McTextComponent.empty()) {
        override fun renderButton(context: GuiRenderContext, mouseX: Int, mouseY: Int, delta: Float) {
            super.renderButton(context, mouseX, mouseY, delta)
            if (value.isEmpty() && !isFocused) context.drawString(
                RenderUtil.stringToWidth(RenderUtil.getFormattedString(hint), (width - 10).coerceAtLeast(1)),
                x + 5, y + 6, Color(145, 145, 150))
        }
    }

    private fun symbol(text: String, style: McTextStyle): McTextComponent = McTextComponent.literal(text).withStyle(style)

    private fun permissions() = drafts["permissions"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private fun localPlayerId(): UUID? = GroupsGuiClientAddon.INSTANCE?.plasmoVoiceClient()?.serverConnection
        ?.orElse(null)?.localPlayer?.orElse(null)?.playerId

    private fun titleKey() = when (view) {
        View.MAIN -> GuiLang.TITLE_MAIN
        View.JOIN -> GuiLang.TITLE_JOIN
        View.CREATE -> GuiLang.TITLE_CREATE
        View.GROUP -> GuiLang.TITLE_GROUP
        View.INVITE -> GuiLang.TITLE_INVITE
        View.TRANSFER -> GuiLang.TITLE_TRANSFER
        View.SETTINGS -> GuiLang.TITLE_SETTINGS
        View.MANAGE -> GuiLang.BUTTON_MANAGE
        View.CONFIRM -> confirmTitle
    }

    private fun tr(key: String, vararg args: String): McTextComponent = McTextComponent.translatable(key, *args)
}
