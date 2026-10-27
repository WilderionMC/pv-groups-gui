package su.plo.voice.groups.gui.ui;

import java.util.List;
import su.plo.voice.client.extension.TextKt;

/** Plasmo's published shadow JAR omits the Kotlin module metadata for its top-level functions. */
public final class PlasmoText {
    private PlasmoText() { }

    public static List<String> wrap(String text, int width) {
        return TextKt.getStringSplitToWidth(text, width, false, true);
    }
}
