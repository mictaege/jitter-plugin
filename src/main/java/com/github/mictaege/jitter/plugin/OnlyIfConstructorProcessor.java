package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtConstructor;

import java.util.List;

import static com.github.mictaege.jitter.plugin.JitterUtil.log;
import static java.util.Arrays.asList;

public class OnlyIfConstructorProcessor extends AbstractAnnotationProcessor<OnlyIf, CtConstructor<?>> {

    @Override
    public void process(final OnlyIf annotation, final CtConstructor<?> constructor) {
        final List<String> flavours = asList(annotation.value());
        if (flavours.stream().noneMatch(JitterUtil::active)) {
            log().info("Remove constructor " + constructor.getDeclaringType().getSimpleName());
            constructor.delete();
        }
    }

}
