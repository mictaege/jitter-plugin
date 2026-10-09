package com.github.mictaege.jitter.plugin

import groovy.transform.Canonical
import org.gradle.api.Action

@Canonical
class JitterExtension {

    /** List of the projects flavours. */
    List<FlavourCfg> flavours = new ArrayList<>()

    /** Java source code compliance level (1,2,3,4,5, 6, 7 or 8). (default: 8) */
    int compliance = 11

    /** List of excluded source sets. */
    List<String> excludeSrcSets = new ArrayList<>()

    /**
     * List of source sets that are stripped late, i.e. after all
     * compilations of the project have run and not right after their own
     * compilation. Needed when another source set compiles against the
     * full 150% model of this source set (e.g. generated jaxb classes).
     */
    List<String> lateStripSrcSets = new ArrayList<>()

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
