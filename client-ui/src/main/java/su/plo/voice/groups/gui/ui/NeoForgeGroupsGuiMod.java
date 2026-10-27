package su.plo.voice.groups.gui.ui;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = "pv_groups_gui", dist = Dist.CLIENT)
public final class NeoForgeGroupsGuiMod {
    public NeoForgeGroupsGuiMod(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) ->
                event.enqueueWork(() -> PlasmoClientRuntime.INSTANCE.load()));
    }
}
