package su.plo.voice.groups.gui.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import su.plo.lib.mod.client.gui.screen.GuiScreen;
import su.plo.lib.mod.client.gui.screen.ScreenWrapper;
import su.plo.lib.mod.client.gui.screen.GuiScreenListener;
import su.plo.lib.mod.client.gui.components.Button;
import su.plo.lib.mod.client.gui.components.TextFieldWidget;
import su.plo.lib.mod.client.gui.widget.GuiAbstractWidget;
import su.plo.lib.mod.client.gui.widget.GuiWidgetListener;
import su.plo.lib.mod.client.render.RenderUtil;
import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon;
import su.plo.voice.groups.gui.client.GroupsGuiController;
import su.plo.voice.groups.gui.protocol.GroupSnapshot;
import su.plo.voice.groups.gui.protocol.GuiMessage;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Drives the production controller in a connected Minecraft client. No fake packets or state. */
public final class BridgeE2eDriver {
    private final Gson gson = new Gson();
    private final Path directory = Paths.get(System.getProperty("pv.bridge.e2e.directory"));
    private final Executor client;
    private final Object minecraft;
    private final GroupsGuiClientAddon addon = GroupsGuiClientAddon.getINSTANCE();
    private final GroupsGuiController controller = addon.getController();
    private int sequence = -1;
    private String error;
    private GroupsGuiScreen screen;
    private boolean sdl;

    private BridgeE2eDriver() throws Exception {
        Field field = GuiScreen.class.getDeclaredField("minecraft");
        field.setAccessible(true);
        minecraft = field.get(null);
        client = (Executor) minecraft;
        try { Class.forName("org.lwjgl.sdl.SDLVideo", false, minecraft.getClass().getClassLoader()); sdl = true; }
        catch (ClassNotFoundException absent) { sdl = false; }
        Files.createDirectories(directory);
    }

