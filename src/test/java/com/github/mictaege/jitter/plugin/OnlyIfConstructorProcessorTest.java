package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtType;

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class OnlyIfConstructorProcessorTest {

    @Mock
    private OnlyIf annotation;
    @Mock
    private CtConstructor<?> constructor;
    @Mock
    private CtType type;

    private OnlyIfConstructorProcessor processor;

    @Before
    public void context() {
        initMocks(this);
        when(annotation.value()).thenReturn(new String[]{"X"});
        when(type.getSimpleName()).thenReturn("AClass");
        when(constructor.getDeclaringType()).thenReturn(type);
        processor = new OnlyIfConstructorProcessor();
    }

    @Test
    public void shouldDeleteIfNoMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "Y");

        processor.process(annotation, constructor);

        verify(constructor).delete();
    }

    @Test
    public void shouldNotDeleteIfMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "X");

        processor.process(annotation, constructor);

        verify(constructor, never()).delete();
    }

}