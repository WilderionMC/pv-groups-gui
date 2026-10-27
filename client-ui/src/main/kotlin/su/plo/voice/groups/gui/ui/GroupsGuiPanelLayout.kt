package su.plo.voice.groups.gui.ui

data class GroupsGuiPanelLayout(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height
    val contentX get() = x + 10
    val contentWidth get() = (width - 20).coerceAtLeast(1)
    val toolbarRows get() = if (width < 240) 2 else 1
    val toolbarY get() = bottom - 10 - toolbarRows * 22

    companion object {
        @JvmStatic
        fun fit(screenWidth: Int, screenHeight: Int, preferredHeight: Int): GroupsGuiPanelLayout {
            val width = (screenWidth - 24).coerceIn(1, 320)
            val height = (screenHeight - 24).coerceIn(1, preferredHeight)
            return GroupsGuiPanelLayout((screenWidth - width) / 2, (screenHeight - height) / 2, width, height)
        }
    }
}
