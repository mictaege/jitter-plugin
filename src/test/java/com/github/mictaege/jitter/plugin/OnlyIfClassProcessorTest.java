package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import spoon.reflect.declaration.CtClass;

import static com.github.mictaege.jitter.plugin.FlavourUtil.KEY;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class OnlyIfClassProcessorTest {

    @Mock
    private OnlyIf annotation;
    @Mock
    private CtClass<?> clazz;

    private OnlyIfClassProcessor processor;

    @Before
    public void context() {
        initMocks(this);
        when(annotation.value()).thenReturn(new String[]{"X"});
        when(clazz.getSimpleName()).thenReturn("AClass");
        processor = new OnlyIfClassProcessor();
    }

    @Test
    public void shouldDeleteIfNoMatchingFlavour() {
        System.setProperty(KEY, "Y");

        processor.process(annotation, clazz);

        verify(clazz).delete();
    }

    @Test
    public void shouldNotDeleteIfMatchingFlavour() {
        System.setProperty(KEY, "X");

        processor.process(annotation, clazz);

        verify(clazz, never()).delete();
    }

}