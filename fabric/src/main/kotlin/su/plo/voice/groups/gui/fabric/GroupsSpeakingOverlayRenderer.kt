package su.plo.voice.groups.gui.fabric

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceWidget
import net.minecraft.world.item.component.ResolvableProfile
import su.plo.voice.groups.gui.client.GroupsSpeakingOverlayConfig
import su.plo.voice.groups.gui.client.SpeakingOverlayAnchor
import su.plo.voice.groups.gui.client.SpeakingOverlayLayout
import su.plo.voice.groups.gui.protocol.GroupMember
import java.util.UUID

object GroupsSpeakingOverlayRenderer {
    private const val SPEAKING_BORDER = 2
    private const val SPEAKING_BORDER_COLOR = 0xFFFFFFFF.toInt()

    fun render(
        graphics: GuiGraphicsExtractor,
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

        val (startX, startY) = anchorOrigin(
            anchor = config.anchor,
            margin = config.margin,
            totalWidth = totalWidth,
            totalHeight = totalHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        members.forEachIndexed { index, member ->
            val x = if (layout == SpeakingOverlayLayout.HORIZONTAL) {
                startX + index * (headSize + gap)
            } else {
                startX
            }
            val y = if (layout == SpeakingOverlayLayout.VERTICAL) {
                startY + index * (headSize + gap)
            } else {
                startY
            }
            renderHead(graphics, member.uuid, x, y, headSize, member.uuid in speaking)
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

    private fun anchorOrigin(
        anchor: SpeakingOverlayAnchor,
        margin: Int,
        totalWidth: Int,
        totalHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> = when (anchor) {
        SpeakingOverlayAnchor.TOP_LEFT -> margin to margin
        SpeakingOverlayAnchor.TOP -> (screenWidth - totalWidth) / 2 to margin
        SpeakingOverlayAnchor.TOP_RIGHT -> screenWidth - margin - totalWidth to margin
        SpeakingOverlayAnchor.LEFT -> margin to (screenHeight - totalHeight) / 2
        SpeakingOverlayAnchor.RIGHT -> screenWidth - margin - totalWidth to (screenHeight - totalHeight) / 2
        SpeakingOverlayAnchor.BOTTOM_LEFT -> margin to screenHeight - margin - totalHeight
        SpeakingOverlayAnchor.BOTTOM -> (screenWidth - totalWidth) / 2 to screenHeight - margin - totalHeight
        SpeakingOverlayAnchor.BOTTOM_RIGHT -> screenWidth - margin - totalWidth to screenHeight - margin - totalHeight
    }

    private fun renderHead(
        graphics: GuiGraphicsExtractor,
        uuid: UUID,
        x: Int,
        y: Int,
        size: Int,
        speaking: Boolean,
    ) {
        if (speaking) {
            val border = SPEAKING_BORDER
            graphics.fill(
                x - border,
                y - border,
                x + size + border,
                y + size + border,
                SPEAKING_BORDER_COLOR,
            )
        }

        val widget = PlayerFaceWidget(size, ResolvableProfile.createUnresolved(uuid))
        widget.setX(x)
        widget.setY(y)
        widget.setWidth(size)
        widget.setHeight(size)
        widget.extractRenderState(graphics, 0, 0, 0f)
    }
}
