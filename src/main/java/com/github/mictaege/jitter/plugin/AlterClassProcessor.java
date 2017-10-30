package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.Alter;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtType;

import static com.github.mictaege.jitter.plugin.FlavourUtil.active;
import static com.github.mictaege.jitter.plugin.FlavourUtil.anyVariant;
import static java.lang.System.out;

public class AlterClassProcessor extends AbstractAnnotationProcessor<Alter, CtClass<?>> {

    @Override
    public void process(final Alter annotation, final CtClass<?> clazz) {
        final String flavour = annotation.ifActive();
        if (anyVariant() && active(flavour)) {
            final String altClass = annotation.with();
            out.println("[jitter] Replace class " + clazz.getSimpleName() + " with " + altClass);

            final CtType<?> altType;
            if (annotation.nested()) {
                altType = clazz.getNestedType(altClass);
            } else {
                altType = clazz.getPackage().getType(altClass);
            }

            if (altType == null) {
                throw new IllegalStateException("[ERROR] The given alternative class " + altClass + " could not be found");
            }

            altType.setSimpleName(clazz.getSimpleName());
            altType.setModifiers(clazz.getModifiers());
            clazz.replace(altType);
        }
    }

}
