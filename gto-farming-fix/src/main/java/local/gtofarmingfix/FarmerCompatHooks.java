package local.gtofarmingfix;

import de.maxhenkel.easyvillagers.blocks.tileentity.FarmerTileentity;
import de.maxhenkel.easyvillagers.entity.EasyVillagerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FarmerCompatHooks {
    private static final String DATA_LAST_CROP = "gto_farming_fix_last_crop";
    private static final String DATA_PROGRESS = "gto_farming_fix_progress";
    private static final String DATA_TEA_STAGE = "gto_farming_fix_tea_stage";

    private static final Field CROP_FIELD = findCropField();

    private static final Map<String, String> SPECIAL_SEEDS = new HashMap<>();
    private static final Map<String, String> SEEDS_BY_CROP = new HashMap<>();

    static {
        seed("minecraft:nether_wart", "minecraft:nether_wart");
        seed("minecraft:cocoa_beans", "minecraft:cocoa");
        seed("minecraft:sugar_cane", "minecraft:sugar_cane");
        seed("minecraft:brown_mushroom", "minecraft:brown_mushroom");
        seed("minecraft:red_mushroom", "minecraft:red_mushroom");
        seed("minecraft:sweet_berries", "minecraft:sweet_berry_bush");
        seed("minecraft:glow_berries", "minecraft:cave_vines");
        seed("minecraft:pumpkin_seeds", "minecraft:pumpkin_stem");
        seed("minecraft:melon_seeds", "minecraft:melon_stem");

        seed("farmersdelight:cabbage_seeds", "farmersdelight:cabbages");
        seed("farmersdelight:onion", "farmersdelight:onions");
        seed("farmersdelight:tomato_seeds", "farmersdelight:tomatoes");
        seed("farmersdelight:rice", "farmersdelight:rice");

        seed("ars_nouveau:magebloom_seed", "ars_nouveau:magebloom_crop");
        seed("ars_nouveau:sourceberry_bush", "ars_nouveau:sourceberry_bush");
        seed("biomeswevegone:blueberries", "biomeswevegone:blueberry_bush");
        seed("farmersrespite:tea_seeds", "farmersrespite:small_tea_bush");

        SEEDS_BY_CROP.put("farmersdelight:rice_panicles", "farmersdelight:rice");
    }

    private FarmerCompatHooks() {
    }

    public static void validateInstallation() {
        Block farmerBlock = block("easy_villagers:farmer");
        Item tomatoSeeds = item("farmersdelight:tomato_seeds");
        if (farmerBlock == null || tomatoSeeds == null) {
            throw new IllegalStateException("Required Easy Villagers/Farmer's Delight registrations are missing");
        }

        FarmerTileentity probe = new FarmerTileentity(BlockPos.ZERO, farmerBlock.defaultBlockState());
        BlockState mappedTomato = probe.getSeedCrop(tomatoSeeds);
        if (mappedTomato == null || !id(mappedTomato.getBlock()).equals("farmersdelight:tomatoes")) {
            throw new IllegalStateException("Easy Villagers getSeedCrop hook did not map tomato seeds to a fruiting vine");
        }

        for (Map.Entry<String, String> entry : SPECIAL_SEEDS.entrySet()) {
            if (item(entry.getKey()) == null) {
                throw new IllegalStateException("Missing configured farming item: " + entry.getKey());
            }
            if (block(entry.getValue()) == null) {
                throw new IllegalStateException("Missing configured farming block: " + entry.getValue());
            }
        }
    }

    private static void seed(String itemId, String blockId) {
        SPECIAL_SEEDS.put(itemId, blockId);
        SEEDS_BY_CROP.put(blockId, itemId);
    }

    public static BlockState getSpecialSeedCrop(FarmerTileentity farmer, Item item) {
        String blockId = SPECIAL_SEEDS.get(id(item));
        if (blockId == null) {
            return null;
        }
        Block block = block(blockId);
        return block == null ? null : block.defaultBlockState();
    }

    /**
     * @return null when Easy Villagers should use its original crop logic.
     */
    public static Boolean ageSpecialCrop(FarmerTileentity farmer, EasyVillagerEntity villager) {
        BlockState crop = farmer.getCrop();
        if (crop == null) {
            return null;
        }

        String cropId = id(crop.getBlock());
        if (!isSpecialGrowth(cropId)) {
            return null;
        }

        if (villager == null || villager.isBaby()
                || villager.getVillagerData().getProfession() != VillagerProfession.FARMER) {
            return Boolean.FALSE;
        }
        if (!(farmer.getLevel() instanceof ServerLevel)) {
            return Boolean.FALSE;
        }

        prepareProgress(farmer, cropId);

        if (cropId.equals("minecraft:pumpkin_stem")) {
            return ageStem(farmer, crop, "minecraft:pumpkin");
        }
        if (cropId.equals("minecraft:melon_stem")) {
            return ageStem(farmer, crop, "minecraft:melon");
        }
        if (cropId.equals("farmersdelight:rice") || cropId.equals("farmersdelight:rice_panicles")) {
            return ageRice(farmer, crop, cropId);
        }
        if (isBerryBush(cropId)) {
            return ageBerryBush(farmer, crop);
        }
        if (cropId.equals("farmersrespite:small_tea_bush")) {
            return ageTea(farmer);
        }
        if (cropId.equals("minecraft:sugar_cane")) {
            return ageTimedSingle(farmer, "minecraft:sugar_cane", 2, 3);
        }
        if (cropId.equals("minecraft:brown_mushroom")) {
            return ageTimedSingle(farmer, "minecraft:brown_mushroom", 1, 4);
        }
        if (cropId.equals("minecraft:red_mushroom")) {
            return ageTimedSingle(farmer, "minecraft:red_mushroom", 1, 4);
        }
        if (cropId.equals("minecraft:cave_vines")) {
            return ageTimedSingle(farmer, "minecraft:glow_berries", 1, 4);
        }
        return null;
    }

    private static boolean isSpecialGrowth(String cropId) {
        return cropId.equals("minecraft:pumpkin_stem")
                || cropId.equals("minecraft:melon_stem")
                || cropId.equals("farmersdelight:rice")
                || cropId.equals("farmersdelight:rice_panicles")
                || cropId.equals("minecraft:sweet_berry_bush")
                || cropId.equals("ars_nouveau:sourceberry_bush")
                || cropId.equals("biomeswevegone:blueberry_bush")
                || cropId.equals("farmersrespite:small_tea_bush")
                || cropId.equals("minecraft:sugar_cane")
                || cropId.equals("minecraft:brown_mushroom")
                || cropId.equals("minecraft:red_mushroom")
                || cropId.equals("minecraft:cave_vines");
    }

    private static boolean isBerryBush(String cropId) {
        return cropId.equals("minecraft:sweet_berry_bush")
                || cropId.equals("ars_nouveau:sourceberry_bush")
                || cropId.equals("biomeswevegone:blueberry_bush");
    }

    private static boolean ageStem(FarmerTileentity farmer, BlockState crop, String fruitBlockId) {
        IntegerProperty age = ageProperty(crop);
        if (age == null) {
            return false;
        }
        int current = crop.getValue(age);
        int maximum = maximum(age);
        if (current < maximum) {
            setCropState(farmer, crop.setValue(age, current + 1));
            return true;
        }

        Block fruit = block(fruitBlockId);
        if (fruit == null) {
            return false;
        }
        insertAll(farmer, drops(farmer, fruit.defaultBlockState(), ItemStack.EMPTY));
        setCropState(farmer, crop.setValue(age, 0));
        return true;
    }

    private static boolean ageRice(FarmerTileentity farmer, BlockState crop, String cropId) {
        IntegerProperty age = ageProperty(crop);
        if (age == null) {
            return false;
        }
        int current = crop.getValue(age);
        int maximum = maximum(age);
        if (current < maximum) {
            setCropState(farmer, crop.setValue(age, current + 1));
            return true;
        }

        if (cropId.equals("farmersdelight:rice")) {
            Block panicles = block("farmersdelight:rice_panicles");
            if (panicles == null) {
                return false;
            }
            setCropState(farmer, panicles.defaultBlockState());
            return true;
        }

        insertAll(farmer, drops(farmer, crop, ItemStack.EMPTY));
        Block rice = block("farmersdelight:rice");
        if (rice != null) {
            setCropState(farmer, rice.defaultBlockState());
        }
        return true;
    }

    private static boolean ageBerryBush(FarmerTileentity farmer, BlockState crop) {
        IntegerProperty age = ageProperty(crop);
        if (age == null) {
            return false;
        }
        int current = crop.getValue(age);
        int maximum = maximum(age);
        if (current < maximum) {
            setCropState(farmer, crop.setValue(age, current + 1));
            return true;
        }

        String berryItemId = switch (id(crop.getBlock())) {
            case "minecraft:sweet_berry_bush" -> "minecraft:sweet_berries";
            case "ars_nouveau:sourceberry_bush" -> "ars_nouveau:sourceberry_bush";
            case "biomeswevegone:blueberry_bush" -> "biomeswevegone:blueberries";
            default -> null;
        };
        if (berryItemId == null) {
            return false;
        }
        insert(farmer, stack(berryItemId, 2 + random(farmer).nextInt(2)));
        int resetAge = Math.min(1, maximum);
        setCropState(farmer, crop.setValue(age, resetAge));
        return true;
    }

    private static boolean ageTea(FarmerTileentity farmer) {
        if (!advanceTimer(farmer, 4)) {
            return true;
        }
        int stage = farmer.getPersistentData().contains(DATA_TEA_STAGE)
                ? farmer.getPersistentData().getInt(DATA_TEA_STAGE)
                : 3;
        String leafId;
        if (stage == 0) {
            leafId = "farmersrespite:green_tea_leaves";
        } else if (stage == 1) {
            leafId = "farmersrespite:yellow_tea_leaves";
        } else {
            leafId = "farmersrespite:black_tea_leaves";
        }
        int leaves = 2 + random(farmer).nextInt(2);
        int sticks = 2 + random(farmer).nextInt(2);
        insert(farmer, stack(leafId, leaves));
        insert(farmer, stack("minecraft:stick", sticks));
        return true;
    }

    private static boolean ageTimedSingle(FarmerTileentity farmer, String itemId, int count, int cycles) {
        if (advanceTimer(farmer, cycles)) {
            insert(farmer, stack(itemId, count));
        }
        return true;
    }

    private static boolean advanceTimer(FarmerTileentity farmer, int cycles) {
        CompoundTag data = farmer.getPersistentData();
        int next = data.getInt(DATA_PROGRESS) + 1;
        if (next < cycles) {
            data.putInt(DATA_PROGRESS, next);
            return false;
        }
        data.putInt(DATA_PROGRESS, 0);
        return true;
    }

    private static void prepareProgress(FarmerTileentity farmer, String cropId) {
        CompoundTag data = farmer.getPersistentData();
        if (!cropId.equals(data.getString(DATA_LAST_CROP))) {
            data.putString(DATA_LAST_CROP, cropId);
            data.putInt(DATA_PROGRESS, 0);
        }
    }

    public static void clearProgress(FarmerTileentity farmer) {
        CompoundTag data = farmer.getPersistentData();
        data.remove(DATA_LAST_CROP);
        data.remove(DATA_PROGRESS);
        data.remove(DATA_TEA_STAGE);
        farmer.setChanged();
    }

    public static String seedForCrop(BlockState crop) {
        return crop == null ? null : SEEDS_BY_CROP.get(id(crop.getBlock()));
    }

    public static String teaMessageFor(Item item) {
        String itemId = id(item);
        if (itemId.equals("farmersrespite:green_tea_leaves")) {
            return "message.gto_farming_fix.tea.green";
        }
        if (itemId.equals("farmersrespite:yellow_tea_leaves")) {
            return "message.gto_farming_fix.tea.yellow";
        }
        if (itemId.equals("farmersrespite:black_tea_leaves")) {
            return "message.gto_farming_fix.tea.black";
        }
        return null;
    }

    public static void selectTeaStage(FarmerTileentity farmer, Item item) {
        String itemId = id(item);
        int stage = itemId.equals("farmersrespite:green_tea_leaves") ? 0
                : itemId.equals("farmersrespite:yellow_tea_leaves") ? 1 : 3;
        farmer.getPersistentData().putInt(DATA_TEA_STAGE, stage);
        farmer.setChanged();
    }

    public static boolean isTeaCrop(BlockState crop) {
        return crop != null && id(crop.getBlock()).equals("farmersrespite:small_tea_bush");
    }

    private static List<ItemStack> drops(FarmerTileentity farmer, BlockState state, ItemStack tool) {
        ServerLevel level = (ServerLevel) farmer.getLevel();
        BlockPos pos = farmer.getBlockPos();
        LootParams.Builder params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withParameter(LootContextParams.TOOL, tool);
        return state.getDrops(params);
    }

    private static void insertAll(FarmerTileentity farmer, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            insert(farmer, stack);
        }
    }

    private static void insert(FarmerTileentity farmer, ItemStack input) {
        if (input.isEmpty()) {
            return;
        }
        ItemStack remaining = input.copy();
        Container inventory = farmer.getOutputInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty()) {
                int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                ItemStack placed = remaining.copy();
                placed.setCount(moved);
                inventory.setItem(slot, placed);
                remaining.shrink(moved);
            } else if (ItemStack.isSameItemSameTags(existing, remaining)) {
                int limit = Math.min(inventory.getMaxStackSize(), existing.getMaxStackSize());
                int moved = Math.min(remaining.getCount(), limit - existing.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    remaining.shrink(moved);
                    inventory.setItem(slot, existing);
                }
            }
        }
        inventory.setChanged();
    }

    private static ItemStack stack(String itemId, int count) {
        Item item = item(itemId);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    private static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integerProperty && property.getName().equals("age")) {
                return integerProperty;
            }
        }
        return null;
    }

    private static int maximum(IntegerProperty property) {
        return property.getPossibleValues().stream().max(Integer::compareTo).orElse(0);
    }

    private static void setCropState(FarmerTileentity farmer, BlockState state) {
        try {
            CROP_FIELD.set(farmer, state);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not update Easy Villagers farmer crop", exception);
        }
    }

    private static Field findCropField() {
        try {
            Field field = FarmerTileentity.class.getDeclaredField("crop");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static net.minecraft.util.RandomSource random(FarmerTileentity farmer) {
        return farmer.getLevel().random;
    }

    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : ForgeRegistries.ITEMS.getValue(key);
    }

    private static Block block(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : ForgeRegistries.BLOCKS.getValue(key);
    }

    private static String id(Item item) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key == null ? "" : key.toString();
    }

    private static String id(Block block) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        return key == null ? "" : key.toString();
    }
}
