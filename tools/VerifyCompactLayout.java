import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import su.plo.voice.groups.gui.protocol.GuiLang;
import su.plo.voice.groups.gui.ui.GroupsGuiPanelLayout;

class VerifyCompactLayout {
    public static void main(String[] args) throws Exception {
        int screens = 0;
        for (int width : new int[]{160, 240, 320, 426, 854, 1920, 3840}) {
            for (int height : new int[]{160, 240, 270, 480, 1080, 2160}) {
                for (int preferred : new int[]{180, 200, 240, 280}) {
                    var panel = GroupsGuiPanelLayout.Companion.fit(width, height, preferred);
                    if (panel.getX() < 0 || panel.getY() < 0 || panel.getRight() > width || panel.getBottom() > height) {
                        throw new AssertionError("Panel escapes the screen at " + width + "x" + height);
                    }
                    if (panel.getWidth() > 320 || panel.getHeight() > preferred) throw new AssertionError("Panel fills a large screen");
                    if (Math.abs(panel.getX() - (width - panel.getRight())) > 1 || Math.abs(panel.getY() - (height - panel.getBottom())) > 1) {
                        throw new AssertionError("Panel is not centered");
                    }
                    if (panel.getToolbarY() <= panel.getY() + 42 || panel.getToolbarY() + panel.getToolbarRows() * 22 > panel.getBottom() - 8) {
                        throw new AssertionError("Toolbar overlaps header or escapes panel");
                    }
                    if (panel.getContentWidth() < 86) throw new AssertionError("No room for participant controls");
                    screens++;
                }
            }
        }
        var directory = Path.of("client-ui/src/main/resources/assets/pv-addon-groups-gui/lang");
        var english = JsonParser.parseString(Files.readString(directory.resolve("en_us.json"))).getAsJsonObject();
        int locales = 0;
        try (var paths = Files.list(directory)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var locale = path.getFileName().toString();
                var translations = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (!translations.keySet().equals(english.keySet())) throw new AssertionError(locale + ": translation keys differ from English");
                for (var key : english.keySet()) {
                    if (!arguments(english.get(key).getAsString()).equals(arguments(translations.get(key).getAsString()))) {
                        throw new AssertionError(locale + ": format arguments differ for " + key);
                    }
                }
                for (var field : GuiLang.class.getDeclaredFields()) {
                    if (field.getType() != String.class || field.getName().equals("PREFIX")) continue;
                    String key = (String)field.get(null);
                    if (!translations.has(key)) throw new AssertionError(locale + ": missing " + key);
                }
                locales++;
            }
        }
        System.out.println("PASS: " + screens + " window/GUI-scale sizes; centered compact panel, toolbar bounds; " + locales + " complete locales with matching format arguments");
    }

    private static Map<Integer, Integer> arguments(String text) {
        var result = new TreeMap<Integer, Integer>();
        var matcher = Pattern.compile("%(?:(\\d+)\\$)?s").matcher(text);
        int implicit = 1;
        while (matcher.find()) {
            int index = matcher.group(1) == null ? implicit++ : Integer.parseInt(matcher.group(1));
            result.merge(index, 1, Integer::sum);
        }
        return result;
    }
}
