package com.github.mictaege.jitter.plugin;

import org.junit.Test;

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

public class JitterUtilTest {

    @Test
    public void shouldIndicateActiveIfNoProperty() {
        assertThat(JitterUtil.active("X"), is(true));
    }

    @Test
    public void shouldIndicateActiveIfEmptyProperty() {
        System.setProperty(FLAVOUR_PROP, "");
        assertThat(JitterUtil.active("X"), is(true));
    }

    @Test
    public void shouldIndicateInactiveIfOtherProperty() {
        System.setProperty(FLAVOUR_PROP, "Y");
        assertThat(JitterUtil.active("X"), is(false));
    }

    @Test
    public void shouldIndicateActiveIfSameProperty() {
        System.setProperty(FLAVOUR_PROP, "X");
        assertThat(JitterUtil.active("X"), is(true));
    }

    @Test
    public void shouldIndicateAnyIfPropertyPresent() {
        System.setProperty(FLAVOUR_PROP, "X");
        assertThat(JitterUtil.anyVariant(), is(true));
    }

    @Test
    public void shouldIndicateNoneIfPropertyEmpty() {
        System.setProperty(FLAVOUR_PROP, "");
        assertThat(JitterUtil.anyVariant(), is(false));
    }

    @Test
    public void shouldIndicateNoneIfNoProperty() {
        assertThat(JitterUtil.anyVariant(), is(false));
    }

    @Test
    public void shouldProvideLogger() {
        assertThat(JitterUtil.log(), is(notNullValue()));
    }

}