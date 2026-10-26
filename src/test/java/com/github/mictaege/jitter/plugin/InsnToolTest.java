package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Characterizes the instruction level helpers as they are used by the
 * backward initializer scan. Two effects are asserted here EXACTLY as the
 * scan behaves today, although they look like candidates for a fix:
 * long/double local loads report one pushed slot (the ILOAD..ALOAD range
 * check wins over the LLOAD/DLOAD branch), and method calls pop by argument
 * COUNT, not by slot count, so long/double parameters cost one pop instead
 * of two. Changing either would change which initializers the stripper
 * dares to remove - a functional change, kept out of the refactoring.
 */
public class InsnToolTest {

    @Test
    void shouldComputeTheStackEffectOfValueProducers() {
        assertThat(InsnTool.stackEffect(new InsnNode(Opcodes.ICONST_1)), is(new int[]{0, 1}));
        assertThat(InsnTool.stackEffect(new InsnNode(Opcodes.LCONST_1)), is(new int[]{0, 2}));
        assertThat(InsnTool.stackEffect(new InsnNode(Opcodes.ACONST_NULL)), is(new int[]{0, 1}));
        assertThat(InsnTool.stackEffect(new IntInsnNode(Opcodes.BIPUSH, 42)), is(new int[]{0, 1}));
        assertThat(InsnTool.stackEffect(new LdcInsnNode("x")), is(new int[]{0, 1}));
        assertThat(InsnTool.stackEffect(new LdcInsnNode(1L)), is(new int[]{0, 2}));
    }

    @Test
    void shouldComputeTheStackEffectOfArithmeticAndLoads() {
        assertThat(InsnTool.stackEffect(new InsnNode(Opcodes.IADD)), is(new int[]{2, 1}));
        assertThat(InsnTool.stackEffect(new InsnNode(Opcodes.LADD)), is(new int[]{4, 2}));
        assertThat(InsnTool.stackEffect(new InsnNode(Opcodes.I2L)), is(new int[]{1, 2}));
        // the ILOAD..ALOAD range wins over the LLOAD/DLOAD branch (see class comment)
        assertThat(InsnTool.stackEffect(new VarInsnNode(Opcodes.LLOAD, 1)), is(new int[]{0, 1}));
        assertThat(InsnTool.stackEffect(new VarInsnNode(Opcodes.ILOAD, 1)), is(new int[]{0, 1}));
    }

    @Test
    void shouldComputeTheStackEffectOfFieldAndMethodAccess() {
        assertThat(InsnTool.stackEffect(new FieldInsnNode(Opcodes.GETSTATIC, "o", "f", "I")), is(new int[]{0, 1}));
        assertThat(InsnTool.stackEffect(new FieldInsnNode(Opcodes.GETSTATIC, "o", "f", "J")), is(new int[]{0, 2}));
        assertThat(InsnTool.stackEffect(new FieldInsnNode(Opcodes.GETFIELD, "o", "f", "J")), is(new int[]{1, 2}));
        // pops by argument count, not slot count (see class comment)
        assertThat(InsnTool.stackEffect(new MethodInsnNode(Opcodes.INVOKESTATIC, "o", "m", "(IJ)V", false)),
                is(new int[]{2, 0}));
        assertThat(InsnTool.stackEffect(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "o", "m", "()J", false)),
                is(new int[]{1, 2}));
    }

    @Test
    void shouldRejectBranchyAndUnknownInstructions() {
        assertThat(InsnTool.stackEffect(new InsnNode(Opcodes.IFEQ)), nullValue());
        assertThat(InsnTool.stackEffect(new InsnNode(Opcodes.GOTO)), nullValue());
        assertThat(InsnTool.stackEffect(new InsnNode(Opcodes.MONITORENTER)), nullValue());
    }

    @Test
    void shouldFindTheStartOfAStoredValue() {
        // javac shape of `this.f = 5;`: ALOAD_0; ICONST_5; PUTFIELD
        // -> the store consumes receiver and value, both part of the removable block
        final List<AbstractInsnNode> insns = List.of(
                new VarInsnNode(Opcodes.ALOAD, 0),
                new InsnNode(Opcodes.ICONST_5),
                new FieldInsnNode(Opcodes.PUTFIELD, "o", "f", "I"));
        assertThat(InsnTool.findValueStart(insns, 2, 2), is(0));
    }

    @Test
    void shouldGiveUpOnUnclearValueSequences() {
        // an arithmetic expression mixing the receiver with new values does
        // not resolve backward - reported instead of removed
        final List<AbstractInsnNode> insns = List.of(
                new VarInsnNode(Opcodes.ALOAD, 0),
                new InsnNode(Opcodes.ICONST_1),
                new InsnNode(Opcodes.IADD),
                new FieldInsnNode(Opcodes.PUTFIELD, "o", "f", "I"));
        assertThat(InsnTool.findValueStart(insns, 3, 2), is(-1));
    }

    @Test
    void shouldPushIntsInTheirCanonicalForm() {
        assertThat(InsnTool.pushInt(0).getOpcode(), is(Opcodes.ICONST_0));
        assertThat(InsnTool.pushInt(5).getOpcode(), is(Opcodes.ICONST_5));
        assertThat(InsnTool.pushInt(6).getOpcode(), is(Opcodes.BIPUSH));
        assertThat(InsnTool.pushInt(300).getOpcode(), is(Opcodes.SIPUSH));
        assertThat(InsnTool.pushInt(100000).getOpcode(), is(Opcodes.LDC));
        assertThat(InsnTool.isIntPush(InsnTool.pushInt(100000)), is(true));
        assertThat(InsnTool.isIntPush(new InsnNode(Opcodes.NOP)), is(false));
    }

}
