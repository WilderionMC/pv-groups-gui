package su.plo.voice.groups.gui.ui;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FontDescription;
import org.lwjgl.glfw.GLFW;
import su.plo.lib.mod.client.gui.screen.ScreenWrapper;
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Function;

public class GroupsGuiClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> client.options.guiScale().set(2));
        context.getInput().resizeWindow(960, 640);
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> GroupsGuiClientAddon.getINSTANCE().plasmoVoiceClient().getServerConnection().isPresent(), 200);
            context.setScreen(() -> null);
            context.getInput().pressKey(GLFW.GLFW_KEY_G);
            context.waitForScreen(ScreenWrapper.class);
            if (!context.computeOnClient(client -> ScreenWrapper.getCurrentWrappedScreen().orElseThrow().getScreen() instanceof GroupsGuiScreen)) {
                throw new AssertionError("G did not open the production addon screen");
            }
            context.takeScreenshot("groups-gui-hotkey-open");
            System.out.println("PV GUI: PASS hotkey-open");
            new UiScenarios().run(new UiScenarios.Context() {
                public void runOnClient(Consumer<Object> action) { context.runOnClient(action::accept); }
                public <T> T computeOnClient(Function<Object, T> action) { return context.computeOnClient(action::apply); }
                public void waitTicks(int ticks) { context.waitTicks(ticks); }
                public void openScreen(GroupsGuiScreen screen) { ScreenWrapper.openScreen(screen); }
                public void takeScreenshot(String name) { context.takeScreenshot(name); }
                public void assertSymbols() {
                    context.runOnClient(client -> {
                        try {
                        Field field = Font.class.getDeclaredField("provider");
                        field.setAccessible(true);
                        var glyphs = ((Font.Provider) field.get(client.font)).glyphs(FontDescription.DEFAULT);
                        for (int symbol : new int[] {'★', '→', '✎'}) {
                            if (glyphs.getGlyph(symbol).info() == glyphs.getGlyph(0x10ffff).info()) {
                                throw new AssertionError("Missing glyph: " + symbol);
                            }
                        }
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    });
                }
                public UiScenarios.Input getInput() {
                    return new UiScenarios.Input() {
                        public void setCursorPos(double x, double y) {
                            var position = context.computeOnClient(client -> {
                                var screen = ScreenWrapper.getCurrentWrappedScreen().orElseThrow().getScreen();
                                return new double[] {x * client.getWindow().getScreenWidth() / screen.getWidth(),
                                        y * client.getWindow().getScreenHeight() / screen.getHeight()};
                            });
                            context.getInput().setCursorPos(position[0], position[1]);
                        }
                        public void pressMouse(int button) { context.getInput().pressMouse(button); }
                        public void typeChars(String text) { context.getInput().typeChars(text); }
                        public void scroll(double amount) { context.getInput().scroll(amount); }
                        public void resizeWindow(int width, int height) { context.getInput().resizeWindow(width, height); }
                    };
                }
            });
            System.out.println("PV GUI: PASS 11 client scenarios");
        }
    }
}
