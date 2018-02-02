package com.github.mictaege.jitter.plugin

import groovy.transform.Canonical
import org.gradle.api.Action
import org.gradle.util.ConfigureUtil

@Canonical
class FlavourCfg {
    String name
    CriticalTermsCfg criticalTerms = new CriticalTermsCfg()

    void criticalTerms(Closure closure) {
        ConfigureUtil.configure(closure, criticalTerms)
    }

    void criticalTerms(Action<? super CriticalTermsCfg> action) {
        action.execute(criticalTerms)
    }

}