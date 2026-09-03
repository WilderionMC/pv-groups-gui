package su.plo.voice.groups.gui.client

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Event-driven speaking state for the group HUD overlay (Talkin Heads style).
 */
object GroupsSpeakingActivityTracker {
    private const val SPEAKING_TTL_MS = 500L
    private const val MIN_AUDIO_LEVEL = 0.008

    private val speakingUntil = ConcurrentHashMap<UUID, Long>()

    @Volatile
    var lastLocalAudioLevel: Double = 0.0
        private set

    fun updateLocalAudioLevel(level: Double) {
        lastLocalAudioLevel = level
    }

    fun markSpeaking(uuid: UUID, level: Double = 1.0) {
        if (level < MIN_AUDIO_LEVEL) return
        if (!isGroupMember(uuid)) return
        speakingUntil[uuid] = System.currentTimeMillis() + SPEAKING_TTL_MS
    }

    fun activeSpeakers(now: Long = System.currentTimeMillis()): Set<UUID> {
        speakingUntil.entries.removeIf { (_, until) -> until <= now }
        return speakingUntil.filterValues { until -> until > now }.keys.toSet()
    }

    fun clear() {
        speakingUntil.clear()
        lastLocalAudioLevel = 0.0
    }

    fun calculateAudioLevel(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / samples.size)
    }

    fun calculateAudioLevel(samples: Array<Short>): Double =
        calculateAudioLevel(samples.toShortArray())

    private fun isGroupMember(uuid: UUID): Boolean {
        val group = GroupsGuiClientAddon.INSTANCE?.controller?.state?.current ?: return false
        return group.members.any { it.uuid == uuid }
    }
}
