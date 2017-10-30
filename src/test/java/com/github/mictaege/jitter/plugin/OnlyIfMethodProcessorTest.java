package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import static com.github.mictaege.jitter.plugin.FlavourUtil.KEY;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class OnlyIfMethodProcessorTest {

    @Mock
    private OnlyIf annotation;
    @Mock
    private CtMethod<?> method;
    @Mock
    private CtType type;

    private OnlyIfMethodProcessor processor;

    @Before
    public void context() {
        initMocks(this);
        when(annotation.value()).thenReturn(new String[]{"X"});
        when(type.getSimpleName()).thenReturn("AClass");
        when(method.getDeclaringType()).thenReturn(type);
        processor = new OnlyIfMethodProcessor();
    }

    @Test
    public void shouldDeleteIfNoMatchingFlavour() {
        System.setProperty(KEY, "Y");

        processor.process(annotation, method);

        verify(method).delete();
    }

    @Test
    public void shouldNotDeleteIfMatchingFlavour() {
        System.setProperty(KEY, "X");

        processor.process(annotation, method);

        verify(method, never()).delete();
    }

}