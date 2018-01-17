package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.log;
import static java.util.Arrays.asList;

import java.util.List;

import com.github.mictaege.jitter.api.OnlyIf;

import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtType;

public class OnlyIfClassProcessor extends AbstractAnnotationProcessor<OnlyIf, CtType<?>> {

    @Override
    public void process(final OnlyIf annotation, final CtType<?> clazz) {
        final List<String> flavours = asList(annotation.value());
        if (flavours.stream().noneMatch(JitterUtil::active)) {
            log().info("Remove class " + clazz.getSimpleName());
            clazz.delete();
        }
    }

}
