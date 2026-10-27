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
import su.plo.voice.groups.gui.protocol.ProtocolConstants
import su.plo.voice.groups.gui.protocol.PlayerRef
import java.awt.Color
import java.util.UUID

/** Compact Plasmo widgets with an independently scrolling content area. */
class GroupsGuiScreen(private val controller: GroupsGuiController) : GuiScreen(), GuiStateListener {
    private enum class View { MAIN, CREATE, GROUP, INVITE, SETTINGS, TRANSFER, CONFIRM }

    private var state = controller.state
    private var view = if (state.current == null) View.MAIN else View.GROUP
    private var browsing = false
    private var subscribed = false
    private var dirty = true
    private var selectedGroup: UUID? = null
    private var selectedPlayer: String? = null
    private var persistent = false
    private var confirmText = McTextComponent.empty()
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
    private val busy get() = state.pendingAction != null

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
        val success = this.state.pendingAction != null && state.pendingAction == null && state.status?.translationKey in setOf(
            GuiLang.SUCCESS_INVITE, GuiLang.SUCCESS_TRANSFER,
        )
        this.state = state
        when {
            state.current == null && view !in setOf(View.CREATE, View.MAIN) -> show(View.MAIN)
            state.current != null && !browsing && view in setOf(View.MAIN, View.CREATE) -> show(View.GROUP)
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
        if (view == View.MAIN) context.drawCenteredString(tr(GuiLang.SECTION_GROUPS), width / 2, panel.y + 52, Color(155, 155, 160))
        super.render(context, mouseX, mouseY, delta)
        tooltip?.let { minecraftScreen.renderTooltipWrapped(context, it) }
    }

