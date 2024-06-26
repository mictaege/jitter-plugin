package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.mockito.MockitoAnnotations.openMocks;
import static spoon.reflect.declaration.ModifierKind.PUBLIC;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.github.mictaege.jitter.api.Alter;

import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;

class AlterClassProcessorTest {

    @Mock
    private Alter annotation;
    @Mock
    private CtClass<?> clazz;
    @Mock
    private CtPackage pck;
    @Mock
    private CtType barClass;

    private ModifierKind visibility;

    private AlterClassProcessor processor;

    private AutoCloseable mocks;

    @BeforeEach
    void context() {
        mocks = openMocks(this);
        when(annotation.ifActive()).thenReturn("X");
        when(annotation.with()).thenReturn("Bar");
        when(clazz.getSimpleName()).thenReturn("AClass");
        when(clazz.getPackage()).thenReturn(pck);
        when(pck.getType("Bar")).thenReturn(barClass);
        when(barClass.clone()).thenReturn(barClass);
        visibility = PUBLIC;
        when(clazz.getVisibility()).thenReturn(visibility);
        processor = new AlterClassProcessor();
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void shouldNotAlterIfNoMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "Y");

        processor.process(annotation, clazz);

        verify(barClass, never()).setSimpleName("AClass");
        verify(barClass, never()).setVisibility(visibility);
        verify(clazz, never()).replace(barClass);
    }

    @Test
    void shouldAlterIfMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "X");

        processor.process(annotation, clazz);

        verify(barClass).setSimpleName("AClass");
        verify(barClass).setVisibility(visibility);
        verify(clazz).delete();
        verify(pck).addType(barClass);
    }

    @Test
    void shouldSkipIfAlterClassIsMissing() {
        System.setProperty(FLAVOUR_PROP, "X");
        when(pck.getType("Bar")).thenReturn(null);

        processor.process(annotation, clazz);

        verify(barClass, never()).setSimpleName("AClass");
        verify(barClass, never()).setVisibility(visibility);
        verify(clazz, never()).replace(barClass);
    }

}