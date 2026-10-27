import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.config.hotkey.Hotkey;
import su.plo.voice.api.client.connection.ServerConnection;
import su.plo.voice.client.config.hotkey.ConfigHotkeys;
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon;
import su.plo.voice.groups.gui.client.GroupsGuiController;
import su.plo.voice.groups.gui.protocol.GuiLang;
import su.plo.voice.groups.gui.ui.PlasmoClientRuntime;

class VerifyHotkey {
    static void verify(boolean customKey) throws Exception {
        var hotkeys = new ConfigHotkeys();
        if (customKey) hotkeys.register(GuiLang.KEY_OPEN, Collections.singletonList(Hotkey.Type.KEYSYM.getOrCreate(74)), GuiLang.KEY_CATEGORY, false);
        var connection = (ServerConnection) Proxy.newProxyInstance(ServerConnection.class.getClassLoader(), new Class<?>[]{ServerConnection.class}, (p,m,a) -> null);
        var voice = (PlasmoVoiceClient) Proxy.newProxyInstance(PlasmoVoiceClient.class.getClassLoader(), new Class<?>[]{PlasmoVoiceClient.class}, (p,m,a) -> {
            if (m.getName().equals("getHotkeys")) return hotkeys;
            if (m.getName().equals("getServerConnection")) return Optional.of(connection);
            throw new AssertionError(m.getName());
        });
        var addon = new GroupsGuiClientAddon();
        var controller = new GroupsGuiController(message -> Unit.INSTANCE, runnable -> { runnable.run(); return Unit.INSTANCE; });
        var controllerField = GroupsGuiClientAddon.class.getDeclaredField("controller");
        controllerField.setAccessible(true);
        controllerField.set(addon, controller);
        var opens = new AtomicInteger();
        addon.setScreenOpener(ignored -> opens.incrementAndGet());
        var register = PlasmoClientRuntime.class.getDeclaredMethod("registerHotkey", PlasmoVoiceClient.class, GroupsGuiClientAddon.class);
        register.setAccessible(true);
        register.invoke(PlasmoClientRuntime.INSTANCE, voice, addon);
        var key = Hotkey.Type.KEYSYM.getOrCreate(customKey ? 74 : 71);
        var active = hotkeys.getHotkey(GuiLang.KEY_OPEN).orElseThrow();
        if (!active.getKeys().contains(key)) throw new AssertionError("Binding was reset");
        hotkeys.getPressedKeys().add(key);
        active.updateState(Hotkey.Action.DOWN);
        active.updateState(Hotkey.Action.DOWN);
        if (opens.get() != 1) throw new AssertionError("Expected one GUI open, got " + opens.get());
        hotkeys.getPressedKeys().clear();
        active.updateState(Hotkey.Action.UP);
        if (opens.get() != 1) throw new AssertionError("Release opened GUI");
    }
    public static void main(String[] args) throws Exception {
        verify(false);
        verify(true);
        System.out.println("PASS: active Plasmo hotkey opens once; release ignored; custom binding preserved");
    }
}
