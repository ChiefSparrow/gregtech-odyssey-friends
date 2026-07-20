function initializeCoreMod() {
    var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

    var OWNER = 'com/gtocore/integration/ae/SimpleCraftingTerminal';

    function nextRealInstruction(instruction) {
        var current = instruction.getNext();
        while (current !== null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    return {
        'gto_terminal_detach_removed_inventory': {
            'target': {
                'type': 'METHOD',
                'class': 'com.gtocore.integration.ae.SimpleCraftingTerminal',
                'methodName': 'updateTarget',
                'methodDesc': '()V'
            },
            'transformer': function (method) {
                // A target change invalidates wrappers created for the previous block entity.
                var resetStrategies = new InsnList();
                resetStrategies.add(new VarInsnNode(Opcodes.ALOAD, 0));
                resetStrategies.add(new InsnNode(Opcodes.ACONST_NULL));
                resetStrategies.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    OWNER,
                    'externalStorageStrategies',
                    'Ljava/util/Map;'
                ));
                method.instructions.insert(resetStrategies);

                var instruction = method.instructions.getFirst();
                while (instruction !== null) {
                    if (instruction.getOpcode() === Opcodes.ALOAD && instruction.var === 3) {
                        var jump = nextRealInstruction(instruction);
                        var oldReturn = jump === null ? null : nextRealInstruction(jump);

                        if (jump !== null && jump.getOpcode() === Opcodes.IFNONNULL &&
                            oldReturn !== null && oldReturn.getOpcode() === Opcodes.RETURN) {
                            // No adjacent block entity: detach the terminal from the stale delegate.
                            var detach = new InsnList();
                            detach.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            detach.add(new FieldInsnNode(
                                Opcodes.GETFIELD,
                                OWNER,
                                'handler',
                                'Lappeng/me/storage/MEInventoryHandler;'
                            ));
                            detach.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                'appeng/me/storage/NullInventory',
                                'of',
                                '()Lappeng/api/storage/MEStorage;',
                                false
                            ));
                            detach.add(new MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL,
                                'appeng/me/storage/MEInventoryHandler',
                                'setDelegate',
                                '(Lappeng/api/storage/MEStorage;)V',
                                false
                            ));
                            detach.add(new InsnNode(Opcodes.RETURN));

                            method.instructions.insertBefore(oldReturn, detach);
                            method.instructions.remove(oldReturn);
                            ASMAPI.log('INFO', '[GTO Terminal Fix] Patched SimpleCraftingTerminal.updateTarget');
                            return method;
                        }
                    }
                    instruction = instruction.getNext();
                }

                throw new Error('[GTO Terminal Fix] Expected updateTarget bytecode was not found; refusing a silent partial patch');
            }
        }
    };
}
