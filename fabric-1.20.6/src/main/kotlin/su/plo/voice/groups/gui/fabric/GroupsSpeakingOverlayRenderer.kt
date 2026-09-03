package su.plo.voice.groups.gui.fabric

import net.minecraft.client.gui.GuiGraphics
import su.plo.voice.groups.gui.client.GroupsSpeakingOverlayConfig
import su.plo.voice.groups.gui.client.SpeakingOverlayAnchor
import su.plo.voice.groups.gui.client.SpeakingOverlayLayout
import su.plo.voice.groups.gui.protocol.GroupMember
import java.util.UUID

object GroupsSpeakingOverlayRenderer {
    private const val SPEAKING_BORDER = 2
    private const val SPEAKING_BORDER_COLOR = 0xFFFFFFFF.toInt()
    private const val HEAD_COLOR = 0xFF555555.toInt()

    fun render(
        graphics: GuiGraphics,
        members: List<GroupMember>,
        speaking: Set<UUID>,
        config: GroupsSpeakingOverlayConfig,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        if (members.isEmpty()) return
        val layout = effectiveLayout(config.anchor, config.layout)
        val headSize = config.headSize
        val gap = config.gap
        val count = members.size
        val totalWidth = if (layout == SpeakingOverlayLayout.HORIZONTAL) {
            count * headSize + (count - 1).coerceAtLeast(0) * gap
        } else {
            headSize
        }
        val totalHeight = if (layout == SpeakingOverlayLayout.VERTICAL) {
            count * headSize + (count - 1).coerceAtLeast(0) * gap
        } else {
            headSize
        }
        val (startX, startY) = when (config.anchor) {
            SpeakingOverlayAnchor.TOP_LEFT -> config.margin to config.margin
            SpeakingOverlayAnchor.TOP -> (screenWidth - totalWidth) / 2 to config.margin
            SpeakingOverlayAnchor.TOP_RIGHT -> screenWidth - config.margin - totalWidth to config.margin
            SpeakingOverlayAnchor.LEFT -> config.margin to (screenHeight - totalHeight) / 2
            SpeakingOverlayAnchor.RIGHT -> screenWidth - config.margin - totalWidth to (screenHeight - totalHeight) / 2
            SpeakingOverlayAnchor.BOTTOM_LEFT -> config.margin to screenHeight - config.margin - totalHeight
            SpeakingOverlayAnchor.BOTTOM -> (screenWidth - totalWidth) / 2 to screenHeight - config.margin - totalHeight
            SpeakingOverlayAnchor.BOTTOM_RIGHT ->
                screenWidth - config.margin - totalWidth to screenHeight - config.margin - totalHeight
        }
        members.forEachIndexed { index, member ->
            val x = if (layout == SpeakingOverlayLayout.HORIZONTAL) startX + index * (headSize + gap) else startX
            val y = if (layout == SpeakingOverlayLayout.VERTICAL) startY + index * (headSize + gap) else startY
            if (member.uuid in speaking) {
                graphics.fill(
                    x - SPEAKING_BORDER,
                    y - SPEAKING_BORDER,
                    x + headSize + SPEAKING_BORDER,
                    y + headSize + SPEAKING_BORDER,
                    SPEAKING_BORDER_COLOR,
                )
            }
            graphics.fill(x, y, x + headSize, y + headSize, HEAD_COLOR)
        }
    }

    private fun effectiveLayout(
        anchor: SpeakingOverlayAnchor,
        layout: SpeakingOverlayLayout,
    ): SpeakingOverlayLayout = when (anchor) {
        SpeakingOverlayAnchor.TOP, SpeakingOverlayAnchor.BOTTOM -> SpeakingOverlayLayout.HORIZONTAL
        SpeakingOverlayAnchor.LEFT, SpeakingOverlayAnchor.RIGHT -> SpeakingOverlayLayout.VERTICAL
        else -> layout
    }
}
