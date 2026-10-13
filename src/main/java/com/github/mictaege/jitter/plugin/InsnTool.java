package com.github.mictaege.jitter.plugin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Stateless instruction level helpers of the bytecode stripping: stack
 * effect analysis of straight-line code, instruction shape checks and the
 * javac switch map entry shape. Nothing in here knows about flavours or
 * the pipeline - everything operates on the ASM tree API alone.
 */
final class InsnTool {

    private InsnTool() {
        super();
    }

    static boolean isIntPush(final AbstractInsnNode insn) {
        final int opcode = insn.getOpcode();
        return (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5)
                || opcode == Opcodes.BIPUSH
                || opcode == Opcodes.SIPUSH
                || opcode == Opcodes.LDC;
    }

    static AbstractInsnNode pushInt(final int value) {
        if (value >= 0 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    /**
     * A removable initializer block must not contain labels that are
     * referenced from outside the block. Line number markers inside a
     * multi-line initializer expression are safe - a branch into the block,
     * an exception range or a local variable range crossing it is not.
     */
    static boolean selfContained(final MethodNode method, final List<AbstractInsnNode> insns,
                                 final int start, final int end) {
        final Set<LabelNode> inner = new HashSet<>();
        for (int i = start; i < end; i++) {
            final AbstractInsnNode insn = insns.get(i);
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode) {
                return false;
            }
            if (insn instanceof LabelNode) {
                inner.add((LabelNode) insn);
            }
        }
        if (inner.isEmpty()) {
            return true;
        }
        for (final AbstractInsnNode insn : insns) {
            if (insn instanceof JumpInsnNode && inner.contains(((JumpInsnNode) insn).label)) {
                return false;
            }
            if (insn instanceof TableSwitchInsnNode) {
                final TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                if (inner.contains(sw.dflt) || sw.labels.stream().anyMatch(inner::contains)) {
                    return false;
                }
            }
            if (insn instanceof LookupSwitchInsnNode) {
                final LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                if (inner.contains(sw.dflt) || sw.labels.stream().anyMatch(inner::contains)) {
                    return false;
                }
            }
            if (insn instanceof LineNumberNode
                    && inner.contains(((LineNumberNode) insn).start)
                    && (insns.indexOf(insn) < start || insns.indexOf(insn) >= end)) {
                return false;
            }
        }
        for (final TryCatchBlockNode block : method.tryCatchBlocks) {
            if (inner.contains(block.start) || inner.contains(block.end)
                    || inner.contains(block.handler)) {
                return false;
            }
        }
        for (final LocalVariableNode local : method.localVariables) {
            if (inner.contains(local.start) || inner.contains(local.end)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks backward from a store instruction and returns the index of the
     * first instruction that produces the stored value. Returns -1 if the
     * producing sequence is too complex to be detected reliably.
     */
    static int findValueStart(final List<AbstractInsnNode> insns, final int storeIndex,
                               final int slots) {
        int unresolved = slots;
        for (int i = storeIndex - 1; i >= 0; i--) {
            final AbstractInsnNode insn = insns.get(i);
            final int[] effect = stackEffect(insn);
            if (effect == null) {
                return -1;
            }
            final int pops = effect[0];
            final int pushes = effect[1];
            if (pushes >= unresolved && pops == 0) {
                return i;
            }
            unresolved = unresolved - pushes + pops;
            if (unresolved <= 0) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Stack effect (pops, pushes) of an instruction, or null if the
     * instruction cannot be handled safely, e.g. branches. Labels are line
     * number markers in a straight-line initializer - the safety of
     * removing across them is checked separately by
     * {@link #selfContained(MethodNode, List, int, int)}.
     */
    static int[] stackEffect(final AbstractInsnNode insn) {
        final int opcode = insn.getOpcode();
        if (opcode < 0) {
            if (insn instanceof LineNumberNode || insn instanceof FrameNode
                    || insn instanceof LabelNode) {
                return new int[]{0, 0};
            }
            return null;
        }
        if (insn instanceof LabelNode) {
            return null;
        }
        if (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.DCONST_1) {
            return new int[]{0, wide(opcode)};
        }
        if (opcode == Opcodes.ACONST_NULL || opcode == Opcodes.BIPUSH
                || opcode == Opcodes.SIPUSH) {
            return new int[]{0, 1};
        }
        if (opcode == Opcodes.POP) {
            return new int[]{1, 0};
        }
        if (opcode == Opcodes.POP2) {
            return new int[]{2, 0};
        }
        final int[] arithmetic = arithmeticEffect(opcode);
        if (arithmetic != null) {
            return arithmetic;
        }
        if (insn instanceof LdcInsnNode) {
            final Object cst = ((LdcInsnNode) insn).cst;
            final boolean wide = cst instanceof Long || cst instanceof Double;
            return new int[]{0, wide ? 2 : 1};
        }
        if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
            return new int[]{0, 1};
        }
        if (opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD) {
            return new int[]{0, 2};
        }
        if (opcode == Opcodes.GETSTATIC) {
            return new int[]{0, Type.getType(((FieldInsnNode) insn).desc).getSize()};
        }
        if (opcode == Opcodes.GETFIELD) {
            return new int[]{1, Type.getType(((FieldInsnNode) insn).desc).getSize()};
        }
        if (opcode == Opcodes.NEW || opcode == Opcodes.DUP) {
            return new int[]{0, 1};
        }
        if (opcode == Opcodes.ANEWARRAY || opcode == Opcodes.NEWARRAY) {
            // pops the element count, pushes the array
            return new int[]{1, 1};
        }
        if (opcode == Opcodes.ARRAYLENGTH) {
            return new int[]{1, 1};
        }
        if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD) {
            // array reads: pops index and arrayref, pushes the element
            // (long and double elements take two slots)
            return new int[]{2, (opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD) ? 2 : 1};
        }
        if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) {
            // array writes: pops value, index and arrayref
            // (long and double values take two slots)
            return new int[]{(opcode == Opcodes.LASTORE || opcode == Opcodes.DASTORE) ? 4 : 3, 0};
        }
        if (opcode == Opcodes.CHECKCAST || opcode == Opcodes.INSTANCEOF) {
            return new int[]{1, 1};
        }
        if (insn instanceof MethodInsnNode) {
            final MethodInsnNode call = (MethodInsnNode) insn;
            final int args = Type.getArgumentTypes(call.desc).length;
            final int ret = Type.getReturnType(call.desc).getSize();
            final int receiver = opcode == Opcodes.INVOKESTATIC ? 0 : 1;
            return new int[]{args + receiver, ret};
        }
        if (insn instanceof InvokeDynamicInsnNode) {
            // lambdas and method references: the call site descriptor
            // consumes the captured arguments and produces the
            // functional interface instance
            final InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
            final Type[] captures = Type.getArgumentTypes(indy.desc);
            int pops = 0;
            for (final Type capture : captures) {
                pops += capture.getSize();
            }
            return new int[]{pops, Type.getReturnType(indy.desc).getSize()};
        }
        return null;
    }

    private static int wide(final int opcode) {
        return opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1
                || opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1 ? 2 : 1;
    }

    /**
     * Stack effect of the straight-line arithmetic and conversion
     * opcodes, or null if the opcode is none of them. Only used by the
     * backward initializer scan, which bails out on anything unknown -
     * branches (the operands of a ternary) still make it give up.
     */
    private static int[] arithmeticEffect(final int opcode) {
        if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM) {
            // I L F D per operation group: pops 2/4/2/4, pushes 1/2/1/2
            final int group = (opcode - Opcodes.IADD) % 4;
            return group == 1 || group == 3
                    ? new int[]{4, 2}
                    : new int[]{2, 1};
        }
        if (opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG) {
            // I L F D
            return new int[]{(opcode - Opcodes.INEG) % 2 == 1 ? 2 : 1,
                    (opcode - Opcodes.INEG) % 2 == 1 ? 2 : 1};
        }
        if (opcode >= Opcodes.ISHL && opcode <= Opcodes.LUSHR) {
            // I L I L I L: a long shift pops the long and the int count
            return new int[]{(opcode - Opcodes.ISHL) % 2 == 1 ? 3 : 2,
                    (opcode - Opcodes.ISHL) % 2 == 1 ? 2 : 1};
        }
        if (opcode >= Opcodes.IAND && opcode <= Opcodes.LXOR) {
            // I L I L I L
            return new int[]{(opcode - Opcodes.IAND) % 2 == 1 ? 4 : 2,
                    (opcode - Opcodes.IAND) % 2 == 1 ? 2 : 1};
        }
        switch (opcode) {
            case Opcodes.I2L: case Opcodes.I2D:
            case Opcodes.F2L: case Opcodes.F2D:
                return new int[]{1, 2};
            case Opcodes.L2I: case Opcodes.L2F: case Opcodes.L2D:
            case Opcodes.D2I: case Opcodes.D2L: case Opcodes.D2F:
                return new int[]{2, (opcode == Opcodes.L2D || opcode == Opcodes.D2L) ? 2 : 1};
            case Opcodes.I2F: case Opcodes.F2I:
            case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
                return new int[]{1, 1};
            default:
                return null;
        }
    }

    static int nextReal(final List<AbstractInsnNode> insns, int idx) {
        while (idx >= 0 && idx < insns.size() && isMeta(insns.get(idx))) {
            idx++;
        }
        return idx;
    }

    static int prevReal(final List<AbstractInsnNode> insns, int idx) {
        while (idx >= 0 && idx < insns.size() && isMeta(insns.get(idx))) {
            idx--;
        }
        return idx;
    }

    static boolean isMeta(final AbstractInsnNode insn) {
        return insn instanceof FrameNode || insn instanceof LineNumberNode;
    }

    /**
     * Recognizes the astore of the NoSuchFieldError handler. The raw
     * astore_0..astore_3 opcodes are normalized by the ASM tree API into a
     * VarInsnNode with the generic ASTORE opcode.
     */
    static boolean isAstoreN(final AbstractInsnNode insn) {
        return insn instanceof VarInsnNode && insn.getOpcode() == Opcodes.ASTORE;
    }

}
