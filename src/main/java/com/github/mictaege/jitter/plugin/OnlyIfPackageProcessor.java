package com.github.mictaege.jitter.plugin;

import com.github.mictaege.jitter.api.OnlyIf;
import spoon.processing.AbstractAnnotationProcessor;
import spoon.reflect.declaration.CtPackage;

import java.util.List;

import static com.github.mictaege.jitter.plugin.JitterUtil.log;
import static java.util.Arrays.asList;

public class OnlyIfPackageProcessor extends AbstractAnnotationProcessor<OnlyIf, CtPackage> {

    @Override
    public void process(OnlyIf annotation, CtPackage pck) {
        final List<String> flavours = asList(annotation.value());
        if (flavours.stream().noneMatch(JitterUtil::active)) {
            log().info("Remove package " + pck.getSimpleName());
            pck.delete();
        }
    }

}
