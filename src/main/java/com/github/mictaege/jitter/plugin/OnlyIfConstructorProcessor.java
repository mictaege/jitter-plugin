package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtConstructor;

import java.util.List;

import static java.lang.System.out;
import static java.util.Arrays.asList;

public class OnlyIfConstructorProcessor extends AbstractAnnotationProcessor<OnlyIf, CtConstructor<?>> {

    @Override
    public void process(final OnlyIf annotation, final CtConstructor<?> constructor) {
        final List<String> flavours = asList(annotation.value());
        if (flavours.stream().noneMatch(FlavourUtil::active)) {
            out.println("[jitter] Remove constructor " + constructor.getDeclaringType().getSimpleName());
            constructor.delete();
        }
    }

}
