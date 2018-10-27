package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.active;
import static com.github.mictaege.jitter.plugin.JitterUtil.anyFlavour;
import static com.github.mictaege.jitter.plugin.JitterUtil.log;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static spoon.reflect.declaration.ModifierKind.PRIVATE;
import static spoon.reflect.declaration.ModifierKind.PROTECTED;
import static spoon.reflect.declaration.ModifierKind.PUBLIC;

import java.util.Optional;

import com.github.mictaege.jitter.api.Alter;

import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.ModifierKind;

public class AlterClassProcessor extends AbstractAnnotationProcessor<Alter, CtClass<?>> {

    @Override
    public void process(final Alter annotation, final CtClass<?> clazz) {
        final String flavour = annotation.ifActive();
        if (anyFlavour() && active(flavour)) {
            final String altClass = annotation.with();
            log().info("Replace class " + clazz.getSimpleName() + " with " + altClass);

            final Optional<CtType<?>> altType;
            if (altClass.contains(".")) {
                altType = ofNullable(getFactory().Class().get(altClass));
            } else {
                altType = ofNullable(clazz.getPackage().getType(altClass));
            }

            if (altType.isPresent()) {
                final ModifierKind visibility = clazz.getVisibility();
                final String simpleName = clazz.getSimpleName();
                clazz.delete();

                final CtType<?> clone = altType.get().clone();
                clone.setVisibility(visibility);
                clone.setSimpleName(simpleName);
                fixModifiers(altType.get(), clone);
                clazz.getPackage().addType(clone);
            } else {
                log().error("The given alternative class " + altClass + " could not be found");
            }
        }
    }

    private void fixModifiers(final CtType<?> altType, final CtType<?> clone) {
        clone.getFields().forEach(f -> fixModifier(f, altType));
        clone.getMethods().forEach(m -> fixModifier(m, altType));
    }

    private void fixModifier(final CtTypeMember cloneMember, final CtType<?> altType) {
        final boolean duplicateVisibility =
                cloneMember.getExtendedModifiers().stream()
                        .filter(m -> asList(PRIVATE, PUBLIC, PROTECTED).contains(m.getKind()))
                        .count() > 1;
        if (duplicateVisibility) {
            log().info("Duplicate visibility modifiers found after spoon processing. Try to fix " + altType.getSimpleName() + "#" + cloneMember.getSimpleName());
            altType.getTypeMembers().stream()
                    .filter(a -> a.getSimpleName().equals(cloneMember.getSimpleName()))
                    .findFirst()
                    .ifPresent(a -> cloneMember.setExtendedModifiers(a.getExtendedModifiers()));
        }
    }

}
