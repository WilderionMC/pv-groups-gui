package su.plo.voice.groups.gui.ui;

import su.plo.lib.mod.client.gui.screen.GuiScreen;
import su.plo.lib.mod.client.gui.screen.ScreenWrapper;
import su.plo.lib.mod.client.gui.widget.GuiWidgetListener;
import su.plo.lib.mod.client.render.RenderUtil;
import su.plo.lib.mod.client.render.gui.GuiRenderContext;
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon;
import su.plo.voice.groups.gui.protocol.GuiLang;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** Native Plasmo event routing and rendering on old Fabric/Forge/NeoForge clients. No OS input. */
public final class MatrixUiDriver implements UiScenarios.Context, UiScenarios.Input {
    private final Executor client;
    private final Object minecraft;
    private final boolean sdl;
    private final AtomicLong ticks = new AtomicLong();
    private final Path output = Paths.get(System.getProperty("pv.ui.matrix.report"));
    private final List<String> passed = new ArrayList<String>();
    private GroupsGuiScreen screen;
    private double mouseX, mouseY;

    private MatrixUiDriver() throws Exception {
        Field field = GuiScreen.class.getDeclaredField("minecraft");
        field.setAccessible(true);
        minecraft = field.get(null);
        client = (Executor) minecraft;
        boolean sdlAvailable;
        try { Class.forName("org.lwjgl.sdl.SDLVideo", false, minecraft.getClass().getClassLoader()); sdlAvailable = true; }
        catch (ClassNotFoundException absent) { sdlAvailable = false; }
        sdl = sdlAvailable;
    }

