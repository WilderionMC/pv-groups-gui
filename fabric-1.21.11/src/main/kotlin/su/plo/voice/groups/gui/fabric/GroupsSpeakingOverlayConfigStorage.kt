package su.plo.voice.groups.gui.fabric

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import su.plo.voice.groups.gui.client.GroupsSpeakingOverlayConfig
import su.plo.voice.groups.gui.client.SpeakingOverlayAnchor
import su.plo.voice.groups.gui.client.SpeakingOverlayLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object GroupsSpeakingOverlayConfigStorage {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configPath: Path =
        FabricLoader.getInstance().configDir.resolve("pv-addon-groups-gui-overlay.json")

    @Volatile
    var config: GroupsSpeakingOverlayConfig = load()
        private set

    fun save(updated: GroupsSpeakingOverlayConfig) {
        config = updated
        Files.writeString(configPath, gson.toJson(toPersisted(updated)))
    }

    fun reload() {
        config = load()
    }

    private fun load(): GroupsSpeakingOverlayConfig {
        if (!configPath.exists()) {
            return GroupsSpeakingOverlayConfig()
        }
        return runCatching {
            gson.fromJson(Files.readString(configPath), Persisted::class.java).toConfig()
        }.getOrElse { GroupsSpeakingOverlayConfig() }
    }

    private fun toPersisted(config: GroupsSpeakingOverlayConfig): Persisted =
        Persisted(
            enabled = config.enabled,
            anchor = config.anchor.name,
            layout = config.layout.name,
            headSize = config.headSize,
            margin = config.margin,
            gap = config.gap,
        )

    private data class Persisted(
        val enabled: Boolean = true,
        val anchor: String = SpeakingOverlayAnchor.TOP_LEFT.name,
        val layout: String = SpeakingOverlayLayout.HORIZONTAL.name,
        val headSize: Int = 24,
        val margin: Int = 8,
        val gap: Int = 4,
    ) {
        fun toConfig(): GroupsSpeakingOverlayConfig = GroupsSpeakingOverlayConfig(
            enabled = enabled,
            anchor = runCatching { SpeakingOverlayAnchor.valueOf(anchor) }
                .getOrElse { SpeakingOverlayAnchor.TOP_LEFT },
            layout = runCatching { SpeakingOverlayLayout.valueOf(layout) }
                .getOrElse { SpeakingOverlayLayout.HORIZONTAL },
            headSize = headSize.coerceIn(16, 48),
            margin = margin.coerceIn(0, 64),
            gap = gap.coerceIn(0, 16),
        )
    }
}
