package com.github.mictaege.jitter.plugin

import groovy.transform.Canonical
import org.gradle.api.Action

@Canonical
class JitterExtension {

    /** List of the projects flavours. */
    List<FlavourCfg> flavours = new ArrayList<>()

    /** List of excluded source sets. */
    List<String> excludeSrcSets = new ArrayList<>()

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
