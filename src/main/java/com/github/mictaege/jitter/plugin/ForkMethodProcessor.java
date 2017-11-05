package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.Fork;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtMethod;

import java.util.Optional;

import static com.github.mictaege.jitter.plugin.JitterUtil.active;
import static com.github.mictaege.jitter.plugin.JitterUtil.anyVariant;
import static com.github.mictaege.jitter.plugin.JitterUtil.log;

public class ForkMethodProcessor extends AbstractAnnotationProcessor<Fork, CtMethod<?>> {

    @Override
    public void process(final Fork annotation, final CtMethod<?> method) {
        final String flavour = annotation.ifActive();
        if (anyVariant() && active(flavour)) {
            final String altName = annotation.to();
            final Optional<CtMethod<?>> altMethod = method.getDeclaringType().getMethodsByName(altName).stream().findFirst();
            if (altMethod.isPresent()) {
                log().info("Replace method " + method.getDeclaringType().getSimpleName() + "#" + method.getSimpleName() + " with #" + altName);
                method.setBody(altMethod.get().getBody());
                altMethod.get().delete();
            } else {
                log().error("The given alternative method " + altName + " could not be found");
            }
        }
    }

}
