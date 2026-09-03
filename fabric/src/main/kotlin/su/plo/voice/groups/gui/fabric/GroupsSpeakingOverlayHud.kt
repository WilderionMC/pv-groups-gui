package su.plo.voice.groups.gui.fabric

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon
import su.plo.voice.groups.gui.client.GroupsSpeakingActivityTracker

object GroupsSpeakingOverlayHud {
    private val ELEMENT_ID = Identifier.fromNamespaceAndPath("pv-addon-groups-gui", "speaking_heads")

    fun register() {
        HudElementRegistry.addLast(ELEMENT_ID, HudElement { graphics, _ ->
            val client = Minecraft.getInstance()
            if (client.gui.hud.isHidden || client.player == null) return@HudElement

            val config = GroupsSpeakingOverlayConfigStorage.config
            if (!config.enabled) return@HudElement

            val addon = GroupsGuiClientAddon.INSTANCE ?: return@HudElement
            val group = addon.controller.state.current ?: return@HudElement
            val members = group.members
            if (members.isEmpty()) return@HudElement

            val speaking = GroupsSpeakingActivityTracker.activeSpeakers()
                .intersect(members.map { it.uuid }.toSet())

            GroupsSpeakingOverlayRenderer.render(
                graphics = graphics,
                members = members,
                speaking = speaking,
                config = config,
                screenWidth = graphics.guiWidth(),
                screenHeight = graphics.guiHeight(),
            )
        })
    }
}
