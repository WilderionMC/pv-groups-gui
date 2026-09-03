package su.plo.voice.groups.gui.client

enum class SpeakingOverlayAnchor {
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    LEFT,
    RIGHT,
    BOTTOM_LEFT,
    BOTTOM,
    BOTTOM_RIGHT,
    ;

    fun next(): SpeakingOverlayAnchor = entries[(ordinal + 1) % entries.size]
}

enum class SpeakingOverlayLayout {
    HORIZONTAL,
    VERTICAL,
    ;

    fun toggle(): SpeakingOverlayLayout =
        if (this == HORIZONTAL) VERTICAL else HORIZONTAL
}

data class GroupsSpeakingOverlayConfig(
    var enabled: Boolean = true,
    var anchor: SpeakingOverlayAnchor = SpeakingOverlayAnchor.TOP_LEFT,
    var layout: SpeakingOverlayLayout = SpeakingOverlayLayout.HORIZONTAL,
    var headSize: Int = 24,
    var margin: Int = 8,
    var gap: Int = 4,
)
