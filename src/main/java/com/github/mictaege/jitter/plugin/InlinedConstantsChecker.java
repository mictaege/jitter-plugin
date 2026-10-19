package com.github.mictaege.jitter.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * Verifies the usages of the constants that are marked
 * {@code @InlinedConstantApproved} - the source level counterpart of the bytecode
 * constant audit.
 *
 * <p>javac inlines the marked constants into their usage sites, so the
 * reference verification of the stripped bytecode cannot see those
 * usages anymore. This check parses the sources instead and enforces the
 * one rule that makes the inlining flavour consistent: every usage sits
 * on a member that is gated with a subset of the constant's
 * {@code @OnlyIf} flavours - the member is therefore removed together
 * with the constant in every flavour in which the constant is gone, and
 * no baked-in value outlives its gate.
 *
 * <p>The check resolves usages of the own sources as well as of marked
 * constants from dependent modules, whose markers and gates are read
 * from the compile classpath bytecode. Java sources - the Kotlin sources
 * verify through {@link KotlinUsageFinder} with the same rule. Name
 * resolution covers static imports (exact and wildcard), qualified and
 * simple type names via imports, the own package and the types of the
 * own source set, and inherited constants along the extends chain.
 * Usages via reflection are beyond the check and need manual attention.
 */
final class InlinedConstantsChecker {

    private static final String ONLY_IF = "OnlyIf";
    private static final String INLINED_CONSTANT = "InlinedConstantApproved";

    private final List<File> classpath;
    private final List<String> flavours;
    private final List<String> errors = new ArrayList<>();

    /** All types of the own source set, by fully qualified name. */
    private final Map<String, TypeDecl> ownTypes = new HashMap<>();

    /** The {@code @OnlyIf} gate of the own packages, by package name. */
    private final Map<String, List<String>> packageGates = new HashMap<>();

    /** Bytecode of classes that carry marked constants, by dotted name. */
    private final Map<String, ClassNode> externalClasses = new HashMap<>();

    /** The usages found so far: where, and which constant. */
    private final List<Usage> usages = new ArrayList<>();

    /** Gates that wait for the second indexing phase, see {@link #resolveGates()}. */
    private final List<PendingGate> pendingGates = new ArrayList<>();

    /**
     * The name resolution context of one compilation unit: its package and
     * type imports. The {@code @OnlyIf} gates are written with constant
     * references like {@code Flavour.MPK}, so reading a gate means
     * resolving those constants, which needs the unit's imports.
     */
    private static final class UnitCtx {
        private final String pkg;
        private final Map<String, String> singleTypeImports;
        private final List<String> wildcardTypeImports;
        /** identifier of an exact static import to the declaring class. */
        private final Map<String, String> exactStaticImports;
        private final List<String> wildcardStaticImports;
        /** the fully qualified names of the own file's types, nested ones with dots. */
        private final List<String> ownFileTypes;

        private UnitCtx(final String pkg, final Map<String, String> singleTypeImports,
                        final List<String> wildcardTypeImports,
                        final Map<String, String> exactStaticImports,
                        final List<String> wildcardStaticImports,
                        final List<String> ownFileTypes) {
            this.pkg = pkg;
            this.singleTypeImports = singleTypeImports;
            this.wildcardTypeImports = wildcardTypeImports;
            this.exactStaticImports = exactStaticImports;
            this.wildcardStaticImports = wildcardStaticImports;
            this.ownFileTypes = ownFileTypes;
        }
    }

    private static final class TypeDecl {
        String fqn;
        /** the JAVA package, no matter how deep the type is nested. */
        String pkg;
        TypeDecl outer;
        List<String> gate;
        String superName;
        boolean alterTarget;
        final Map<String, FieldDecl> fields = new HashMap<>();
    }

    private static final class FieldDecl {
        List<String> gate;
        boolean marked;
        String value;
    }

    private static final class Usage {
        private final Node node;
        private final String constantFqn;
        private final String field;
        private final List<String> constantGate;
        private final boolean alterTarget;
        private final UnitCtx ctx;
        private final String path;

        private Usage(final Node node, final String constantFqn, final String field,
                      final List<String> constantGate, final boolean alterTarget,
                      final UnitCtx ctx, final String path) {
            this.node = node;
            this.constantFqn = constantFqn;
            this.field = field;
            this.constantGate = constantGate;
            this.alterTarget = alterTarget;
            this.ctx = ctx;
            this.path = path;
        }
    }

