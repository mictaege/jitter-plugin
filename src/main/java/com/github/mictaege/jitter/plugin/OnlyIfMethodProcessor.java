package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtMethod;

import java.util.List;

import static com.github.mictaege.jitter.plugin.JitterUtil.log;
import static java.util.Arrays.asList;

public class OnlyIfMethodProcessor extends AbstractAnnotationProcessor<OnlyIf, CtMethod<?>> {

    @Override
    public void process(final OnlyIf annotation, final CtMethod<?> method) {
        final List<String> flavours = asList(annotation.value());
        if (flavours.stream().noneMatch(JitterUtil::active)) {
            log().info("Remove method " + method.getDeclaringType().getSimpleName() + "#" + method.getSimpleName());
            method.delete();
        }
    }

}
