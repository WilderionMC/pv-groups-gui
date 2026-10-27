package su.plo.voice.groups.gui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import su.plo.voice.groups.gui.protocol.BrowsePage
import su.plo.voice.groups.gui.protocol.GuiMessage
import su.plo.voice.groups.gui.protocol.PlayerRef
import java.util.UUID

class GroupsGuiControllerTest {
    @Test
    fun unchangedRefreshDoesNotRebuildFocusedWidgets() {
        val controller = GroupsGuiController({}, { it.run() })
        var updates = 0
        controller.addListener { updates++ }
        val page = BrowsePage(1, 1, emptyList(), null)
        val player = PlayerRef(UUID.randomUUID(), "OtherPlayer")
        controller.handle(GuiMessage.S2C.Browse(page))
        controller.handle(GuiMessage.S2C.InviteCandidates(listOf(player)))
        val initial = updates
        repeat(3) {
            controller.handle(GuiMessage.S2C.Browse(page.copy()))
            controller.handle(GuiMessage.S2C.State(null))
            controller.handle(GuiMessage.S2C.InviteCandidates(listOf(player.copy())))
        }
        assertEquals(initial, updates)
        controller.handle(GuiMessage.S2C.InviteCandidates(emptyList()))
        assertEquals(initial + 1, updates)
    }
}
