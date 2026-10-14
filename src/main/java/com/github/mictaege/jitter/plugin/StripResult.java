package com.github.mictaege.jitter.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Collects the outcome of a bytecode stripping run: the classes and members
 * that have been removed from the compiled sources, together with messages.
 *
 * The removed elements are meant to be verified afterwards: no surviving
 * class may reference them anymore, otherwise the flavour specific code
 * they belong to was not used consistently.
 */
public final class StripResult {

    private final TreeSet<String> removedClasses = new TreeSet<>();
    private final TreeSet<String> keptSourceClasses = new TreeSet<>();
    private final TreeSet<String> removedMembers = new TreeSet<>();
    private final List<String> removedFields = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> infos = new ArrayList<>();

    /**
     * The key a removed member is recorded under: {@code owner.name + descriptor}.
     */
    public static String memberKey(final String owner, final String name, final String desc) {
        return owner + "." + name + desc;
    }

    public void merge(final StripResult other) {
        removedClasses.addAll(other.removedClasses);
        removedMembers.addAll(other.removedMembers);
        removedFields.addAll(other.removedFields);
        errors.addAll(other.errors);
        infos.addAll(other.infos);
    }

    public void removedClass(final String internalName) {
        removedClasses.add(internalName);
    }

    public void removedMember(final String key) {
        removedMembers.add(key);
    }

    /**
     * A member that was recorded as removed but exists in the flavour
     * model again, like the implicit default constructor of a class whose
     * explicit constructors were all flavour specific (javac parity).
     */
    public void restoredMember(final String key) {
        removedMembers.remove(key);
    }

    public void removedField(final String owner, final String name, final String desc,
                             final boolean isStatic) {
        removedFields.add(owner + "|" + name + "|" + desc + "|" + (isStatic ? "S" : "I"));
    }

    public List<String> getRemovedFields() {
        return removedFields;
    }

    public void error(final String message) {
        errors.add(message);
    }

    public void info(final String message) {
        infos.add(message);
    }

    public TreeSet<String> getRemovedClasses() {
        return removedClasses;
    }

    /**
     * The internal names of the classes whose source files stay in the
     * dejittered variant, derived from the file names. The cross check of
     * the task compares them with the removal manifest: a class the
     * bytecode stripping removed must not have a kept source file.
     */
    public void keptSourceClass(final String internalName) {
        keptSourceClasses.add(internalName);
    }

    /**
     * Takes back a kept class record: the source was consumed as a template,
     * like an {@code @Alter} template that was dejittered before its target.
     */
    public void unkeepSourceClass(final String internalName) {
        keptSourceClasses.remove(internalName);
    }

    public TreeSet<String> getKeptSourceClasses() {
        return keptSourceClasses;
    }

    public TreeSet<String> getRemovedMembers() {
        return removedMembers;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getInfos() {
        return infos;
    }

}
