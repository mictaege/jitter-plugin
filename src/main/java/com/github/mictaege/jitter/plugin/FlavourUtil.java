package com.github.mictaege.jitter.plugin;

import static java.lang.System.getProperty;
import static java.util.Optional.ofNullable;

class FlavourUtil {

    static boolean active(final String flavour) {
        return ofNullable(getProperty("jitter.active.flavour"))
                .map(p -> p.length() == 0 || p.equalsIgnoreCase(flavour))
                .orElse(true);
    }

    static boolean anyVariant() {
        return ofNullable(getProperty("jitter.active.flavour"))
                .map(p -> p.length() > 0 )
                .orElse(false);
    }

}