    private static final class ResolvedConstant {
        private List<String> gate;
        private boolean marked;
        private boolean alterTarget;
    }

    InlinedConstantsChecker(final List<File> classpath, final List<String> flavours) {
        this.classpath = classpath;
        this.flavours = new ArrayList<>(flavours);
    }

    List<String> check(final List<Path> sourceDirs) throws IOException {
        final List<Path> files = new ArrayList<>();
        for (final Path dir : sourceDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (final Stream<Path> walk = Files.walk(dir)) {
                walk.filter(f -> f.toString().endsWith(".java")).forEach(files::add);
            }
        }
        final JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        final Map<Path, CompilationUnit> units = new LinkedHashMap<>();
        for (final Path file : files) {
            final ParseResult<CompilationUnit> result = parser.parse(file);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                units.put(file, result.getResult().get());
            } else {
                errors.add("Could not parse " + file + ": "
                        + result.getProblems().stream()
                        .map(Object::toString).collect(Collectors.joining("; ")));
            }
        }
        units.values().forEach(this::indexUnit);
        resolveGates();
        units.forEach((path, unit) -> findUsages(unit, path));
        usages.forEach(this::verifyUsage);
        return errors;
    }

    // ------------------------------------------------------------------
    // indexing: the own types, their fields and the package gates

    private void indexUnit(final CompilationUnit unit) {
        final String pkg = unit.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        final UnitCtx ctx = unitCtxOf(unit, pkg);
        // a package-info.java carries the package gate on its package
        // declaration and has no type declaration at all
        if (unit.getTypes().isEmpty()) {
            unit.getPackageDeclaration().ifPresent(p ->
                    pendingGates.add(new PendingGate(null, p.getAnnotations(), ctx, pkg)));
            return;
        }
        for (final TypeDeclaration<?> type : unit.getTypes()) {
            indexType(type, pkg, ctx);
        }
    }

    private void indexType(final TypeDeclaration<?> type, final String prefix,
                           final UnitCtx ctx) {
        final String fqn = prefix.isEmpty() ? type.getNameAsString()
                : prefix + "." + type.getNameAsString();
        final TypeDecl decl = new TypeDecl();
        decl.fqn = fqn;
        // nested types are indexed with dots, so the package cannot be
        // derived from the fqn - the outer type knows it
        decl.pkg = ownTypes.containsKey(prefix) ? ownTypes.get(prefix).pkg
                : prefix.contains(".") ? prefix : prefix;
        decl.outer = ownTypes.get(prefix);
        pendingGates.add(new PendingGate(decl, type.getAnnotations(), ctx, null));
        decl.alterTarget = type.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().endsWith("Alter"));
        if (type instanceof ClassOrInterfaceDeclaration) {
            final ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) type;
            if (!clazz.getExtendedTypes().isEmpty()) {
                decl.superName = clazz.getExtendedTypes().get(0).getNameAsString();
            }
        }
        for (final FieldDeclaration field : type.getFields()) {
            final FieldDecl info = new FieldDecl();
            pendingGates.add(new PendingGate(info, field.getAnnotations(), ctx, null));
            info.marked = isMarked(field.getAnnotations());
            for (final VariableDeclarator var : field.getVariables()) {
                decl.fields.put(var.getNameAsString(), info);
                final Expression initializer = var.getInitializer().orElse(null);
                if (initializer instanceof StringLiteralExpr) {
                    info.value = ((StringLiteralExpr) initializer).getValue();
                }
            }
        }
        ownTypes.put(fqn, decl);
        for (final TypeDeclaration<?> nested : type.getMembers().stream()
                .filter(m -> m instanceof TypeDeclaration)
                .map(m -> (TypeDeclaration<?>) m)
                .collect(Collectors.toList())) {
            indexType(nested, fqn, ctx);
        }
    }

    /**
     * Resolves the {@code @OnlyIf} gates of the indexed types, fields and
     * packages. This runs as a second phase, after every unit is indexed:
     * the gate values are constant references like {@code Flavour.MPK}, so
     * resolving them needs the whole own type index in place.
     */
    private void resolveGates() {
        for (final PendingGate pending : pendingGates) {
            final List<String> gate = gateOf(pending.annotations, pending.ctx);
            if (pending.target instanceof TypeDecl) {
                ((TypeDecl) pending.target).gate = gate;
            } else if (pending.target instanceof FieldDecl) {
                ((FieldDecl) pending.target).gate = gate;
            } else if (gate != null) {
                packageGates.put(pending.pkg, gate);
            }
        }
        pendingGates.clear();
    }

    /** An {@code @OnlyIf} gate that waits for the second indexing phase. */
    private static final class PendingGate {
        private final Object target;
        private final List<AnnotationExpr> annotations;
        private final UnitCtx ctx;
        private final String pkg;

        private PendingGate(final Object target, final List<AnnotationExpr> annotations,
                            final UnitCtx ctx, final String pkg) {
            this.target = target;
            this.annotations = annotations;
            this.ctx = ctx;
            this.pkg = pkg;
        }
    }

    // ------------------------------------------------------------------
    // finding the usages of marked constants

    private UnitCtx unitCtxOf(final CompilationUnit unit, final String pkg) {
        final Map<String, String> singleTypeImports = new HashMap<>();
        final List<String> wildcardTypeImports = new ArrayList<>();
        final Map<String, String> exactStaticImports = new HashMap<>();
        final List<String> wildcardStaticImports = new ArrayList<>();
        for (final ImportDeclaration imp : unit.getImports()) {
            final String name = imp.getNameAsString();
            if (imp.isStatic()) {
                final int idx = name.lastIndexOf('.');
                if ("*".equals(name.substring(idx + 1))) {
                    wildcardStaticImports.add(name.substring(0, idx));
                } else {
                    exactStaticImports.put(name.substring(idx + 1), name.substring(0, idx));
                }
            } else if (name.endsWith(".*")) {
                wildcardTypeImports.add(name.substring(0, name.length() - 2));
            } else {
                final int idx = name.lastIndexOf('.');
                singleTypeImports.put(name.substring(idx + 1), name);
            }
        }
        final List<String> ownFileTypes = new ArrayList<>();
        for (final TypeDeclaration<?> type : unit.getTypes()) {
            collectOwnFileTypes(type, pkg, ownFileTypes);
        }
        return new UnitCtx(pkg, singleTypeImports, wildcardTypeImports,
                exactStaticImports, wildcardStaticImports, ownFileTypes);
    }

    private void collectOwnFileTypes(final TypeDeclaration<?> type, final String prefix,
                                      final List<String> out) {
        final String fqn = prefix.isEmpty() ? type.getNameAsString()
                : prefix + "." + type.getNameAsString();
        out.add(fqn);
        for (final TypeDeclaration<?> nested : type.getMembers().stream()
                .filter(m -> m instanceof TypeDeclaration)
                .map(m -> (TypeDeclaration<?>) m)
                .collect(Collectors.toList())) {
            collectOwnFileTypes(nested, fqn, out);
        }
    }

    private void findUsages(final CompilationUnit unit, final Path path) {
        final String pkg = unit.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        final UnitCtx ctx = unitCtxOf(unit, pkg);
        final Map<String, String> exactStaticImports = new HashMap<>();
        final List<String> wildcardStaticImports = new ArrayList<>();
        for (final ImportDeclaration imp : unit.getImports()) {
            final String name = imp.getNameAsString();
            if (!imp.isStatic()) {
                continue;
            }
            final int idx = name.lastIndexOf('.');
            if ("*".equals(name.substring(idx + 1))) {
                wildcardStaticImports.add(name.substring(0, idx));
            } else {
                exactStaticImports.put(name.substring(idx + 1), name.substring(0, idx));
            }
        }

        // qualified usages: Type.FIELD
        for (final FieldAccessExpr access : unit.findAll(FieldAccessExpr.class)) {
            final String field = access.getNameAsString();
            final Set<String> candidates = candidatesForScope(access.getScope(), ctx);
            for (final String candidate : candidates) {
                final ResolvedConstant constant = resolveStatic(candidate, field);
                if (constant == null) {
                    continue;
                }
                // the first visible declaration wins, marked or not:
                // what javac resolves is what gets inlined, and an
                // unmarked declaration is not flavour variant
                if (constant.marked) {
                    usages.add(new Usage(access, candidate, field, constant.gate,
                            constant.alterTarget, ctx, path.toString()));
                }
                break;
            }
        }

        // bare usages: static imports and inherited constants
        for (final NameExpr name : unit.findAll(NameExpr.class)) {
            if (name.getNameAsString().contains(".")) {
                continue;
            }
            // a name that is only the scope of a field access is the type,
            // not a usage
            if (name.getParentNode().isPresent()
                    && name.getParentNode().get() instanceof FieldAccessExpr
                    && ((FieldAccessExpr) name.getParentNode().get()).getScope() == name) {
                continue;
            }
            final String field = name.getNameAsString();
            final Set<String> candidates = new LinkedHashSet<>();
            if (exactStaticImports.containsKey(field)) {
                candidates.add(exactStaticImports.get(field));
            }
            candidates.addAll(wildcardStaticImports);
            inheritedCandidates(name).forEach(candidates::add);
            for (final String candidate : candidates) {
                final ResolvedConstant constant = resolveStatic(candidate, field);
                if (constant == null) {
                    continue;
                }
                // the nearest declaration wins, marked or not: an own or
                // shadowing constant is what javac inlines, and an unmarked
                // one is not flavour variant
                if (constant.marked) {
                    usages.add(new Usage(name, candidate, field, constant.gate,
                            constant.alterTarget, ctx, path.toString()));
                }
                break;
            }
        }
    }

    /**
     * The type names a field access scope could refer to. A scope like
     * {@code p.q.C} or {@code Outer.Inner} is a chain of field accesses
     * with a name at its root; the root is resolved via the imports, the
     * own package and the own types, the rest of the chain is appended.
     */
    private Set<String> candidatesForScope(final Expression scope, final UnitCtx ctx) {
        final Set<String> candidates = new LinkedHashSet<>();
        final List<String> parts = new ArrayList<>();
        Expression current = scope;
        while (current instanceof FieldAccessExpr) {
            parts.add(0, ((FieldAccessExpr) current).getNameAsString());
            current = ((FieldAccessExpr) current).getScope();
        }
        if (!(current instanceof NameExpr)) {
            return candidates;
        }
        parts.add(0, ((NameExpr) current).getNameAsString());
        final String root = parts.get(0);
        final String full = String.join(".", parts);
        final String tail = parts.size() > 1
                ? "." + String.join(".", parts.subList(1, parts.size())) : "";
        if (root.contains(".")) {
            // a fully qualified name: p.C.FIELD or p.q.C.FIELD
            candidates.add(full);
            return candidates;
        }
        // the own file's types shadow everything else: a nested class of
        // the same compilation unit wins over same-named types anywhere
        ctx.ownFileTypes.stream()
                .filter(t -> t.endsWith("." + root) || t.equals(root))
                .forEach(t -> candidates.add(t + tail.substring(0, tail.contains(".") ? tail.length() : 0)));
        ctx.ownFileTypes.stream()
                .filter(t -> t.endsWith("." + full) || t.equals(full))
                .forEach(candidates::add);
        if (ctx.singleTypeImports.containsKey(root)) {
            candidates.add(ctx.singleTypeImports.get(root) + tail);
        }
        candidates.add(ctx.pkg.isEmpty() ? full : ctx.pkg + "." + full);
        ctx.wildcardTypeImports.forEach(imported -> candidates.add(imported + "." + full));
        // the chain may still be a fully qualified name whose root is a
        // package segment, like test.C.FIELD: the own file types and the
        // imports above win over it
        candidates.add(full);
        return candidates;
    }

    /**
     * The declaration classes a bare name could refer to along the
     * enclosing type and its extends chain: an inherited constant is
     * inlined by javac just like an own one.
     */
    private Set<String> inheritedCandidates(final Node node) {
        final Set<String> candidates = new LinkedHashSet<>();
        final Set<String> seen = new HashSet<>();
        TypeDecl own = enclosingType(node);
        String externalName = null;
        while (own != null && seen.add(own.fqn)) {
            candidates.add(own.fqn);
            final TypeDecl next = resolveOwnByName(own.superName);
            if (next != null) {
                own = next;
            } else {
                externalName = own.superName;
                own = null;
            }
        }
        while (externalName != null && seen.add(externalName)) {
            candidates.add(externalName);
            final ClassNode external = externalClass(externalName);
            if (external == null) {
                break;
            }
            externalName = external.superName == null ? null
                    : external.superName.replace('/', '.');
        }
        return candidates;
    }

    private TypeDecl resolveOwnByName(final String name) {
        if (name == null) {
            return null;
        }
        return ownTypes.values().stream()
                .filter(t -> t.fqn.equals(name) || t.fqn.endsWith("." + name))
                .findFirst().orElse(null);
    }

    private TypeDecl enclosingType(final Node node) {
        final List<String> names = new ArrayList<>();
        String pkg = null;
        for (Node parent = node; parent != null; parent = parent.getParentNode().orElse(null)) {
            if (parent instanceof TypeDeclaration) {
                names.add(0, ((TypeDeclaration<?>) parent).getNameAsString());
            } else if (parent instanceof CompilationUnit) {
                pkg = ((CompilationUnit) parent).getPackageDeclaration()
                        .map(p -> p.getNameAsString()).orElse("");
            }
        }
        if (names.isEmpty() || pkg == null) {
            return null;
        }
        final String fqn = (pkg.isEmpty() ? "" : pkg + ".") + String.join(".", names);
        return ownTypes.get(fqn);
    }

    // ------------------------------------------------------------------
    // resolving a constant declaration, own sources first, classpath after

    private ResolvedConstant resolveStatic(final String fqn, final String field) {
        final TypeDecl own = ownTypes.get(fqn);
        if (own != null) {
            final FieldDecl decl = own.fields.get(field);
            if (decl != null) {
                final ResolvedConstant result = new ResolvedConstant();
                List<String> effective = decl.gate;
                for (TypeDecl enclosing = own; enclosing != null;
                        enclosing = enclosing.outer) {
                    effective = intersect(effective, enclosing.gate);
                }
                result.gate = intersect(effective, packageGates.get(own.pkg));
                result.marked = decl.marked;
                result.alterTarget = own.alterTarget;
                return result;
            }
            return null;
        }
        final ClassNode node = externalClass(fqn);
        if (node == null) {
            return null;
        }
        for (final FieldNode f : node.fields) {
            if (f.name.equals(field) && (f.access & Opcodes.ACC_STATIC) != 0) {
                final ResolvedConstant result = new ResolvedConstant();
                List<String> effective = intersect(
                        JitterAnnotations.onlyIfFlavours(f.visibleAnnotations),
                        JitterAnnotations.onlyIfFlavours(node.visibleAnnotations));
                String internal = node.name;
                int dollar = internal.lastIndexOf('$');
                while (dollar > 0) {
                    final ClassNode outerNode = ClassIndex.loadFromClasspath(classpath,
                            internal.substring(0, dollar), new StripResult());
                    if (outerNode != null) {
                        effective = intersect(effective,
                                JitterAnnotations.onlyIfFlavours(outerNode.visibleAnnotations));
                    }
                    internal = internal.substring(0, dollar);
                    dollar = internal.lastIndexOf('$');
                }
                result.gate = intersect(effective, externalPackageGate(node));
                result.marked = JitterAnnotations.isInlinedConstantApproved(f.visibleAnnotations);
                result.alterTarget =
                        !JitterAnnotations.alterAnnotations(node.visibleAnnotations).isEmpty();
                return result;
            }
        }
        return null;
    }

    /**
     * The intersection of two gates, case-insensitively. A null gate
     * contributes nothing - the member has no gate on that level.
     */
    private static List<String> intersect(List<String> a, final List<String> b) {
        if (b == null || b.isEmpty()) {
            return a;
        }
        if (a == null) {
            return new ArrayList<>(b);
        }
        final List<String> lower = b.stream()
                .map(g -> g.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
        a = a.stream()
                .filter(g -> lower.contains(g.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        return a;
    }

    /**
     * The package gate of the package the given class lives in. The
     * package-info is read from the SAME classpath entry as the declaring
     * class: packages can be split across artifacts with different gates,
     * and only the declaring artifact's own package-info governs its
     * classes.
     */
    private List<String> externalPackageGate(final ClassNode node) {
        final int idx = node.name.lastIndexOf('/');
        if (idx <= 0) {
            return null;
        }
        final File entry = ClassIndex.entryOf(classpath, node.name);
        if (entry == null) {
            return null;
        }
        try {
            final byte[] bytes = ClassIndex.readFromEntry(entry,
                    node.name.substring(0, idx) + "/package-info");
            if (bytes == null) {
                return null;
            }
            final ClassNode info = new ClassNode();
            new org.objectweb.asm.ClassReader(bytes).accept(info, 0);
            return JitterAnnotations.onlyIfFlavours(info);
        } catch (final IOException e) {
            return null;
        }
    }

    private ClassNode externalClass(final String fqn) {
        if (externalClasses.containsKey(fqn)) {
            return externalClasses.get(fqn);
        }
        ClassNode node = null;
        // a source name may address a nested class: p.q.Outer.Inner lives
        // in the bytecode as p/q/Outer$Inner, so try every dot as a dollar
        final int last = fqn.lastIndexOf('.');
        for (int idx = last; idx > 0 && node == null; idx = fqn.lastIndexOf('.', idx - 1)) {
            final String internal = fqn.substring(0, idx).replace('.', '/')
                    + '$' + fqn.substring(idx + 1).replace('.', '$');
            node = ClassIndex.loadFromClasspath(classpath, internal, new StripResult());
        }
        if (node == null) {
            node = ClassIndex.loadFromClasspath(classpath,
                    fqn.replace('.', '/'), new StripResult());
        }
        externalClasses.put(fqn, node);
        return node;
    }

    // ------------------------------------------------------------------
    // the gate subset rule

    private void verifyUsage(final Usage usage) {
        if (usage.constantGate == null) {
            verifyAlteredUsage(usage);
            return;
        }
        final Set<String> surviving = flavours.stream()
                .map(f -> f.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Node root = usage.node;
        String pkg = "";
        for (Node parent = usage.node; parent != null;
                parent = parent.getParentNode().orElse(null)) {
            root = parent;
            if (parent instanceof CompilationUnit) {
                pkg = ((CompilationUnit) parent).getPackageDeclaration()
                        .map(p -> p.getNameAsString()).orElse("");
            }
        }
        final List<String> packageGate = packageGates.get(pkg);
        if (packageGate != null) {
            retain(surviving, packageGate);
        }
        for (Node node = usage.node; node != null && node != root;
                node = node.getParentNode().orElse(null)) {
            if (node instanceof NodeWithAnnotations) {
                final List<String> gate = gateOf(
                        ((NodeWithAnnotations<?>) node).getAnnotations(), usage.ctx);
                if (gate != null) {
                    retain(surviving, gate);
                }
            }
        }
        final List<String> constantGate = usage.constantGate == null
                ? new ArrayList<>() : usage.constantGate;
        final Set<String> leaking = surviving.stream()
                .filter(f -> constantGate.stream().noneMatch(g ->
                        g.toLowerCase(Locale.ROOT).equals(f)))
                .collect(Collectors.toSet());
        if (!leaking.isEmpty()) {
            final int line = usage.node.getBegin()
                    .map(p -> p.line).orElse(-1);
            final String member = memberOf(usage.node);
            errors.add(usage.path + ":" + line
                    + ": the usage of " + usage.constantFqn + "#" + usage.field
                    + (member.isEmpty() ? "" : " in " + member)
                    + " is kept in the flavours " + sorted(leaking)
                    + " where the constant is removed by its @OnlyIf gate - the"
                    + " inlined value would leak. Gate the usage with a subset"
                    + " of the constant's flavours.");
        }
    }

    /**
     * A marked constant without an {@code @OnlyIf} gate is a constant of a
     * flavour-altered class: its inlined values are only sound inside
     * annotations, where the re-bake replaces them with the active
     * flavour's template values. A usage in a method body would keep the
     * full model value whatever flavour is active.
     */
    private void verifyAlteredUsage(final Usage usage) {
        boolean inAnnotation = false;
        for (Node node = usage.node; node != null; node = node.getParentNode().orElse(null)) {
            if (node instanceof AnnotationExpr) {
                inAnnotation = true;
                break;
            }
        }
        if (!inAnnotation) {
            final int line = usage.node.getBegin().map(p -> p.line).orElse(-1);
            errors.add(usage.path + ":" + line
                    + ": the usage of " + usage.constantFqn + "#" + usage.field
                    + (usage.alterTarget
                        ? " is outside an annotation, but the inlined value of a"
                        + " flavour-altered constant is only re-baked inside"
                        + " annotations - a body usage keeps the full model value"
                        + " whatever flavour is active. Use the constant in"
                        + " annotations only or make it non-inlinable."
                        : " has no @OnlyIf gate and is not flavour-altered - the"
                        + " marker is meaningless, see the constant audit."));
        }
    }

    private void retain(final Set<String> surviving, final List<String> gate) {
        final Set<String> allowed = gate.stream()
                .map(g -> g.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        surviving.retainAll(allowed);
    }

    private String memberOf(final Node node) {
        for (Node parent = node; parent != null; parent = parent.getParentNode().orElse(null)) {
            if (parent instanceof MethodDeclaration) {
                return ((MethodDeclaration) parent).getDeclarationAsString();
            }
            if (parent instanceof ConstructorDeclaration) {
                return ((ConstructorDeclaration) parent).getDeclarationAsString();
            }
            if (parent instanceof FieldDeclaration) {
                return "the initializer of "
                        + ((FieldDeclaration) parent).getVariables().get(0);
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // annotation reading in the source AST

    private static boolean isMarked(final List<AnnotationExpr> annotations) {
        for (final AnnotationExpr annotation : annotations) {
            if (annotation.getNameAsString().endsWith(INLINED_CONSTANT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the flavours of an {@code @OnlyIf} gate from the source AST.
     * The values are often no string literals but constant references like
     * {@code Flavour.MPK} - javac would not accept the annotation otherwise
     * - so every value is also resolved through the constant machinery:
     * the own source index first, the classpath bytecode after.
     */
    private List<String> gateOf(final List<AnnotationExpr> annotations, final UnitCtx ctx) {
        List<String> gate = null;
        for (final AnnotationExpr annotation : annotations) {
            if (!annotation.getNameAsString().endsWith(ONLY_IF)) {
                continue;
            }
            final List<String> flavours = new ArrayList<>();
            if (annotation instanceof SingleMemberAnnotationExpr) {
                flavoursOf(((SingleMemberAnnotationExpr) annotation).getMemberValue(),
                        flavours, ctx);
            } else if (annotation instanceof NormalAnnotationExpr) {
                ((NormalAnnotationExpr) annotation).getPairs()
                        .forEach(pair -> flavoursOf(pair.getValue(), flavours, ctx));
            }
            if (gate == null) {
                gate = flavours;
            } else {
                gate.addAll(flavours);
            }
        }
        return gate;
    }

    private void flavoursOf(final Expression value, final List<String> flavours,
                           final UnitCtx ctx) {
        if (value instanceof StringLiteralExpr) {
            flavours.add(((StringLiteralExpr) value).getValue());
        } else if (value instanceof ArrayInitializerExpr) {
            ((ArrayInitializerExpr) value).getValues()
                    .forEach(v -> flavoursOf(v, flavours, ctx));
        } else if (value instanceof FieldAccessExpr) {
            final String flavour = constantValueOf((FieldAccessExpr) value, ctx);
            if (flavour != null) {
                flavours.add(flavour);
            }
        } else if (value instanceof NameExpr) {
            final String flavour = constantValueOf((NameExpr) value, ctx);
            if (flavour != null) {
                flavours.add(flavour);
            }
        }
    }

    /**
     * The string value of a constant reference like {@code Flavour.MPK}:
     * the declaration is resolved through the own types or the classpath
     * bytecode, where the javac inlined value is stored.
     */
    private String constantValueOf(final FieldAccessExpr access, final UnitCtx ctx) {
        final String field = access.getNameAsString();
        final Set<String> candidates = candidatesForScope(access.getScope(), ctx);
        for (final String candidate : candidates) {
            final String value = constantValueOf(candidate, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String constantValueOf(final NameExpr name, final UnitCtx ctx) {
        final String field = name.getNameAsString();
        // gate values are often static-imported flavour constants
        if (ctx.exactStaticImports.containsKey(field)) {
            final String value =
                    constantValueOf(ctx.exactStaticImports.get(field), field);
            if (value != null) {
                return value;
            }
        }
        for (final String imported : ctx.wildcardStaticImports) {
            final String value = constantValueOf(imported, field);
            if (value != null) {
                return value;
            }
        }
        final Set<String> candidates = new LinkedHashSet<>();
        ownTypes.values().stream()
                .filter(t -> t.fields.containsKey(field))
                .forEach(t -> candidates.add(t.fqn));
        for (final String candidate : candidates) {
            final String value = constantValueOf(candidate, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String constantValueOf(final String fqn, final String field) {
        final TypeDecl own = ownTypes.get(fqn);
        if (own != null) {
            final FieldDecl decl = own.fields.get(field);
            return decl == null ? null : decl.value;
        }
        final ClassNode node = externalClass(fqn);
        if (node == null) {
            return null;
        }
        for (final FieldNode f : node.fields) {
            if (f.name.equals(field) && f.value instanceof String) {
                return (String) f.value;
            }
        }
        return null;
    }

    private static String sorted(final Set<String> flavours) {
        return Arrays.toString(flavours.stream().sorted().toArray());
    }

}
