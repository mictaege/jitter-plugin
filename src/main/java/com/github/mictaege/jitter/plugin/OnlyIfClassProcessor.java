package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtClass;

import java.util.List;

import static java.lang.System.out;
import static java.util.Arrays.asList;

public class OnlyIfClassProcessor extends AbstractAnnotationProcessor<OnlyIf, CtClass<?>> {

    @Override
    public void process(final OnlyIf annotation, final CtClass<?> clazz) {
        final List<String> flavours = asList(annotation.value());
        if (flavours.stream().noneMatch(FlavourUtil::active)) {
            out.println("[jitter] Remove class " + clazz.getSimpleName());
            clazz.delete();
        }
    }

}
