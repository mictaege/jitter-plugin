package com.github.mictaege.jitter.plugin;

import org.junit.Test;

import static com.github.mictaege.jitter.plugin.FlavourUtil.KEY;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class FlavourUtilTest {

    @Test
    public void shouldIndicateActiveIfNoProperty() {
        assertThat(FlavourUtil.active("X"), is(true));
    }

    @Test
    public void shouldIndicateActiveIfEmptyProperty() {
        System.setProperty(KEY, "");
        assertThat(FlavourUtil.active("X"), is(true));
    }

    @Test
    public void shouldIndicateInactiveIfOtherProperty() {
        System.setProperty(KEY, "Y");
        assertThat(FlavourUtil.active("X"), is(false));
    }

    @Test
    public void shouldIndicateActiveIfSameProperty() {
        System.setProperty(KEY, "X");
        assertThat(FlavourUtil.active("X"), is(true));
    }

    @Test
    public void shouldIndicateAnyIfPropertyPresent() {
        System.setProperty(KEY, "X");
        assertThat(FlavourUtil.anyVariant(), is(true));
    }

    @Test
    public void shouldIndicateNoneIfPropertyEmpty() {
        System.setProperty(KEY, "");
        assertThat(FlavourUtil.anyVariant(), is(false));
    }

    @Test
    public void shouldIndicateNoneIfNoProperty() {
        assertThat(FlavourUtil.anyVariant(), is(false));
    }

}