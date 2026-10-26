package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class StripResultTest {

    @Test
    void shouldKeyRemovedMembersByOwnerNameAndDescriptor() {
        assertThat(StripResult.memberKey("com/x/Y", "init", "()V"), is("com/x/Y.init()V"));
        assertThat(StripResult.memberKey("a/b/C", "field", "Ljava/lang/String;"),
                is("a/b/C.fieldLjava/lang/String;"));
    }

    @Test
    void shouldUnregisterRestoredMembers() {
        final StripResult result = new StripResult();
        result.removedMember(StripResult.memberKey("test/GatewayData", "<init>", "()V"));

        result.restoredMember(StripResult.memberKey("test/GatewayData", "<init>", "()V"));

        assertThat(result.getRemovedMembers()
                .contains(StripResult.memberKey("test/GatewayData", "<init>", "()V")), is(false));
    }

    @Test
    void shouldRecordRemovedClassesAndFields() {
        final StripResult result = new StripResult();
        result.removedClass("test/Gone");
        result.removedField("test/Owner", "f", "I", true);

        assertThat(result.getRemovedClasses().contains("test/Gone"), is(true));
        assertThat(result.getRemovedFields(), is(java.util.List.of("test/Owner|f|I|S")));
    }

    @Test
    void shouldMergeResults() {
        final StripResult one = new StripResult();
        one.removedClass("test/Gone");
        one.removedMember("test/Owner.m()V");
        one.removedField("test/Owner", "f", "I", false);
        one.error("an error");
        one.info("an info");
        final StripResult two = new StripResult();

        two.merge(one);

        assertThat(two.getRemovedClasses().contains("test/Gone"), is(true));
        assertThat(two.getRemovedMembers().contains("test/Owner.m()V"), is(true));
        assertThat(two.getRemovedFields().size(), is(1));
        assertThat(two.getErrors(), is(java.util.List.of("an error")));
        assertThat(two.getInfos(), is(java.util.List.of("an info")));
    }

}
