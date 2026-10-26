package com.github.mictaege.jitter.plugin

import groovy.transform.Canonical
import org.gradle.api.Action

@Canonical
class JitterExtension {

    /** List of the projects flavours. */
    List<FlavourCfg> flavours = new ArrayList<>()

    /** List of excluded source sets. */
    List<String> excludeSrcSets = new ArrayList<>()

    /**
     * Source sets whose flavour-stripped model is the module's runtime
     * artifact: a stripped jar replaces the outgoing runtime variant
     * ({@code runtimeElements} for {@code main},
     * {@code <name>RuntimeElements} for any other name). Their full
     * compiled classes stay the compile artifact, so downstream modules
     * compile against the 150% model. The default is the {@code main}
     * source set.
     */
    List<String> packagingSrcSets = new ArrayList<>(List.of('main'))

    /**
     * Source sets that keep their full compiled classes as the compile
     * artifact and get a flavour-stripped runtime artifact instead: a
     * stripped jar replaces the outgoing {@code <name>RuntimeElements}
     * variant, like the standard test fixtures do. The default is the
     * {@code testFixtures} source set.
     */
    List<String> apiSrcSets = new ArrayList<>(List.of('testFixtures'))

    void flavour(Closure closure) {
        def instance = new FlavourCfg()
        flavours.add(instance)
        Closures.configure(closure, instance)
    }

    void flavour(Action<? super FlavourCfg> action) {
        def instance = new FlavourCfg()
        flavours.add(instance)
        action.execute(instance)
    }

}
