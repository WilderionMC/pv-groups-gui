package su.plo.voice.groups.gui.fabric

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.Minecraft
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon
import su.plo.voice.groups.gui.client.GroupsSpeakingActivityTracker

object GroupsSpeakingOverlayHud {
    fun register() {
        HudRenderCallback.EVENT.register { graphics, _ ->
            val client = Minecraft.getInstance()
            if (client.options.hideGui || client.player == null) return@register

            val config = GroupsSpeakingOverlayConfigStorage.config
            if (!config.enabled) return@register

            val addon = GroupsGuiClientAddon.INSTANCE ?: return@register
            val group = addon.controller.state.current ?: return@register
            val members = group.members
            if (members.isEmpty()) return@register

            val speaking = GroupsSpeakingActivityTracker.activeSpeakers()
                .intersect(members.map { it.uuid }.toSet())

            GroupsSpeakingOverlayRenderer.render(
                graphics = graphics,
                members = members,
                speaking = speaking,
                config = config,
                screenWidth = client.window.guiScaledWidth,
                screenHeight = client.window.guiScaledHeight,
            )
        }
    }
}
