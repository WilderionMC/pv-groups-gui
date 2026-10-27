package su.plo.voice.groups.gui.ui

import su.plo.lib.mod.client.gui.components.AbstractScrollbar
import su.plo.lib.mod.client.gui.widget.GuiAbstractWidget
import su.plo.lib.mod.client.gui.widget.GuiWidgetListener
import su.plo.lib.mod.client.render.RenderUtil
import su.plo.lib.mod.client.render.gui.GuiRenderContext
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.voice.groups.gui.protocol.GroupSnapshot
import java.awt.Color
import java.util.UUID

class GroupsGuiList(parent: GroupsGuiScreen, width: Int, top: Int, bottom: Int) :
    AbstractScrollbar<GroupsGuiScreen>(parent, width, top, bottom) {

    private var restoredOffset: Double? = null
    var offset: Double
        get() = scrollTop
        set(value) { restoredOffset = value; setScrollTop(value) }

    override fun init() = Unit
    override fun getScrollbarPosition() = containerX1 + 3
    override fun render(context: GuiRenderContext, mouseX: Int, mouseY: Int, delta: Float) {
        if (y1 - y0 < 40) renderList(context, containerX0, y0, mouseX, mouseY, delta)
        else super.render(context, mouseX, mouseY, delta)
    }

    override fun renderList(context: GuiRenderContext, x: Int, y: Int, mouseX: Int, mouseY: Int, delta: Float) {
        // Plasmo resets scrolling on the first render after its content height changes.
        restoredOffset?.let { setScrollTop(it); restoredOffset = null }
        super.renderList(context, x, y, mouseX, mouseY, delta)
    }

    fun addControl(widget: GuiAbstractWidget, playerId: UUID? = null, playerName: String = "") {
        playerId?.let { PlasmoPlayerHeads.load(it, playerName) }
        addEntry(ControlEntry(widget, playerId, playerName))
    }
    fun addText(text: McTextComponent, color: Color) = addEntry(TextEntry(text, color))
    fun addPlayer(id: UUID, name: String, text: McTextComponent, buttons: List<GuiAbstractWidget> = emptyList()) {
        PlasmoPlayerHeads.load(id, name)
        addEntry(object : Entry(32) {
            override fun widgets(): List<GuiWidgetListener> = buttons
            override fun updatePosition(context: GuiRenderContext, index: Int, x: Int, y: Int, entryWidth: Int,
                                        mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
                buttons.forEachIndexed { i, button ->
                    button.x = x + entryWidth - (buttons.size - i) * 22
                    button.y = y + 6
                    button.width = 20
                }
            }
            override fun render(context: GuiRenderContext, index: Int, x: Int, y: Int, entryWidth: Int,
                                mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
                if (hovered) context.fill(x - 2, y + 1, x + entryWidth, y + 31, Color(255, 255, 255, 12))
                PlasmoPlayerHeads.render(context, id, name, x, y + 4, 24)
                context.drawString(RenderUtil.stringToWidth(RenderUtil.getFormattedString(text),
                    (entryWidth - 34 - buttons.size * 22).coerceAtLeast(1)), x + 32, y + 12, Color.WHITE)
                buttons.forEach { it.render(context, mouseX, mouseY, delta) }
            }
        })
    }

    fun addGroup(group: GroupSnapshot, button: GuiAbstractWidget) {
        group.members.forEach { PlasmoPlayerHeads.load(it.uuid, it.name) }
        addEntry(object : Entry(if (group.members.isEmpty()) 26 else 50) {
            override fun widgets(): List<GuiWidgetListener> = listOf(button)
            override fun updatePosition(context: GuiRenderContext, index: Int, x: Int, y: Int, entryWidth: Int,
                                        mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
                button.x = x
                button.y = y + 2
                button.width = entryWidth
            }
            override fun render(context: GuiRenderContext, index: Int, x: Int, y: Int, entryWidth: Int,
                                mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
                button.render(context, mouseX, mouseY, delta)
                val count = ((entryWidth - 30) / 20).coerceAtLeast(0)
                group.members.take(count).forEachIndexed { i, member ->
                    PlasmoPlayerHeads.render(context, member.uuid, member.name, x + i * 20, y + 26)
                }
                if (group.members.size > count) context.drawString("+${group.members.size - count}", x + count * 20, y + 30, Color.LIGHT_GRAY)
            }
        })
    }

    fun focusField(widget: GuiAbstractWidget): Boolean {
        val entry = entries.filterIsInstance<ControlEntry>().firstOrNull { it.widget === widget } ?: return false
        entry.focused = widget
        focused = entry
        return true
    }

    private inner class ControlEntry(val widget: GuiAbstractWidget, val playerId: UUID?, val playerName: String) : Entry(26) {
        override fun widgets(): List<GuiWidgetListener> = listOf(widget)

        override fun updatePosition(context: GuiRenderContext, index: Int, x: Int, y: Int, entryWidth: Int,
                                    mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            val inset = if (playerId == null) 0 else 24
            widget.x = x + inset
            widget.y = y + 2
            widget.width = (entryWidth - inset).coerceAtLeast(1)
        }

        override fun render(context: GuiRenderContext, index: Int, x: Int, y: Int, entryWidth: Int,
                            mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            playerId?.let { PlasmoPlayerHeads.render(context, it, playerName, x, y + 4) }
            widget.render(context, mouseX, mouseY, delta)
        }
    }

    private inner class TextEntry(val text: McTextComponent, val color: Color) : Entry(18) {
        private val lines = PlasmoText.wrap(RenderUtil.getFormattedString(text), containerWidth)

        init { height = lines.size * RenderUtil.getFontHeight() + 8 }

        override fun render(context: GuiRenderContext, index: Int, x: Int, y: Int, entryWidth: Int,
                            mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            lines.forEachIndexed { index, line ->
                context.drawString(line, x, y + 4 + index * RenderUtil.getFontHeight(), color)
            }
        }
    }
}
