package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.InsnTool.isIntPush;
import static com.github.mictaege.jitter.plugin.InsnTool.pushInt;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Removing enum constants needs a rewrite of the enum's static initializer:
 * the constructor invocations of the removed constants as well as the
 * synthetic values array have to disappear, and the ordinals of the
 * surviving constants have to be renumbered, as the compiler would do it
 * when the enum is compiled without the removed constants.
 */
final class EnumRewriter {

    private EnumRewriter() {
        super();
    }

    static void rewriteEnums(final StripContext ctx) {
        for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
            if ((node.access & Opcodes.ACC_ENUM) == 0) {
                continue;
            }
            final List<String> survivors = new ArrayList<>();
            final List<String> removed = new ArrayList<>();
            for (final FieldNode field : node.fields) {
                if ((field.access & Opcodes.ACC_ENUM) == 0
                        || (field.access & Opcodes.ACC_STATIC) == 0) {
                    continue;
                }
                if (JitterAnnotations.isInactive(field.visibleAnnotations)) {
                    removed.add(field.name);
                } else {
                    survivors.add(field.name);
                }
            }
            if (removed.isEmpty()) {
                continue;
            }

            final MethodNode clinit = node.methods.stream()
                    .filter(m -> m.name.equals("<clinit>"))
                    .findFirst().orElse(null);
            if (clinit != null) {
                removeConstantBlocks(clinit, node.name, removed);
                renumberOrdinals(clinit, node.name, survivors);
            }

            final MethodNode valuesHelper = node.methods.stream()
                    .filter(m -> m.name.equals("$values"))
                    .findFirst().orElse(null);
            if (valuesHelper != null) {
                rebuildValuesArray(valuesHelper.instructions, node.name, survivors, true);
                valuesHelper.maxStack = Math.max(4, valuesHelper.maxStack);
            } else if (clinit != null) {
                rebuildValuesInClinit(clinit, node.name, survivors);
            }

            ctx.result.info("Remove enum constants " + removed + " of " + node.name.replace('/', '.')
                    + ". Renumber ordinals of " + survivors);
            ctx.dirty.add(node.name);
        }
    }

    private static void removeConstantBlocks(final MethodNode clinit, final String owner,
                                             final List<String> removed) {
        final List<AbstractInsnNode> insns = new ArrayList<>();
        clinit.instructions.forEach(insns::add);
        int i = 0;
        while (i < insns.size()) {
            final AbstractInsnNode insn = insns.get(i);
            if (!(insn instanceof TypeInsnNode) || insn.getOpcode() != Opcodes.NEW
                    || !((TypeInsnNode) insn).desc.equals(owner)) {
                i++;
                continue;
            }
            int depth = 1;
            int j = i + 1;
            while (j < insns.size() && depth > 0) {
                final AbstractInsnNode current = insns.get(j);
                if (current.getOpcode() == Opcodes.NEW) {
                    depth++;
                } else if (current.getOpcode() == Opcodes.INVOKESPECIAL) {
                    depth--;
                }
                j++;
            }
            if (j >= insns.size()) {
                break;
            }
            final AbstractInsnNode put = insns.get(j);
            if (put instanceof FieldInsnNode && put.getOpcode() == Opcodes.PUTSTATIC
                    && removed.contains(((FieldInsnNode) put).name)) {
                final List<AbstractInsnNode> block = insns.subList(i, j + 1);
                block.forEach(clinit.instructions::remove);
                block.clear();
            } else {
                i = j;
            }
        }
    }

    private static void renumberOrdinals(final MethodNode clinit, final String owner,
                                         final List<String> survivors) {
        final List<AbstractInsnNode> insns = new ArrayList<>();
        clinit.instructions.forEach(insns::add);
        int newOrdinal = 0;
        for (int i = 0; i < insns.size(); i++) {
            final AbstractInsnNode insn = insns.get(i);
            if (!(insn instanceof TypeInsnNode) || insn.getOpcode() != Opcodes.NEW
                    || !((TypeInsnNode) insn).desc.equals(owner)) {
                continue;
            }
            if (i + 3 < insns.size()
                    && insns.get(i + 2) instanceof LdcInsnNode
                    && ((LdcInsnNode) insns.get(i + 2)).cst instanceof String
                    && survivors.contains((String) ((LdcInsnNode) insns.get(i + 2)).cst)
                    && isIntPush(insns.get(i + 3))) {
                clinit.instructions.set(insns.get(i + 3), pushInt(newOrdinal));
                newOrdinal++;
            }
        }
    }

    private static void rebuildValuesInClinit(final MethodNode clinit, final String owner,
                                              final List<String> survivors) {
        final List<AbstractInsnNode> insns = new ArrayList<>();
        clinit.instructions.forEach(insns::add);
        int start = -1;
        int end = -1;
        for (int i = 0; i < insns.size(); i++) {
            final AbstractInsnNode insn = insns.get(i);
            if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.ANEWARRAY
                    && ((TypeInsnNode) insn).desc.equals(owner)) {
                start = i;
            }
            if (start >= 0 && insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTSTATIC
                    && ((FieldInsnNode) insn).name.equals("$VALUES")) {
                end = i;
                break;
            }
        }
        if (start < 0 || end < 0 || end <= start) {
            return;
        }
        for (int k = start; k <= end; k++) {
            clinit.instructions.remove(insns.get(k));
        }
        final InsnList fresh = new InsnList();
        fresh.add(pushInt(survivors.size()));
        fresh.add(new TypeInsnNode(Opcodes.ANEWARRAY, owner));
        appendConstantStores(fresh, owner, survivors);
        fresh.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "$VALUES", "[L" + owner + ";"));
        clinit.instructions.insert(fresh);
        clinit.maxStack = Math.max(4, clinit.maxStack);
    }

    private static void rebuildValuesArray(final InsnList instructions, final String owner,
                                           final List<String> survivors, final boolean returnArray) {
        instructions.clear();
        final InsnList fresh = new InsnList();
        fresh.add(pushInt(survivors.size()));
        fresh.add(new TypeInsnNode(Opcodes.ANEWARRAY, owner));
        appendConstantStores(fresh, owner, survivors);
        if (returnArray) {
            fresh.add(new InsnNode(Opcodes.ARETURN));
        }
        instructions.add(fresh);
    }

    private static void appendConstantStores(final InsnList fresh, final String owner,
                                             final List<String> survivors) {
        int index = 0;
        for (final String constant : survivors) {
            fresh.add(new InsnNode(Opcodes.DUP));
            fresh.add(pushInt(index));
            fresh.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, constant, "L" + owner + ";"));
            fresh.add(new InsnNode(Opcodes.AASTORE));
            index++;
        }
    }

}
