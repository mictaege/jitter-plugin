package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.mockito.MockitoAnnotations.openMocks;

class OnlyIfFieldProcessorTest {

    @Mock
    private OnlyIf annotation;
    @Mock
    private CtField<?> field;
    @Mock
    private CtType type;

    private OnlyIfFieldProcessor processor;

    private AutoCloseable mocks;

    @BeforeEach
    void context() {
        mocks = openMocks(this);
        when(annotation.value()).thenReturn(new String[]{"X"});
        when(type.getSimpleName()).thenReturn("AClass");
        when(field.getDeclaringType()).thenReturn(type);
        processor = new OnlyIfFieldProcessor();
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void shouldDeleteIfNoMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "Y");

        processor.process(annotation, field);

        verify(field).delete();
    }

    @Test
    void shouldNotDeleteIfMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "X");

        processor.process(annotation, field);

        verify(field, never()).delete();
    }

}