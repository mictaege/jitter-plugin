package com.github.mictaege.jitter.plugin;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class FlavourUtilTest {

    @Test
    public void shouldIndicateActiveIfNoProperty() {
        assertThat(FlavourUtil.active("X"), is(true));
    }

    @Test
    public void shouldIndicateActiveIfEmptyProperty() {
        System.setProperty("jitter.active.flavour", "");
        assertThat(FlavourUtil.active("X"), is(true));
    }

    @Test
    public void shouldIndicateInactiveIfOtherProperty() {
        System.setProperty("jitter.active.flavour", "Y");
        assertThat(FlavourUtil.active("X"), is(false));
    }

    @Test
    public void shouldIndicateActiveIfSameProperty() {
        System.setProperty("jitter.active.flavour", "X");
        assertThat(FlavourUtil.active("X"), is(true));
    }

    @Test
    public void shouldIndicateAnyIfPropertyPresent() {
        System.setProperty("jitter.active.flavour", "X");
        assertThat(FlavourUtil.anyVariant(), is(true));
    }

    @Test
    public void shouldIndicateNoneIfPropertyEmpty() {
        System.setProperty("jitter.active.flavour", "");
        assertThat(FlavourUtil.anyVariant(), is(false));
    }

    @Test
    public void shouldIndicateNoneIfNoProperty() {
        assertThat(FlavourUtil.anyVariant(), is(false));
    }

}