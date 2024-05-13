package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import spoon.reflect.declaration.CtClass;

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.mockito.MockitoAnnotations.openMocks;

class OnlyIfClassProcessorTest {

    @Mock
    private OnlyIf annotation;
    @Mock
    private CtClass<?> clazz;

    private OnlyIfClassProcessor processor;

    private AutoCloseable mocks;

    @BeforeEach
    void context() {
        mocks = openMocks(this);
        when(annotation.value()).thenReturn(new String[]{"X"});
        when(clazz.getSimpleName()).thenReturn("AClass");
        processor = new OnlyIfClassProcessor();
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void shouldDeleteIfNoMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "Y");

        processor.process(annotation, clazz);

        verify(clazz).delete();
    }

    @Test
    void shouldNotDeleteIfMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "X");

        processor.process(annotation, clazz);

        verify(clazz, never()).delete();
    }

}