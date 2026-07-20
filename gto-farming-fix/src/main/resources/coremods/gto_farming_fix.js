function initializeCoreMod() {
    var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
    var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');

    var FARMER = 'de/maxhenkel/easyvillagers/blocks/tileentity/FarmerTileentity';
    var HOOKS = 'local/gtofarmingfix/FarmerCompatHooks';

    return {
        'gto_farmer_special_seed_mapping': {
            'target': {
                'type': 'METHOD',
                'class': 'de.maxhenkel.easyvillagers.blocks.tileentity.FarmerTileentity',
                'methodName': 'getSeedCrop',
                'methodDesc': '(Lnet/minecraft/world/item/Item;)Lnet/minecraft/world/level/block/state/BlockState;'
            },
            'transformer': function (method) {
                var fallback = new LabelNode();
                var patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    HOOKS,
                    'getSpecialSeedCrop',
                    '(L' + FARMER + ';Lnet/minecraft/world/item/Item;)Lnet/minecraft/world/level/block/state/BlockState;',
                    false
                ));
                patch.add(new InsnNode(Opcodes.DUP));
                patch.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
                patch.add(new InsnNode(Opcodes.ARETURN));
                patch.add(fallback);
                patch.add(new InsnNode(Opcodes.POP));
                method.instructions.insert(patch);
                ASMAPI.log('INFO', '[GTO Farming Fix] Patched FarmerTileentity.getSeedCrop');
                return method;
            }
        },
        'gto_farmer_special_crop_growth': {
            'target': {
                'type': 'METHOD',
                'class': 'de.maxhenkel.easyvillagers.blocks.tileentity.FarmerTileentity',
                'methodName': 'ageCrop',
                'methodDesc': '(Lde/maxhenkel/easyvillagers/entity/EasyVillagerEntity;)Z'
            },
            'transformer': function (method) {
                var fallback = new LabelNode();
                var patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    HOOKS,
                    'ageSpecialCrop',
                    '(L' + FARMER + ';Lde/maxhenkel/easyvillagers/entity/EasyVillagerEntity;)Ljava/lang/Boolean;',
                    false
                ));
                patch.add(new InsnNode(Opcodes.DUP));
                patch.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    'java/lang/Boolean',
                    'booleanValue',
                    '()Z',
                    false
                ));
                patch.add(new InsnNode(Opcodes.IRETURN));
                patch.add(fallback);
                patch.add(new InsnNode(Opcodes.POP));
                method.instructions.insert(patch);
                ASMAPI.log('INFO', '[GTO Farming Fix] Patched FarmerTileentity.ageCrop');
                return method;
            }
        }
    };
}