    public static void run() throws Exception {
        MatrixUiDriver driver = new MatrixUiDriver();
        try {
            driver.awaitReady();
            driver.runOnClient(ignored -> {
                if (!GroupsGuiClientAddon.getINSTANCE().plasmoVoiceClient().getHotkeys().getHotkey(GuiLang.KEY_OPEN).isPresent()) {
                    throw new AssertionError("Production hotkey was not registered");
                }
                // Offline fixture: prevent player skin requests that require a world connection.
                try {
                    Field instance = GroupsGuiClientAddon.class.getDeclaredField("INSTANCE");
                    instance.setAccessible(true);
                    instance.set(null, new GroupsGuiClientAddon());
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            new UiScenarios().run(driver);
            driver.report("passed", null);
            driver.stopClient();
        } catch (Throwable failure) {
            failure.printStackTrace();
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            driver.report("failed", failure + ": " + cause);
            try { driver.stopClient(); }
            catch (Throwable cannotClose) { System.exit(1); }
        }
    }

    private void stopClient() {
        runOnClient(ignored -> {
            if (sdl) {
                try { minecraft.getClass().getMethod("stop").invoke(minecraft); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            } else {
                GLFW.glfwSetWindowShouldClose(GLFW.glfwGetCurrentContext(), true);
            }
        });
    }

    private void awaitReady() throws Exception {
        long deadline = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < deadline) {
            boolean ready = computeOnClient(ignored -> {
                try {
                    return GroupsGuiClientAddon.getINSTANCE() != null
                            && !RenderUtil.getFormattedString(su.plo.slib.api.chat.component.McTextComponent.translatable(GuiLang.BUTTON_CREATE)).contains(GuiLang.PREFIX);
                }
                catch (Throwable notReady) { return false; }
            });
            if (ready) { Thread.sleep(5000); return; }
            Thread.sleep(200);
        }
        throw new AssertionError("Minecraft resources not ready");
    }

    public void runOnClient(Consumer<Object> action) { computeOnClient(ignored -> { action.accept(null); return null; }); }
    public <T> T computeOnClient(Function<Object, T> action) {
        FutureTask<T> task = new FutureTask<T>(() -> action.apply(null));
        client.execute(task);
        try { return task.get(30, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    public void waitTicks(int count) {
        long target = ticks.get() + count;
        long deadline = System.currentTimeMillis() + 30000;
        while (ticks.get() < target) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("UI tick timeout");
            try { Thread.sleep(10); } catch (InterruptedException failure) { throw new RuntimeException(failure); }
        }
    }
    public void openScreen(GroupsGuiScreen screen) {
        this.screen = screen;
        ScreenWrapper.openScreen(new Host(screen));
    }
    public UiScenarios.Input getInput() { return this; }
    public void setCursorPos(double x, double y) { mouseX = x; mouseY = y; }
    public void pressMouse(int button) {
        int nativeButton = sdl ? button + 1 : button;
        runOnClient(ignored -> { screen.mouseClicked(mouseX, mouseY, nativeButton); screen.mouseReleased(mouseX, mouseY, nativeButton); });
    }
    public void typeChars(String text) { runOnClient(ignored -> { for (char c : text.toCharArray()) screen.charTyped(c, 0); }); }
    public void scroll(double amount) { runOnClient(ignored -> screen.mouseScrolled(mouseX, mouseY, amount)); }
    public void resizeWindow(int width, int height) {
        runOnClient(ignored -> {
            if (sdl) {
                try {
                    Object window = minecraft.getClass().getMethod("getWindow").invoke(minecraft);
                    window.getClass().getMethod("setWindowed", int.class, int.class).invoke(window, width, height);
                } catch (Exception failure) { throw new RuntimeException(failure); }
                return;
            }
            long window = GLFW.glfwGetCurrentContext();
            if (window == 0) throw new AssertionError("No GLFW context on client thread");
            GLFW.glfwSetWindowSize(window, width, height);
        });
    }
    public void assertSymbols() {
        // Detailed native-font glyph checks remain in the Fabric 26.2 adapter.
        runOnClient(ignored -> {
            for (String symbol : new String[] {"★", "→", "✎"}) {
                if (RenderUtil.getTextWidth(su.plo.slib.api.chat.component.McTextComponent.literal(symbol)) <= 0) throw new AssertionError("Invisible UI symbol");
            }
        });
    }
    public void takeScreenshot(String name) {
        if (sdl) {
            takeModernScreenshot(name);
            if (!name.contains("FAILED")) passed.add(name);
            return;
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (!computeOnClient(ignored -> {
            long window = GLFW.glfwGetCurrentContext();
            IntBuffer width = BufferUtils.createIntBuffer(1), height = BufferUtils.createIntBuffer(1);
            GLFW.glfwGetFramebufferSize(window, width, height);
            if (width.get(0) > 0 && height.get(0) > 0) return true;
            GLFW.glfwRestoreWindow(window);
            return false;
        })) {
            if (System.currentTimeMillis() >= deadline) throw new AssertionError("Window framebuffer remained empty");
            waitTicks(2);
        }
        runOnClient(ignored -> {
            try {
                long window = GLFW.glfwGetCurrentContext();
                IntBuffer width = BufferUtils.createIntBuffer(1), height = BufferUtils.createIntBuffer(1);
                GLFW.glfwGetFramebufferSize(window, width, height);
                int w = width.get(0), h = height.get(0);
                ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
                GL11.glReadBuffer(GL11.GL_FRONT);
                GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int i = (x + y * w) * 4;
                    image.setRGB(x, h - 1 - y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
                }
                Path screenshots = output.getParent().resolve("screenshots");
                Files.createDirectories(screenshots);
                ImageIO.write(image, "png", screenshots.resolve(name + ".png").toFile());
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        if (!name.contains("FAILED")) passed.add(name);
    }

    private void takeModernScreenshot(String name) {
        final CountDownLatch done = new CountDownLatch(1);
        runOnClient(ignored -> {
            try {
                Object renderer = minecraft.getClass().getField("gameRenderer").get(minecraft);
                Object target = renderer.getClass().getMethod("mainRenderTarget").invoke(renderer);
                Class<?> screenshot = Class.forName("net.minecraft.client.Screenshot", true, minecraft.getClass().getClassLoader());
                java.lang.reflect.Method grab = java.util.Arrays.stream(screenshot.getMethods())
                        .filter(m -> m.getName().equals("grab") && m.getParameterTypes().length == 5).findFirst().get();
                grab.invoke(null, output.getParent().toFile(), name + ".png", target, 1, (Consumer<Object>) message -> done.countDown());
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        try {
            if (!done.await(30, TimeUnit.SECONDS) || !Files.exists(output.getParent().resolve("screenshots").resolve(name + ".png"))) {
                throw new AssertionError("Minecraft did not save screenshot " + name);
            }
        } catch (InterruptedException failure) { throw new RuntimeException(failure); }
    }

    private void report(String status, String error) throws Exception {
        Files.createDirectories(output.getParent());
        String json = "{\"status\":\"" + status + "\",\"uiScenarios\":" + passed.size()
                + ",\"input\":\"Plasmo event routing\",\"hotkey\":\"registration only\",\"error\":"
                + (error == null ? "null" : "\"" + error.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"") + "}";
        Files.write(output, json.getBytes(StandardCharsets.UTF_8));
    }

    private final class Host extends GuiScreen {
        private final GroupsGuiScreen delegate;
        Host(GroupsGuiScreen delegate) { this.delegate = delegate; }
        public void init() { delegate.setMinecraftScreen(getMinecraftScreen()); delegate.init(); }
        public void removed() { delegate.removed(); }
        public void tick() { delegate.tick(); ticks.incrementAndGet(); }
        public void render(GuiRenderContext context, int x, int y, float delta) { delegate.render(context, x, y, delta); }
        public List<? extends GuiWidgetListener> widgets() { return delegate.widgets(); }
    }
}
