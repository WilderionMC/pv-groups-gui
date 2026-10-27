package su.plo.voice.groups.gui.ui;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod("pv_groups_gui")
public final class ForgeGroupsGuiMod {
    @Mod.EventBusSubscriber(modid = "pv_groups_gui", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ClientSetup {
        @SubscribeEvent
        public static void initialize(FMLClientSetupEvent event) {
            event.enqueueWork(() -> PlasmoClientRuntime.INSTANCE.load());
        }
    }
}
