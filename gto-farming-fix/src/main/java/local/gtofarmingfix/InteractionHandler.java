package local.gtofarmingfix;

import de.maxhenkel.easyvillagers.blocks.tileentity.FarmerTileentity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(
        modid = GTOFarmingFix.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class InteractionHandler {
    private static final Map<String, String> REPLANT_ITEMS = new HashMap<>();

    static {
        REPLANT_ITEMS.put("minecraft:wheat", "minecraft:wheat_seeds");
        REPLANT_ITEMS.put("minecraft:carrots", "minecraft:carrot");
        REPLANT_ITEMS.put("minecraft:potatoes", "minecraft:potato");
        REPLANT_ITEMS.put("minecraft:beetroots", "minecraft:beetroot_seeds");
        REPLANT_ITEMS.put("minecraft:nether_wart", "minecraft:nether_wart");
        REPLANT_ITEMS.put("minecraft:cocoa", "minecraft:cocoa_beans");
        REPLANT_ITEMS.put("farmersdelight:cabbages", "farmersdelight:cabbage_seeds");
        REPLANT_ITEMS.put("farmersdelight:onions", "farmersdelight:onion");
        REPLANT_ITEMS.put("farmersdelight:tomatoes", "farmersdelight:tomato_seeds");
        REPLANT_ITEMS.put("ars_nouveau:magebloom_crop", "ars_nouveau:magebloom_seed");
    }

    private InteractionHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide || event.getUseBlock() == Event.Result.DENY) {
            return;
        }

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (blockEntity instanceof FarmerTileentity farmer) {
            if (handleFarmerInteraction(event, farmer)) {
                return;
            }
        }

        if (event.getEntity().isSecondaryUseActive()) {
            return;
        }
        harvestMatureCrop(event, (ServerLevel) level, pos, state);
    }

    private static boolean handleFarmerInteraction(PlayerInteractEvent.RightClickBlock event,
                                                    FarmerTileentity farmer) {
        if (event.getEntity().isSecondaryUseActive() && farmer.getCrop() != null) {
            String seedId = FarmerCompatHooks.seedForCrop(farmer.getCrop());
            Item seed = item(seedId);
            if (seed != null) {
                FarmerCompatHooks.clearProgress(farmer);
                farmer.setCrop(null);
                ItemStack returned = new ItemStack(seed);
                ItemStack held = event.getEntity().getItemInHand(event.getHand());
                if (held.isEmpty()) {
                    event.getEntity().setItemInHand(event.getHand(), returned);
                } else if (!event.getEntity().addItem(returned)) {
                    event.getEntity().drop(returned, false);
                }
                succeed(event);
                return true;
            }
        }

        if (!event.getEntity().isSecondaryUseActive() && FarmerCompatHooks.isTeaCrop(farmer.getCrop())) {
            Item held = event.getEntity().getItemInHand(event.getHand()).getItem();
            String message = FarmerCompatHooks.teaMessageFor(held);
            if (message != null) {
                FarmerCompatHooks.selectTeaStage(farmer, held);
                event.getEntity().displayClientMessage(Component.translatable(message), true);
                succeed(event);
                return true;
            }
        }
        return false;
    }

    private static void harvestMatureCrop(PlayerInteractEvent.RightClickBlock event, ServerLevel level,
                                          BlockPos pos, BlockState state) {
        String blockId = id(state.getBlock());
        String replantItemId = REPLANT_ITEMS.get(blockId);
        if (replantItemId == null) {
            return;
        }

        IntegerProperty age = ageProperty(state);
        if (age == null) {
            return;
        }
        int current = state.getValue(age);
        int maximum = age.getPossibleValues().stream().max(Integer::compareTo).orElse(0);
        if (current < maximum) {
            return;
        }

        ItemStack held = event.getEntity().getItemInHand(event.getHand());
        if (!event.getEntity().mayUseItemAt(pos, event.getFace(), held)) {
            return;
        }

        List<ItemStack> drops = Block.getDrops(
                state,
                level,
                pos,
                level.getBlockEntity(pos),
                event.getEntity(),
                held.copy()
        );
        removeOne(drops, item(replantItemId));

        int minimum = age.getPossibleValues().stream().min(Integer::compareTo).orElse(0);
        BlockState replanted = state.setValue(age, minimum);
        if (!level.setBlock(pos, replanted, Block.UPDATE_CLIENTS)) {
            return;
        }

        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                Block.popResource(level, pos, drop);
            }
        }
        level.levelEvent(2001, pos, Block.getId(state));
        level.gameEvent(event.getEntity(), GameEvent.BLOCK_DESTROY, pos);
        succeed(event);
    }

    private static void removeOne(List<ItemStack> drops, Item plantingItem) {
        if (plantingItem == null) {
            return;
        }
        for (ItemStack drop : drops) {
            if (drop.is(plantingItem) && !drop.isEmpty()) {
                drop.shrink(1);
                return;
            }
        }
    }

    private static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integerProperty && property.getName().equals("age")) {
                return integerProperty;
            }
        }
        return null;
    }

    private static void succeed(PlayerInteractEvent.RightClickBlock event) {
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static Item item(String itemId) {
        ResourceLocation key = itemId == null ? null : ResourceLocation.tryParse(itemId);
        return key == null ? null : ForgeRegistries.ITEMS.getValue(key);
    }

    private static String id(Block block) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        return key == null ? "" : key.toString();
    }
}
