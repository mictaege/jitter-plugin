package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.Alter;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtType;

import java.util.Optional;

import static com.github.mictaege.jitter.plugin.JitterUtil.active;
import static com.github.mictaege.jitter.plugin.JitterUtil.anyVariant;
import static com.github.mictaege.jitter.plugin.JitterUtil.log;
import static java.util.Optional.ofNullable;

public class AlterClassProcessor extends AbstractAnnotationProcessor<Alter, CtClass<?>> {

    @Override
    public void process(final Alter annotation, final CtClass<?> clazz) {
        final String flavour = annotation.ifActive();
        if (anyVariant() && active(flavour)) {
            final String altClass = annotation.with();
            log().info("Replace class " + clazz.getSimpleName() + " with " + altClass);

            final Optional<CtType<?>> altType;
            if (annotation.nested()) {
                altType = ofNullable(clazz.getNestedType(altClass));
            } else {
                if (altClass.contains(".")) {
                    altType = ofNullable(getFactory().Class().get(altClass));
                } else {
                    altType = ofNullable(clazz.getPackage().getType(altClass));
                }
            }

            if (altType.isPresent()) {
                final CtType<?> type = altType.get();
                type.setSimpleName(clazz.getSimpleName());
                type.setModifiers(clazz.getModifiers());
                clazz.replace(type);
            } else {
                log().error("The given alternative class " + altClass + " could not be found");
            }
        }
    }

}
