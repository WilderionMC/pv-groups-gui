package su.plo.voice.groups.gui.fabric

import net.minecraft.client.Minecraft
import su.plo.voice.groups.gui.client.GroupsGuiController
import su.plo.voice.groups.gui.client.GroupsGuiScreenOpener

class GroupsGuiScreenOpenerImpl : GroupsGuiScreenOpener {
    override fun openGroupsGui(controller: GroupsGuiController) {
        val client = Minecraft.getInstance()
        client.gui.setScreen(GroupsGuiScreen(client.gui.screen(), controller))
    }
}
