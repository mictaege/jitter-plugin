package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.Alter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;

import java.util.Set;

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class AlterClassProcessorTest {

    @Mock
    private Alter annotation;
    @Mock
    private CtClass<?> clazz;
    @Mock
    private CtPackage pck;
    @Mock
    private CtType barClass;
    @Mock
    private Set<ModifierKind> modifiers;

    private AlterClassProcessor processor;

    @Before
    public void context() {
        initMocks(this);
        when(annotation.ifActive()).thenReturn("X");
        when(annotation.with()).thenReturn("Bar");
        when(annotation.nested()).thenReturn(false);
        when(clazz.getSimpleName()).thenReturn("AClass");
        when(clazz.getPackage()).thenReturn(pck);
        when(pck.getType("Bar")).thenReturn(barClass);
        when(clazz.getNestedType("Bar")).thenReturn(barClass);
        when(clazz.getModifiers()).thenReturn(modifiers);
        processor = new AlterClassProcessor();
    }

    @Test
    public void shouldNotAlterIfNoMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "Y");

        processor.process(annotation, clazz);

        verify(barClass, never()).setSimpleName("AClass");
        verify(barClass, never()).setModifiers(modifiers);
        verify(clazz, never()).replace(barClass);
    }

    @Test
    public void shouldAlterIfMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "X");

        processor.process(annotation, clazz);

        verify(barClass).setSimpleName("AClass");
        verify(barClass).setModifiers(modifiers);
        verify(clazz).replace(barClass);
    }

    @Test
    public void shouldNotAlterNestedIfNoMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "Y");
        when(annotation.nested()).thenReturn(true);

        processor.process(annotation, clazz);

        verify(barClass, never()).setSimpleName("AClass");
        verify(barClass, never()).setModifiers(modifiers);
        verify(clazz, never()).replace(barClass);
    }

    @Test
    public void shouldAlterNestedIfMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "X");
        when(annotation.nested()).thenReturn(true);

        processor.process(annotation, clazz);

        verify(barClass).setSimpleName("AClass");
        verify(barClass).setModifiers(modifiers);
        verify(clazz).replace(barClass);
    }

    @Test
    public void shouldSkipIfAlterClassIsMissing() {
        System.setProperty(FLAVOUR_PROP, "X");
        when(pck.getType("Bar")).thenReturn(null);

        processor.process(annotation, clazz);

        verify(barClass, never()).setSimpleName("AClass");
        verify(barClass, never()).setModifiers(modifiers);
        verify(clazz, never()).replace(barClass);
    }

}