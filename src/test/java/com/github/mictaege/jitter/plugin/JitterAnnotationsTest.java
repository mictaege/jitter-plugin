package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class JitterAnnotationsTest extends StripperTestFixture {

    private static final String ONLY_IF = "Lcom/github/mictaege/jitter/api/OnlyIf;";
    private static final String FORK = "Lcom/github/mictaege/jitter/api/Fork;";
    private static final String GATEWAY = "Lcom/github/mictaege/jitter/api/Gateway;";
    private static final String ALTER = "Lcom/github/mictaege/jitter/api/Alter;";
    private static final String ALTERNATIVES = "Lcom/github/mictaege/jitter/api/Alternatives;";

    @Test
    void shouldReadTheFlavoursOfAnOnlyIfAnnotation() {
        assertThat(JitterAnnotations.onlyIfFlavours(List.of(onlyIf("ESA"))), is(List.of("ESA")));
        assertThat(JitterAnnotations.onlyIfFlavours(List.of(onlyIf("ESA", "NASA"))), is(List.of("ESA", "NASA")));
        assertThat(JitterAnnotations.onlyIfFlavours((List<AnnotationNode>) null), is(nullValue()));

        final ClassNode node = new ClassNode();
        node.visibleAnnotations = new ArrayList<>(List.of(onlyIf("NASA")));
        assertThat(JitterAnnotations.onlyIfFlavours(node), is(List.of("NASA")));
    }

    @Test
    void shouldDetectInactiveAnnotations() {
        assertThat(JitterAnnotations.isInactive(List.of(onlyIf("ESA"))), is(false));
        assertThat(JitterAnnotations.isInactive(List.of(onlyIf("ESA", "NASA"))), is(false));
        assertThat(JitterAnnotations.isInactive(List.of(onlyIf("NASA"))), is(true));
        assertThat(JitterAnnotations.isInactive((List<AnnotationNode>) null), is(false));
    }

    @Test
    void shouldFindTheForkGatewayOfTheActiveFlavour() {
        final AnnotationNode esa = fork("ESA", "initEsa");
        final AnnotationNode nasa = fork("NASA", "initNasa");

        assertThat(JitterAnnotations.forkGateway(List.of(esa, nasa)), is(esa));
        assertThat(JitterAnnotations.forkGateway(List.of(nasa)), is(nullValue()));
        assertThat(JitterAnnotations.forkGateway((List<AnnotationNode>) null), is(nullValue()));
    }

    @Test
    void shouldFindForkGatewaysInRepeatableContainers() {
        final AnnotationNode esa = fork("ESA", "initEsa");
        final AnnotationNode nasa = fork("NASA", "initNasa");
        final AnnotationNode container = new AnnotationNode(GATEWAY);
        container.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(esa, nasa))));

        assertThat(JitterAnnotations.forkGateway(List.of(container)), is(esa));
    }

    @Test
    void shouldFindTheAlterTargetOfTheActiveFlavour() {
        final AnnotationNode esa = alter("ESA", "TargetEsa");
        final AnnotationNode nasa = alter("NASA", "TargetNasa");

        assertThat(JitterAnnotations.alterTarget(List.of(esa, nasa)), is(esa));
        assertThat(JitterAnnotations.alterTarget(List.of(nasa)), is(nullValue()));
    }

    @Test
    void shouldIgnoreAnnotationsWithoutTheExpectedValue() {
        final AnnotationNode broken = fork("ESA", null);

        assertThat(JitterAnnotations.forkGateway(List.of(broken)), is(nullValue()));
    }

    @Test
    void shouldReadAnnotationValues() {
        final AnnotationNode annotation = new AnnotationNode("Ltest/Some;");
        annotation.values = new ArrayList<>(List.of("single", "a", "many", new ArrayList<>(List.of("b", "c"))));

        assertThat(JitterAnnotations.annotationValue(annotation, "single"), is("a"));
        assertThat(JitterAnnotations.annotationValues(annotation, "many"), is(List.of("b", "c")));
        assertThat(JitterAnnotations.annotationValues(annotation, "single"), is(List.of("a")));
        assertThat(JitterAnnotations.annotationValues(annotation, "missing"), is(List.of()));
        assertThat(JitterAnnotations.annotationValue(annotation, "missing"), is(nullValue()));
    }

    @Test
    void shouldRemoveTheJitterAnnotationsAndKeepTheOthers() {
        final AnnotationNode onlyIf = onlyIf("ESA");
        final AnnotationNode other = new AnnotationNode("Ltest/Other;");

        final List<AnnotationNode> annotations = new ArrayList<>(List.of(onlyIf, other));
        assertThat(JitterAnnotations.removeJitterAnnotations(annotations), is(true));
        assertThat(annotations, is(List.of(other)));

        assertThat(JitterAnnotations.removeJitterAnnotations(new ArrayList<>(List.of(other))), is(false));
        assertThat(JitterAnnotations.removeJitterAnnotations(null), is(false));
    }

    @Test
    void shouldDeriveThePackageOfAnInternalName() {
        assertThat(JitterAnnotations.packageOf("a/b/C"), is("a/b/"));
        assertThat(JitterAnnotations.packageOf("C"), is(""));
    }

    private AnnotationNode onlyIf(final String... flavours) {
        final AnnotationNode annotation = new AnnotationNode(ONLY_IF);
        annotation.values = new ArrayList<>();
        if (flavours.length == 1) {
            annotation.values.add("value");
            annotation.values.add(flavours[0]);
        } else {
            annotation.values.add("value");
            annotation.values.add(new ArrayList<>(List.of(flavours)));
        }
        return annotation;
    }

    private AnnotationNode fork(final String flavour, final String to) {
        final AnnotationNode annotation = new AnnotationNode(FORK);
        annotation.values = new ArrayList<>();
        annotation.values.add("ifActive");
        annotation.values.add(flavour);
        annotation.values.add("to");
        annotation.values.add(to);
        return annotation;
    }

    private AnnotationNode alter(final String flavour, final String with) {
        final AnnotationNode annotation = new AnnotationNode(ALTER);
        annotation.values = new ArrayList<>();
        annotation.values.add("ifActive");
        annotation.values.add(flavour);
        annotation.values.add("with");
        annotation.values.add(with);
        return annotation;
    }

}
