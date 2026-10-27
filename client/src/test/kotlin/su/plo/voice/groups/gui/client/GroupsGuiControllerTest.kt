package su.plo.voice.groups.gui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import su.plo.voice.groups.gui.protocol.GuiLang
import su.plo.voice.groups.gui.protocol.BrowsePage
import su.plo.voice.groups.gui.protocol.GuiMessage
import su.plo.voice.groups.gui.protocol.PlayerRef
import java.util.UUID

class GroupsGuiControllerTest {
    @Test
    fun settingsWaitForAcknowledgementAndIgnoreUnrelatedStateUpdates() {
        val sent = mutableListOf<GuiMessage.C2S>()
        val controller = GroupsGuiController({ sent += it }, { it.run() })
        val first = GuiMessage.C2S.SetPassword(null)
        val second = GuiMessage.C2S.SetPermissions(null)
        controller.onOpen()
        sent.clear()
        controller.saveSettings(listOf(first, second))
        assertEquals<List<GuiMessage.C2S>>(listOf(first), sent)
        controller.handle(GuiMessage.S2C.State(null))
        assertTrue(controller.state.savingSettings)
        assertNotNull(controller.state.pendingAction)
        controller.handle(GuiMessage.S2C.ActionResult(true, GuiLang.SUCCESS_PASSWORD_UNSET, emptyList()))
        assertEquals(listOf(first, second), sent)
        assertTrue(controller.state.savingSettings)
        controller.handle(GuiMessage.S2C.ActionResult(true, GuiLang.SUCCESS_PERMISSIONS_UNSET, emptyList()))
        assertFalse(controller.state.savingSettings)
        assertEquals(GuiLang.SUCCESS_SETTINGS, controller.state.status?.translationKey)
    }

    @Test
    fun rejectedSettingStopsRemainingChangesAndPreservesError() {
        val sent = mutableListOf<GuiMessage.C2S>()
        val controller = GroupsGuiController({ sent += it }, { it.run() })
        controller.saveSettings(listOf(GuiMessage.C2S.SetPassword(null), GuiMessage.C2S.SetPermissions(null)))
        controller.handle(GuiMessage.S2C.ActionResult(false, GuiLang.ERROR_NO_PERMISSION_SET, emptyList()))
        assertEquals(1, sent.count { it is GuiMessage.C2S.SetPassword || it is GuiMessage.C2S.SetPermissions })
        assertFalse(controller.state.savingSettings)
        assertEquals(GuiLang.ERROR_NO_PERMISSION_SET, controller.state.status?.translationKey)
    }

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
