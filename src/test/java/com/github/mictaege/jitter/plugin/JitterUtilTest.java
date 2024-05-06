package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Test;

class JitterUtilTest {

    @Test
    void shouldIndicateActiveIfNoProperty() {
        assertThat(JitterUtil.active("X"), is(true));
    }

    @Test
    void shouldIndicateActiveIfEmptyProperty() {
        System.setProperty(FLAVOUR_PROP, "");
        assertThat(JitterUtil.active("X"), is(true));
    }

    @Test
    void shouldIndicateInactiveIfOtherProperty() {
        System.setProperty(FLAVOUR_PROP, "Y");
        assertThat(JitterUtil.active("X"), is(false));
    }

    @Test
    void shouldIndicateActiveIfSameProperty() {
        System.setProperty(FLAVOUR_PROP, "X");
        assertThat(JitterUtil.active("X"), is(true));
    }

    @Test
    void shouldIndicateAnyIfPropertyPresent() {
        System.setProperty(FLAVOUR_PROP, "X");
        assertThat(JitterUtil.anyFlavour(), is(true));
    }

    @Test
    void shouldIndicateNoneIfPropertyEmpty() {
        System.setProperty(FLAVOUR_PROP, "");
        assertThat(JitterUtil.anyFlavour(), is(false));
    }

    @Test
    void shouldIndicateNoneIfNoProperty() {
        assertThat(JitterUtil.anyFlavour(), is(false));
    }

    @Test
    void shouldProvideLogger() {
        assertThat(JitterUtil.log(), is(notNullValue()));
    }

}