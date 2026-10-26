package com.github.mictaege.jitter.plugin

/**
 * The jitter-api version under test: generated into the test resources
 * (see the {@code generateTestResources} task of the build), so the
 * functional test fixtures resolve the same api version the plugin is
 * built against and no test pins a hand bumped version.
 */
final class JitterApi {

    private JitterApi() {
        super()
    }

    /** The plain version, e.g. {@code 2026.3}. */
    static String version() {
        final def properties = new Properties()
        final def inStream = JitterApi.classLoader.getResourceAsStream('jitter-api.properties')
        if (inStream == null) {
            throw new IllegalStateException(
                    'jitter-api.properties is missing from the test resources -'
                    + ' the generateTestResources task of the build writes it')
        }
        inStream.withCloseable { properties.load(it) }
        properties.getProperty('jitter.api.version')
    }

    /** The dependency notation, e.g. {@code io.github.mictaege:jitter-api:2026.3}. */
    static String dependency() {
        "io.github.mictaege:jitter-api:${version()}"
    }

}
