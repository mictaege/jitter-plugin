package com.github.mictaege.jitter.plugin

import groovy.transform.Canonical
import org.gradle.api.Action

@Canonical
class FlavourCfg {
    String name
    CriticalTermsCfg criticalTerms = new CriticalTermsCfg()

    void criticalTerms(Closure closure) {
        Closures.configure(closure, criticalTerms)
    }

    void criticalTerms(Action<? super CriticalTermsCfg> action) {
        action.execute(criticalTerms)
    }

}