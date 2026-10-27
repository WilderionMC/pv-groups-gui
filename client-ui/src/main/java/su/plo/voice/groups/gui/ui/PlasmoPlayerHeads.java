package su.plo.voice.groups.gui.ui;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import su.plo.lib.mod.client.render.gui.GuiRenderContext;
import su.plo.lib.mod.client.render.texture.ModPlayerSkins;
import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon;

/** The texture type changes with Minecraft mappings; keep that type out of shared bytecode. */
public final class PlasmoPlayerHeads {
    private static final Method GET_SKIN;
    private static final Method GET_DEFAULT_SKIN;
    private static final Method BLIT;

    static {
        try {
            GET_SKIN = ModPlayerSkins.class.getMethod("getSkin", UUID.class, String.class);
            GET_DEFAULT_SKIN = ModPlayerSkins.class.getMethod("getDefaultSkin", UUID.class);
            BLIT = Arrays.stream(GuiRenderContext.class.getMethods())
                    .filter(method -> method.getName().equals("blit") && method.getParameterCount() == 11)
                    .filter(method -> method.getParameterTypes()[0] == GET_SKIN.getReturnType())
                    .findFirst().orElseThrow(() -> new IllegalStateException("Missing Plasmo skin blit"));
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private PlasmoPlayerHeads() { }

    public static void load(UUID id, String name) {
        PlasmoVoiceClient client = client();
        if (client != null && client.getServerConnection().isPresent()) ModPlayerSkins.loadSkin(id, name, client.getBackgroundExecutor());
    }

    public static void render(GuiRenderContext context, UUID id, String name, int x, int y) {
        render(context, id, name, x, y, 16);
    }

    public static void render(GuiRenderContext context, UUID id, String name, int x, int y, int size) {
        try {
            PlasmoVoiceClient client = client();
            Object texture = client != null && client.getServerConnection().isPresent()
                    ? GET_SKIN.invoke(null, id, name) : GET_DEFAULT_SKIN.invoke(null, id);
            BLIT.invoke(context, texture, x, y, size, size, 8f, 8f, 8, 8, 64, 64);
            BLIT.invoke(context, texture, x, y, size, size, 40f, 8f, 8, 8, 64, 64);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Plasmo Voice skin rendering failed", exception);
        }
    }

    private static PlasmoVoiceClient client() {
        GroupsGuiClientAddon addon = GroupsGuiClientAddon.getINSTANCE();
        return addon == null ? null : addon.plasmoVoiceClient();
    }
}
