package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.github.mictaege.jitter.api.OnlyIf;

import spoon.reflect.declaration.CtPackage;

class OnlyIfPackageProcessorTest {

    @Mock
    private OnlyIf annotation;
    @Mock
    private CtPackage pck;

    private OnlyIfPackageProcessor processor;

    private AutoCloseable mocks;

    @BeforeEach
    void context() {
        mocks = openMocks(this);
        when(annotation.value()).thenReturn(new String[]{"X"});
        when(pck.getSimpleName()).thenReturn("apackage");
        processor = new OnlyIfPackageProcessor();
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void shouldDeleteIfNoMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "Y");

        processor.process(annotation, pck);

        verify(pck).delete();
    }

    @Test
    void shouldNotDeleteIfMatchingFlavour() {
        System.setProperty(FLAVOUR_PROP, "X");

        processor.process(annotation, pck);

        verify(pck, never()).delete();
    }

}