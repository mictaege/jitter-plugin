package com.github.mictaege.jitter.plugin;

import static java.lang.System.getProperty;
import static java.util.Optional.ofNullable;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

final class JitterUtil {

    static final String FLAVOUR_PROP = "jitter.active.flavour";
    static final String VERIFY_INLINED_CONSTANTS_PROP = "jitter.verifyInlinedConstants";
    static final String GROUP = "jitter";
    static final String DEJITTERED_DIR = "jitter-dejittered";
    /**
     * The Kotlin PSI for the dejittering of Kotlin sources, resolved lazily
     * at dejitter time and loaded in an isolated classloader. The version
     * should match the Kotlin version of the consumers.
     */
    public static final String KOTLIN_COMPILER_EMBEDDABLE =
            "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21";
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

    /**
     * Whether the source level verification of the {@code @InlinedConstantApproved}
     * usages runs as part of the build. The check parses all sources, so
     * it is deliberately opt-in: off in the everyday build - the marker is
     * the review decision - and on for those who want the usages verified
     * mechanically, e.g. on the build server. The check tasks stay
     * invocable directly whatever the flag says.
     */
    static boolean verifyInlinedConstants() {
        return Boolean.parseBoolean(getProperty(VERIFY_INLINED_CONSTANTS_PROP));
    }

    static Logger log() {
        return logger;
    }

}