    public static void run() throws Exception {
        BridgeE2eDriver driver = new BridgeE2eDriver();
        // As in the fixture driver, do not replace a loading screen before resources finish.
        long deadline = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < deadline) {
            if (driver.onClient(() -> !RenderUtil.getFormattedString(McTextComponent.translatable(
                    "pv.addon.groups.gui.button.create")).contains("pv.addon.groups.gui."))) {
                Thread.sleep(5000);
                if (System.getProperty("pv.bridge.e2e.connect.port") != null) {
                    driver.onClient(() -> { driver.connectAfterLoading(); return null; });
                }
                driver.loop();
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Minecraft resources not ready");
    }

    private void connectAfterLoading() throws Exception {
        Class<?> screenType = ScreenWrapper.class.getSuperclass();
        ClassLoader loader = minecraft.getClass().getClassLoader();
        Class<?> connectType;
        try { connectType = Class.forName("net.minecraft.class_412", true, loader); }
        catch (ClassNotFoundException forge) {
            try { connectType = Class.forName("net.minecraft.client.gui.screen.ConnectingScreen", true, loader); }
            catch (ClassNotFoundException modernForge) { connectType = Class.forName("net.minecraft.client.gui.screens.ConnectScreen", true, loader); }
        }
        int port = Integer.parseInt(System.getProperty("pv.bridge.e2e.connect.port"));
        try {
            Object connecting = connectType.getConstructor(screenType, minecraft.getClass(), String.class, int.class)
                    .newInstance(null, minecraft, "127.0.0.1", port);
            Class.forName("su.plo.lib.mod.extensions.MinecraftKt", true, loader)
                    .getMethod("setCurrentScreen", minecraft.getClass(), screenType).invoke(null, minecraft, connecting);
        } catch (NoSuchMethodException modern) {
            for (java.lang.reflect.Method method : connectType.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) || parameters.length != 4
                        || parameters[0] != screenType || parameters[1] != minecraft.getClass()) continue;
                Object address = parameters[2].getConstructor(String.class, int.class).newInstance("127.0.0.1", port);
                Object server = parameters[3].getConstructor(String.class, String.class, boolean.class)
                        .newInstance("Bridge E2E", "127.0.0.1:" + port, false);
                method.invoke(null, null, minecraft, address, server);
                return;
            }
            throw modern;
        }
    }

    private <T> T onClient(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<T>(action);
        client.execute(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private void loop() throws Exception {
        long deadline = System.currentTimeMillis() + 1200000;
        while (System.currentTimeMillis() < deadline) {
            Path request = directory.resolve("command.json");
            if (Files.exists(request)) {
                JsonObject command = gson.fromJson(new String(Files.readAllBytes(request), StandardCharsets.UTF_8), JsonObject.class);
                int next = command.get("sequence").getAsInt();
                if (next > sequence) {
                    try { onClient(() -> { execute(command); return null; }); error = null; }
                    catch (Throwable failure) { error = failure.toString(); failure.printStackTrace(); }
                    sequence = next;
                }
            }
            String snapshot = onClient(() -> {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("sequence", sequence);
                result.put("error", error);
                result.put("voiceConnected", addon.plasmoVoiceClient() != null && addon.plasmoVoiceClient().getServerConnection().isPresent());
                for (Field field : minecraft.getClass().getDeclaredFields()) {
                    if (field.getType() == ScreenWrapper.class.getSuperclass()) {
                        field.setAccessible(true);
                        Object activeScreen = field.get(minecraft);
                        result.put("minecraftScreen", activeScreen == null ? null : activeScreen.getClass().getName());
                        break;
                    }
                }
                result.put("state", controller.getState());
                if (screen != null) {
                    result.put("view", reflect(screen, "view").toString());
                    result.put("uiDirty", reflect(screen, "dirty"));
                    result.put("fields", ((Map<?, ?>) reflect(screen, "fields")).keySet());
                    List<Map<String, Object>> buttons = new ArrayList<Map<String, Object>>();
                    for (GuiAbstractWidget widget : widgets()) if (widget instanceof Button) {
                        Map<String, Object> button = new LinkedHashMap<String, Object>();
                        button.put("text", RenderUtil.getFormattedString(widget.getText()).replaceAll("§.", ""));
                        button.put("active", widget.isActive());
                        buttons.add(button);
                    }
                    result.put("buttons", buttons);
                }
                return gson.toJson(result);
            });
            Path temporary = directory.resolve("state.tmp");
            Files.write(temporary, snapshot.getBytes(StandardCharsets.UTF_8));
            // Windows can briefly hold the previous snapshot open in the runner.
            try { Files.move(temporary, directory.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.FileSystemException sharingViolation) { /* Publish again on the next tick. */ }
            Thread.sleep(100);
        }
        throw new AssertionError("E2E runner did not stop client within 20 minutes");
    }

    private String text(JsonObject command, String key) {
        return !command.has(key) || command.get(key).isJsonNull() ? null : command.get(key).getAsString();
    }

    private List<String> permissions(JsonObject command) {
        List<String> values = new ArrayList<String>();
        if (command.has("permissions")) command.getAsJsonArray("permissions").forEach(v -> values.add(v.getAsString()));
        return values;
    }

    private void execute(JsonObject command) throws Exception {
        String action = text(command, "action");
        if ("open".equals(action)) {
            screen = new GroupsGuiScreen(controller);
            ScreenWrapper.openScreen(screen);
            controller.onOpen();
        }
        else if ("click".equals(action)) {
            String key = text(command, "key");
            String label = key == null ? text(command, "text") : RenderUtil.getFormattedString(McTextComponent.translatable(key)).replaceAll("§.", "");
            GuiAbstractWidget button = widgets().stream().filter(w -> w instanceof Button && RenderUtil.getFormattedString(w.getText()).replaceAll("§.", "").equals(label)).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label));
            if (!button.isActive()) throw new AssertionError("Disabled button: " + label);
            double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
            screen.mouseClicked(x, y, sdl ? 1 : 0);
            screen.mouseReleased(x, y, sdl ? 1 : 0);
        }
        else if ("field".equals(action)) {
            @SuppressWarnings("unchecked") Map<String, TextFieldWidget> fields = (Map<String, TextFieldWidget>) reflect(screen, "fields");
            TextFieldWidget field = fields.get(text(command, "field"));
            if (field == null) throw new AssertionError("Missing field " + text(command, "field"));
            field.setValue(text(command, "value"));
        }
        else if ("scroll".equals(action)) screen.mouseScrolled(screen.getWidth() / 2.0, ((Number) reflect(screen, "bodyTop")).doubleValue() + 10.0, command.get("amount").getAsDouble());
        else if ("refresh".equals(action)) { controller.onOpen(); controller.refreshInfo(); }
        else if ("create".equals(action)) controller.create(text(command, "name"), text(command, "password"), command.has("persistent") && command.get("persistent").getAsBoolean(), permissions(command));
        else if ("join".equals(action)) {
            String id = text(command, "id");
            GroupSnapshot group = controller.getState().getBrowse().getGroups().stream().filter(g -> g.getId().toString().equals(id)).findFirst().orElseThrow(() -> new AssertionError("Group not visible: " + id));
            controller.join(group, text(command, "password"));
        }
        else if ("leave".equals(action)) controller.leave();
        else if ("invite".equals(action)) controller.invite(text(command, "player"));
        else if ("transfer".equals(action)) controller.transfer(text(command, "player"));
        else if ("kick".equals(action)) controller.kick(text(command, "player"));
        else if ("ban".equals(action)) controller.ban(text(command, "player"));
        else if ("unban".equals(action)) controller.unban(text(command, "player"));
        else if ("delete".equals(action)) controller.delete();
        else if ("settings".equals(action)) {
            List<GuiMessage.C2S> changes = new ArrayList<GuiMessage.C2S>();
            if (command.has("name")) changes.add(new GuiMessage.C2S.SetName(text(command, "name")));
            if (command.has("password")) changes.add(new GuiMessage.C2S.SetPassword(text(command, "password")));
            if (command.has("persistent")) changes.add(new GuiMessage.C2S.SetPersistent(command.get("persistent").getAsBoolean()));
            if (command.has("permissions")) changes.add(new GuiMessage.C2S.SetPermissions(permissions(command)));
            controller.saveSettings(changes);
        }
        else if ("snapshot".equals(action)) { /* Snapshot is written after every request. */ }
        else throw new IllegalArgumentException("Unknown action: " + action);
    }

    private Object reflect(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private List<GuiAbstractWidget> widgets() {
        List<GuiAbstractWidget> result = new ArrayList<GuiAbstractWidget>();
        collect(screen, result);
        return result;
    }

    private void collect(GuiWidgetListener listener, List<GuiAbstractWidget> result) {
        if (listener instanceof GuiAbstractWidget) result.add((GuiAbstractWidget) listener);
        if (listener instanceof GuiScreenListener) ((GuiScreenListener) listener).widgets().forEach(child -> collect(child, result));
    }
}
