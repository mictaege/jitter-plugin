package com.github.mictaege.jitter.plugin

/**
 * Replacement for org.gradle.util.ConfigureUtil, which was removed in Gradle 9.
 */
final class Closures {

    private Closures() {
        super()
    }

    static <T> T configure(Closure closure, T target) {
        def c = (Closure) closure.clone()
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c.delegate = target
        if (c.maximumNumberOfParameters == 0) {
            c.call()
        } else {
            c.call(target)
        }
        target
    }

}