    private fun rebuild() {
        val focusedName = fields.entries.firstOrNull { it.value.isFocused }?.key
        list?.let { scrollPositions[view] = it.offset }
        focused = null
        clearWidgets()
        fields.clear()
        panel = GroupsGuiPanelLayout.fit(width, height, if (view == View.CREATE || view == View.CONFIRM) 240 else 280)
        bodyTop = panel.y + when (view) {
            View.GROUP -> 42
            View.MAIN -> 64
            View.INVITE, View.TRANSFER -> 62
            else -> 34
        }
        bodyBottom = when (view) {
            View.GROUP -> panel.toolbarY - 9
            View.MAIN -> panel.bottom - 70
            else -> panel.bottom - 40
        }.coerceAtLeast(bodyTop + 10)
        val viewport = GroupsGuiList(this, (panel.contentWidth - 20).coerceAtLeast(1), bodyTop + 3, bodyBottom - 3)
        list = viewport
        playerConfirmButton = null
        addRenderWidget(makeButton(McTextComponent.literal("×"), true, 16, tr(GuiLang.BUTTON_CLOSE)) {
            ScreenWrapper.openScreen(null)
        }.apply { x = panel.right - 23; y = panel.y + 5; height = 16 })
        addRenderWidget(viewport)
        state.status?.takeUnless { it.translationKey.startsWith("${GuiLang.PREFIX}.success.") }?.let {
            label(tr(it.translationKey, *it.args.toTypedArray()), Color(220, 170, 105))
        }
        when (view) {
            View.MAIN -> browse()
            View.CREATE -> create()
            View.GROUP -> group()
            View.INVITE -> players(false)
            View.TRANSFER -> players(true)
            View.SETTINGS -> settings()
            View.CONFIRM -> {
                label(confirmText)
                footer(GuiLang.BUTTON_CONFIRM_YES, !busy && confirmAction != null, GuiLang.BUTTON_CONFIRM_NO) {
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
        val headerWidth = ((panel.contentWidth - 6) / 2).coerceIn(1, 90)
        rootButton(tr(GuiLang.BUTTON_CREATE), !busy && state.canCreate,
            width / 2 - headerWidth - 3, panel.y + 28, headerWidth) {
            drafts.clear(); persistent = false; show(View.CREATE)
        }
        rootButton(tr(GuiLang.BUTTON_REFRESH), !busy, width / 2 + 3, panel.y + 28, headerWidth) { controller.onOpen() }
        val page = state.browse
        if (page == null || page.groups.isEmpty()) label(tr(if (state.loading) GuiLang.STATUS_LOADING else GuiLang.STATUS_EMPTY))
        if (page?.groups?.none { it.id == selectedGroup } == true) selectedGroup = null
        page?.groups?.forEach { entry ->
            val text = McTextComponent.empty().append(
                if (selectedGroup == entry.id) symbol("> ", McTextStyle.GREEN) else McTextComponent.empty(),
                if (entry.passwordProtected) symbol("⛨ ", McTextStyle.GREEN) else McTextComponent.empty(),
                tr(GuiLang.LIST_ROW, entry.name, entry.onlinePlayers.toString()),
            )
            list!!.addGroup(entry, makeButton(text, !busy && state.canBrowse, list!!.containerWidth) {
                if (selectedGroup != entry.id) drafts["joinPassword"] = ""
                selectedGroup = entry.id
                dirty = true
            })
        }
        val selected = page?.groups?.find { it.id == selectedGroup }
        rootField("joinPassword", GuiLang.HINT_PASSWORD, panel.bottom - 62, 64)
        val current = selected != null && (selected.isCurrent || selected.id == state.current?.id)
        rootButton(tr(if (current) GuiLang.BUTTON_GO else GuiLang.BUTTON_JOIN), !busy && selected != null,
            panel.contentX, panel.bottom - 36, panel.contentWidth) {
            if (current) { browsing = false; show(View.GROUP) }
            else selected?.let { browsing = false; controller.join(it, drafts["joinPassword"]) }
        }
        page?.takeIf { it.totalPages > 1 }?.let {
            rootButton(tr(GuiLang.BUTTON_PREV), !busy && it.page > 1, panel.contentX, panel.y + 44, 20,
                tr(GuiLang.STATUS_PAGE, it.page.toString(), it.totalPages.toString())) { selectedGroup = null; controller.browse(it.page - 1) }
            rootButton(tr(GuiLang.BUTTON_NEXT), !busy && it.page < it.totalPages, panel.right - 30, panel.y + 44, 20,
                tr(GuiLang.STATUS_PAGE, it.page.toString(), it.totalPages.toString())) { selectedGroup = null; controller.browse(it.page + 1) }
        }
    }

    private fun create() {
        field("name", GuiLang.FIELD_NAME, GuiLang.HINT_NAME)
        field("password", GuiLang.FIELD_PASSWORD, GuiLang.HINT_PASSWORD_CREATE, 64)
        if (state.canCreatePermissions) field("permissions", GuiLang.FIELD_PERMISSIONS, GuiLang.HINT_PERMISSIONS, 1024)
        action(if (persistent) GuiLang.BUTTON_PERSISTENT_ON else GuiLang.BUTTON_PERSISTENT_OFF,
            !busy && state.canCreatePersistent) { persistent = !persistent; dirty = true }
        footer(GuiLang.BUTTON_CREATE, !busy && state.canCreate) {
            browsing = false
            controller.create(drafts["name"], drafts["password"], persistent && state.canCreatePersistent,
                if (state.canCreatePermissions) permissions() else emptyList())
        }
    }

    private fun group() {
        val current = state.current ?: return
        rootButton(symbol("+", McTextStyle.GREEN), !busy, panel.contentX, panel.toolbarY, 20, tr(GuiLang.TOOLTIP_INVITE)) {
            selectedPlayer = null; drafts["filter"] = ""; show(View.INVITE); controller.refreshInviteCandidates()
        }
        rootButton(symbol("→", McTextStyle.GREEN), !busy && current.canTransfer,
            panel.contentX + 22, panel.toolbarY, 20, tr(GuiLang.TOOLTIP_TRANSFER)) {
            selectedPlayer = null; drafts["filter"] = ""; show(View.TRANSFER)
        }
        rootButton(symbol("✎", McTextStyle.YELLOW), !busy && canEdit(current),
            panel.contentX + 44, panel.toolbarY, 20, tr(GuiLang.TOOLTIP_SETTINGS)) {
            drafts["name"] = current.name
            drafts["password"] = ""
            drafts["permissions"] = current.permissions.joinToString(", ")
            persistent = current.persistent
            show(View.SETTINGS)
        }
        rootButton(symbol("×", McTextStyle.RED), !busy, panel.right - 30, panel.toolbarY, 20,
            tr(if (current.isOwner) GuiLang.TOOLTIP_DISBAND else GuiLang.TOOLTIP_LEAVE)) {
            if (current.isOwner) confirm(GuiLang.CONFIRM_DELETE) { controller.delete() }
            else confirm(GuiLang.CONFIRM_LEAVE) { browsing = false; controller.leave() }
        }
        val centerWidth = if (panel.toolbarRows == 1) (panel.contentWidth - 96).coerceAtLeast(1) else panel.contentWidth
        val allWidth = (RenderUtil.getTextWidth(tr(GuiLang.BUTTON_ALL_GROUPS)) + 12).coerceAtMost(centerWidth)
        val allX = if (panel.toolbarRows == 1) panel.contentX + 70 + (centerWidth - allWidth) / 2 else (width - allWidth) / 2
        rootButton(tr(GuiLang.BUTTON_ALL_GROUPS), !busy, allX,
            panel.toolbarY + (panel.toolbarRows - 1) * 22, allWidth) {
            browsing = true; show(View.MAIN); controller.browse(1)
        }
        val self = localPlayerId()
        current.members.sortedWith(compareByDescending<GroupMember> { it.isOwner }.thenBy { it.name.lowercase() }).forEach { member ->
            val buttons = mutableListOf<Button>()
            if (member.uuid == self) {
                buttons += makeButton(symbol("-", McTextStyle.YELLOW), !busy, 20, tr(GuiLang.TOOLTIP_LEAVE)) {
                    confirm(GuiLang.CONFIRM_LEAVE) { browsing = false; controller.leave() }
                }
            } else if (!member.isOwner) {
                if (current.canKick) buttons += makeButton(symbol("-", McTextStyle.YELLOW), !busy, 20, tr(GuiLang.TOOLTIP_KICK)) {
                    confirm(GuiLang.CONFIRM_KICK, member.name) { controller.kick(member.name) }
                }
                if (current.canBan) buttons += makeButton(symbol("×", McTextStyle.RED), !busy, 20, tr(GuiLang.TOOLTIP_BAN)) {
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
                    makeButton(symbol("+", McTextStyle.GREEN), !busy, 20, tr(GuiLang.BUTTON_UNBAN)) { controller.unban(player.name) }))
            }
        }
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
            list!!.addControl(makeButton(McTextComponent.literal(name), !busy, (list!!.containerWidth - 24).coerceAtLeast(1)) {
                selectedPlayer = player.name; refreshPlayers(transfer)
            }, player.uuid, player.name)
        }
        playerConfirmButton?.isActive = !busy && selectedPlayer != null
        list!!.offset = offset
    }

    private fun settings() {
        val current = state.current ?: return
        field("name", GuiLang.FIELD_NAME, GuiLang.HINT_NAME, editable = current.canSetName)
        field("password", GuiLang.FIELD_PASSWORD, GuiLang.HINT_PASSWORD_SET, 64, current.canSetPassword)
        field("permissions", GuiLang.FIELD_PERMISSIONS, GuiLang.HINT_PERMISSIONS, 1024, current.canSetPermissions)
        action(if (persistent) GuiLang.BUTTON_PERSISTENT_ON else GuiLang.BUTTON_PERSISTENT_OFF,
            !busy && current.canSetPersistent) { persistent = !persistent; dirty = true }
        action(GuiLang.BUTTON_REMOVE_PASSWORD, !busy && current.canUnsetPassword) {
            drafts["password"] = ""; controller.clearPassword()
        }
        action(GuiLang.BUTTON_CLEAR_PERMISSIONS, !busy && current.canUnsetPermissions) {
            drafts["permissions"] = ""; controller.clearPermissions()
        }
        footer(GuiLang.BUTTON_SAVE, !busy && canEdit(current)) {
            val name = drafts["name"].orEmpty().trim()
            val password = drafts["password"].orEmpty()
            val permissions = permissions()
            if (current.canSetName && name.isNotEmpty() && name != current.name) controller.setName(name)
            if (current.canSetPassword && password.isNotEmpty()) controller.setPassword(password)
            if (current.canSetPersistent && persistent != current.persistent) controller.setPersistent(persistent)
            if (current.canSetPermissions && permissions.toSet() != current.permissions.toSet()) controller.setPermissions(permissions)
        }
    }

    private fun canEdit(group: GroupSnapshot) = group.canSetName || group.canSetPassword || group.canUnsetPassword ||
        group.canSetPersistent || group.canSetPermissions || group.canUnsetPermissions

    private fun confirm(key: String, name: String? = null, action: () -> Unit) {
        confirmText = if (name == null) tr(key) else tr(key, name)
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
        widget.setResponder { value -> drafts[id] = value; changed() }
        fields[id] = widget
        list!!.addControl(widget)
    }

    private fun label(text: McTextComponent, color: Color = Color.WHITE) = list!!.addText(text, color)
    private fun action(key: String, enabled: Boolean, run: () -> Unit) = action(tr(key), enabled, run)
    private fun action(text: McTextComponent, enabled: Boolean, run: () -> Unit) {
        list!!.addControl(makeButton(text, enabled, list!!.containerWidth, run = run))
    }

    private fun makeButton(text: McTextComponent, enabled: Boolean, width: Int,
                           hint: McTextComponent = text, run: () -> Unit): Button {
        val message = McTextComponent.literal(RenderUtil.stringToWidth(RenderUtil.getFormattedString(text), (width - 8).coerceAtLeast(1)))
        return Button(0, 0, width, 20, message, { run() }, { _, x, y ->
            tooltip = TooltipData(hint, x, y)
        }).apply { isActive = enabled }
    }

    private fun rootButton(text: McTextComponent, enabled: Boolean, x: Int, y: Int, width: Int,
                           hint: McTextComponent = text, run: () -> Unit): Button =
        addRenderWidget(makeButton(text, enabled, width, hint, run).apply { this.x = x; this.y = y })

    private fun rootField(id: String, hint: String, y: Int, maxLength: Int, changed: () -> Unit = {}) {
        val widget = HintTextField(panel.contentX, y, panel.contentWidth, tr(hint))
        widget.maxLength = maxLength
        widget.value = drafts[id].orEmpty()
        widget.isEditable = !busy
        widget.setResponder { value -> drafts[id] = value; changed() }
        fields[id] = widget
        addRenderWidget(widget)
    }

    private fun footer(primary: String, enabled: Boolean, back: String = GuiLang.BUTTON_BACK, run: () -> Unit): Button {
        val buttonWidth = ((panel.contentWidth - 6) / 2).coerceIn(1, 130)
        rootButton(tr(back), !busy, width / 2 + 3, panel.bottom - 30, buttonWidth) { goBack() }
        return rootButton(tr(primary), enabled, width / 2 - buttonWidth - 3, panel.bottom - 30, buttonWidth, run = run)
    }

    private fun goBack() {
        when (view) {
            View.MAIN -> ScreenWrapper.openScreen(null)
            View.GROUP -> { browsing = true; show(View.MAIN) }
            View.CREATE -> show(View.MAIN)
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
        View.CREATE -> GuiLang.TITLE_CREATE
        View.GROUP -> GuiLang.TITLE_GROUP
        View.INVITE -> GuiLang.TITLE_INVITE
        View.TRANSFER -> GuiLang.TITLE_TRANSFER
        View.SETTINGS -> GuiLang.TITLE_SETTINGS
        View.CONFIRM -> GuiLang.SCREEN_TITLE
    }

    private fun tr(key: String, vararg args: String): McTextComponent = McTextComponent.translatable(key, *args)
}
