package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtField;

import java.util.List;

import static com.github.mictaege.jitter.plugin.JitterUtil.log;
import static java.util.Arrays.asList;

public class OnlyIfFieldProcessor extends AbstractAnnotationProcessor<OnlyIf, CtField<?>> {

    @Override
    public void process(final OnlyIf annotation, final CtField<?> field) {
        final List<String> flavours = asList(annotation.value());
        if (flavours.stream().noneMatch(JitterUtil::active)) {
            log().info("Remove field " + field.getDeclaringType().getSimpleName() + "#" + field.getSimpleName());
            field.delete();
        }
    }

}
