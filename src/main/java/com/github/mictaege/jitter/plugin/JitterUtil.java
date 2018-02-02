package com.github.mictaege.jitter.plugin;

import static java.lang.System.getProperty;
import static java.util.Optional.ofNullable;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

final class JitterUtil {

    static final String FLAVOUR_PROP = "jitter.active.flavour";
    private static final Logger logger = Logging.getLogger("jitter");

    private JitterUtil() {
        super();
    }

    static boolean active(final String flavour) {
        return ofNullable(getProperty(FLAVOUR_PROP))
                .map(p -> p.length() == 0 || p.equalsIgnoreCase(flavour))
                .orElse(true);
    }

    static boolean anyFlavour() {
        return ofNullable(getProperty(FLAVOUR_PROP))
                .map(p -> p.length() > 0 )
                .orElse(false);
    }

    static Logger log() {
        return logger;
    }

}
