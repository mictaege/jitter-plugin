package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.mockito.MockitoAnnotations.openMocks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.github.mictaege.jitter.api.Fork;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

class ForkMethodProcessorTest {

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

    private AutoCloseable mocks;

    @BeforeEach
    void context() {
        mocks = openMocks(this);
        when(annotation.ifActive()).thenReturn("X");
        when(annotation.to()).thenReturn("bar");
        when(type.getSimpleName()).thenReturn("AClass");
        when(barMethod.getBody()).thenReturn(barBody);
        when(type.getMethodsByName("bar")).thenReturn(singletonList(barMethod));
        when(method.getDeclaringType()).thenReturn(type);
        processor = new ForkMethodProcessor();
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void shouldNotReplaceIfNoMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "Y");

        processor.process(annotation, method);

        verify(method, never()).setBody(any(CtStatement.class));
        verify(barMethod, never()).delete();
    }

    @Test
    void shouldReplaceIfMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "X");

        processor.process(annotation, method);

        verify(method).setBody(barBody);
        verify(barMethod).delete();
    }

    @Test
    void shouldSkipIfForkMethodIsMissing() {
        System.setProperty(FLAVOUR_PROP, "X");
        when(type.getMethodsByName("bar")).thenReturn(emptyList());

        processor.process(annotation, method);

        verify(method, never()).setBody(barBody);
        verify(barMethod, never()).delete();
    }

}