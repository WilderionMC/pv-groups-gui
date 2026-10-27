package su.plo.voice.groups.gui.ui;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;

/** Runs only in a disposable matrix client, never in a distributable mod. */
public final class MatrixTestAgent {
    public static void premain(String argument, final Instrumentation instrumentation) {
        Thread worker = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 120000;
                while (System.currentTimeMillis() < deadline) {
                    boolean initialized = false;
                    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                        if (type.getName().equals("su.plo.voice.groups.gui.client.GroupsGuiClientAddon")) {
                            initialized = type.getMethod("getINSTANCE").invoke(null) != null;
                            break;
                        }
                    }
                    if (!initialized) { Thread.sleep(100); continue; }
                    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                        if (!type.getName().equals("su.plo.lib.mod.client.gui.screen.GuiScreen")) continue;
                        URL jar = MatrixTestAgent.class.getProtectionDomain().getCodeSource().getLocation();
                        URLClassLoader tests = new URLClassLoader(new URL[] {jar}, type.getClassLoader()) {
                            protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                                if (name.startsWith("su.plo.voice.groups.gui.ui.MatrixUiDriver") || name.startsWith("su.plo.voice.groups.gui.ui.UiScenarios")) {
                                    Class<?> found = findLoadedClass(name);
                                    if (found == null) found = findClass(name);
                                    if (resolve) resolveClass(found);
                                    return found;
                                }
                                return super.loadClass(name, resolve);
                            }
                        };
                        Class<?> runner = tests.loadClass("su.plo.voice.groups.gui.ui.MatrixUiDriver");
                        runner.getMethod("run").invoke(null);
                        return;
                    }
                    Thread.sleep(100);
                }
                throw new AssertionError("Plasmo UI did not load within 120 seconds");
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
        }, "PV UI matrix test");
        worker.setDaemon(true);
        worker.start();
    }
}
