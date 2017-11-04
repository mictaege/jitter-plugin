package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtPackage;

import java.util.List;

import static java.lang.System.out;
import static java.util.Arrays.asList;

public class OnlyIfPackageProcessor extends AbstractAnnotationProcessor<OnlyIf, CtPackage> {

    @Override
    public void process(OnlyIf annotation, CtPackage pck) {
        final List<String> flavours = asList(annotation.value());
        if (flavours.stream().noneMatch(FlavourUtil::active)) {
            out.println("[jitter] Remove package " + pck.getSimpleName());
            pck.delete();
        }
    }

}
