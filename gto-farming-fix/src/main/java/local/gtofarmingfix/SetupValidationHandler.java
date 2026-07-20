package local.gtofarmingfix;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(
        modid = GTOFarmingFix.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD
)
public final class SetupValidationHandler {
    private SetupValidationHandler() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            FarmerCompatHooks.validateInstallation();
            System.out.println(
                    "[GTO Farming Fix] Easy Villagers compatibility and right-click harvesting validated"
            );
        });
    }
}
