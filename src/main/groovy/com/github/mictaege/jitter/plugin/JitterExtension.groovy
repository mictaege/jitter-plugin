package com.github.mictaege.jitter.plugin

import groovy.transform.Canonical
import org.gradle.api.Action
import org.gradle.util.ConfigureUtil

@Canonical
class JitterExtension {

    /** List of the projects flavours. */
    List<FlavourCfg> flavours = new ArrayList<>()

    /** Java source code compliance level (1,2,3,4,5, 6, 7 or 8). (default: 8) */
    int compliance = 8

    /** List of excluded source sets. */
    List<String> excludeSrcSets = new ArrayList<>()

    void flavour(Closure closure) {
        def instance = new FlavourCfg()
        flavours.add(instance)
        ConfigureUtil.configure(closure, instance)
    }

    void flavour(Action<? super FlavourCfg> action) {
        def instance = new FlavourCfg()
        flavours.add(instance)
        action.execute(instance)
    }

}
