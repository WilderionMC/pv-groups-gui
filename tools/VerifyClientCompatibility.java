import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.nio.file.Path;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Checks real published Plasmo binaries without loading Minecraft or starting a client/server. */
class VerifyClientCompatibility {
    private static final String OWN = "su/plo/voice/groups/gui/";
    private static boolean plasmo(String name) {
        return name.startsWith("su/plo/") && !name.startsWith(OWN);
    }

    private static Map<String, ClassModel> classes(JarFile jar) throws IOException {
        var result = new HashMap<String, ClassModel>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("su/plo/")) continue;
            try (var stream = jar.getInputStream(entry)) {
                var model = ClassFile.of().parse(stream.readAllBytes());
                result.put(model.thisClass().asInternalName(), model);
            }
        }
        return result;
    }

    private static boolean member(Map<String, ClassModel> classes, String owner, String name, String type,
                                  boolean field, Set<String> visited) {
        if (!visited.add(owner)) return false;
        var model = classes.get(owner);
        if (model == null) {
            if (!owner.startsWith("java/")) return false;
            try {
                var standard = Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                if (field) {
                    for (var candidate : standard.getFields()) {
                        if (candidate.getName().equals(name) && candidate.getType().descriptorString().equals(type)) return true;
                    }
                } else {
                    for (var candidate : standard.getMethods()) {
                        var descriptor = MethodType.methodType(candidate.getReturnType(), candidate.getParameterTypes()).descriptorString();
                        if (candidate.getName().equals(name) && descriptor.equals(type)) return true;
                    }
                }
            } catch (ClassNotFoundException ignored) { }
            return false;
        }
        if (field && model.fields().stream().anyMatch(f -> f.fieldName().equalsString(name) && f.fieldType().equalsString(type))) return true;
        if (!field && model.methods().stream().anyMatch(m -> m.methodName().equalsString(name) && m.methodType().equalsString(type))) return true;
        if (name.equals("<init>")) return false;
        for (var parent : model.interfaces()) {
            if (member(classes, parent.asInternalName(), name, type, field, visited)) return true;
        }
        return model.superclass().isPresent() && member(classes, model.superclass().get().asInternalName(), name, type, field, visited);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Expected artifact directory, addon version and pinned Plasmo JARs");
        int count = 0;
        for (int index = 2; index < args.length; index++) {
            var pvPath = Path.of(args[index]);
            var target = pvPath.getFileName().toString().replace("-2.1.17.jar", "");
            var addonPath = Path.of(args[0], "pv-addon-groups-gui-" + target + "-" + args[1] + ".jar");
            try (var pv = new JarFile(pvPath.toFile()); var addon = new JarFile(addonPath.toFile())) {
                var all = classes(pv);
                var own = classes(addon);
                if (own.keySet().stream().anyMatch(name -> !name.startsWith(OWN))) {
                    throw new AssertionError(target + ": Plasmo classes were bundled into the addon");
                }
                if (addon.stream().anyMatch(entry -> entry.getName().startsWith("net/minecraft/"))) {
                    throw new AssertionError(target + ": Minecraft classes were bundled into the addon");
                }
                all.putAll(own);
                if (!all.get("su/plo/lib/mod/client/gui/screen/GuiScreen").fields().stream().anyMatch(f -> f.fieldName().equalsString("minecraft"))) {
                    throw new AssertionError(target + ": missing client executor field");
                }
                if (!all.get("su/plo/voice/proto/packets/tcp/PacketTcpCodec").fields().stream().anyMatch(f -> f.fieldName().equalsString("PACKETS"))) {
                    throw new AssertionError(target + ": missing packet registry field");
                }
                var skin = all.get("su/plo/lib/mod/client/render/texture/ModPlayerSkins").methods().stream()
                        .filter(m -> m.methodName().equalsString("getSkin") && m.methodType().stringValue().startsWith("(Ljava/util/UUID;Ljava/lang/String;)"))
                        .findFirst().orElseThrow(() -> new AssertionError(target + ": missing skin getter"));
                var textureType = skin.methodType().stringValue().substring(skin.methodType().stringValue().indexOf(')') + 1);
                if (!all.get("su/plo/lib/mod/client/render/texture/ModPlayerSkins").methods().stream().anyMatch(m ->
                        m.methodName().equalsString("getDefaultSkin") && m.methodType().equalsString("(Ljava/util/UUID;)" + textureType))) {
                    throw new AssertionError(target + ": missing offline skin getter");
                }
                if (!all.get("su/plo/lib/mod/client/render/gui/GuiRenderContext").methods().stream().anyMatch(m ->
                        m.methodName().equalsString("blit") && m.methodType().equalsString("(" + textureType + "IIIIFFIIII)V"))) {
                    throw new AssertionError(target + ": missing skin blit");
                }
                int references = 0;
                for (var model : own.values()) {
                    if (model.majorVersion() > 52) throw new AssertionError("Not Java 8: " + model.thisClass().asInternalName());
                    for (var entry : model.constantPool()) {
                        if (entry instanceof Utf8Entry text && (text.stringValue().contains("net/minecraft/") || text.stringValue().contains("org/lwjgl/"))) {
                            throw new AssertionError("Minecraft mapping leaked into " + model.thisClass().asInternalName() + ": " + text.stringValue());
                        }
                        if (entry instanceof ClassEntry type && plasmo(type.asInternalName()) && !all.containsKey(type.asInternalName())) {
                            throw new AssertionError(target + ": missing class " + type.asInternalName());
                        }
                        if (entry instanceof MemberRefEntry ref && (plasmo(ref.owner().asInternalName()) || ref.owner().asInternalName().startsWith(OWN))) {
                            references++;
                            if (!member(all, ref.owner().asInternalName(), ref.name().stringValue(), ref.type().stringValue(), ref instanceof FieldRefEntry, new HashSet<>())) {
                                throw new AssertionError(target + ": missing " + ref.owner().asInternalName() + "." + ref.name().stringValue() + ref.type().stringValue());
                            }
                        }
                    }
                }
                if (addon.getJarEntry("assets/pv-addon-groups-gui/icon.png") == null) throw new AssertionError(target + ": missing icon");
                if (!target.startsWith("fabric-") && addon.getEntry("pack.mcmeta") == null) throw new AssertionError(target + ": missing resource pack metadata");
                if (addon.stream().anyMatch(e -> e.getName().contains("owo"))) throw new AssertionError(target + ": owo dependency remains");
                var metadata = target.startsWith("fabric-") ? "fabric.mod.json" : target.startsWith("neoforge-") ? "META-INF/neoforge.mods.toml" : "META-INF/mods.toml";
                if (addon.getJarEntry(metadata) == null) throw new AssertionError(target + ": missing loader metadata");
                var wrongEntry = target.startsWith("fabric-") ? "ForgeGroupsGuiMod" : "FabricGroupsGuiMod";
                if (own.keySet().stream().anyMatch(n -> n.contains(wrongEntry))) throw new AssertionError(target + ": wrong loader entrypoint");
                System.out.println(target + ": " + references + " references checked, Java 8, no Minecraft mappings or owo");
                count++;
            }
        }
        System.out.println("PASS: " + count + " client distributions");
    }
}
