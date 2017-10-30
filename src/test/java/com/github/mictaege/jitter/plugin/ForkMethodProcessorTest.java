package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.Fork;
import com.github.mictaege.jitter.api.OnlyIf;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.github.mictaege.jitter.plugin.FlavourUtil.KEY;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ForkMethodProcessorTest {

    @Mock
    private Fork annotation;
    @Mock
    private CtMethod<?> method;
    @Mock
    private CtType type;
    @Mock
    private CtMethod barMethod;
    @Mock
    private CtBlock barBody;

    private ForkMethodProcessor processor;

    @Before
    public void context() {
        initMocks(this);
        when(annotation.ifActive()).thenReturn("X");
        when(annotation.to()).thenReturn("bar");
        when(type.getSimpleName()).thenReturn("AClass");
        when(barMethod.getBody()).thenReturn(barBody);
        when(type.getMethodsByName("bar")).thenReturn(singletonList(barMethod));
        when(method.getDeclaringType()).thenReturn(type);
        processor = new ForkMethodProcessor();
    }

    @Test
    public void shouldNotReplaceIfNoMatchingFlavour() {
        System.setProperty(KEY, "Y");

        processor.process(annotation, method);

        verify(method, never()).setBody(any(CtStatement.class));
        verify(barMethod, never()).delete();
    }

    @Test
    public void shouldReplaceIfMatchingFlavour() {
        System.setProperty(KEY, "X");

        processor.process(annotation, method);

        verify(method).setBody(barBody);
        verify(barMethod).delete();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowErrorIfForkMethodIsMissing() {
        System.setProperty(KEY, "X");
        when(type.getMethodsByName("bar")).thenReturn(emptyList());

        processor.process(annotation, method);

        verify(method).setBody(barBody);
        verify(barMethod).delete();
    }

}