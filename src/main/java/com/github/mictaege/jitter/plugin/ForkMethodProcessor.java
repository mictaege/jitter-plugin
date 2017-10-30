package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.Fork;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtMethod;

import static com.github.mictaege.jitter.plugin.FlavourUtil.active;
import static com.github.mictaege.jitter.plugin.FlavourUtil.anyVariant;
import static java.lang.System.out;

public class ForkMethodProcessor extends AbstractAnnotationProcessor<Fork, CtMethod<?>> {

    @Override
    public void process(final Fork annotation, final CtMethod<?> method) {
        final String flavour = annotation.ifActive();
        if (anyVariant() && active(flavour)) {
            final String altName = annotation.to();
            final CtMethod<?> alternative = method.getDeclaringType().getMethodsByName(altName).stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("[ERROR] The given alternative method " + altName + " could not be found"));
            out.println("[jitter] Replace method " + method.getDeclaringType().getSimpleName() + "#" + method.getSimpleName() + " with #" + altName);
            method.setBody(alternative.getBody());
            alternative.delete();
        }
    }

}
