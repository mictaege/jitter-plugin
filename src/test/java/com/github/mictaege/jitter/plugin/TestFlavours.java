package com.github.mictaege.jitter.plugin;

/**
 * Compile time flavour constants, the fixture of the dejitterer tests: the
 * annotation values reference them instead of string literals, like the
 * projects under test do.
 */
public final class TestFlavours {

    public static final String ESA = "Esa";
    public static final String NASA = "Nasa";

    private TestFlavours() {
        super();
    }

}
