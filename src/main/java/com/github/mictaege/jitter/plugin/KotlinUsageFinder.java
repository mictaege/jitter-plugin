package com.github.mictaege.jitter.plugin;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.kotlin.cli.CompilerConfigurationCreationKt;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtEnumEntry;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;
import org.jetbrains.kotlin.psi.KtTypeReference;
import org.jetbrains.kotlin.psi.KtValueArgument;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * The Kotlin counterpart of {@link InlinedConstantsChecker}: verifies the
 * usages of the constants that are marked
 * {@code @InlinedConstantApproved} in the Kotlin sources of a source set.
 *
 * <p>This class references the Kotlin PSI and is loaded in an isolated
 * classloader in the build (see {@link IsolatedKotlinUsageFinder}) - the
 * kotlin compiler embeddable is deliberately no runtime dependency of the
 * plugin, it would clash with the Kotlin toolchain of Kotlin based builds.
 * In the tests it is on the classpath and used directly. All shared plugin
 * types load parent first across the loader boundary, so the bytecode
 * resolution helpers are the same as everywhere in the plugin.
 *
 * <p>The own Kotlin sources are indexed for declarations and their gates;
 * everything else - constants of dependent modules, constants of the own
 * Java sources, package gates - is resolved from the bytecode on the
 * classpath, which carries the own compiled classes first. Name resolution
 * covers qualified receivers, companion members, top level constants of the
 * own file and imports of constants; top level constants of OTHER files
 * used without import are a known gap, they resolve to no declaration and
 * are therefore never falsely reported.
 */
public final class KotlinUsageFinder implements AutoCloseable {

    /**
     * The PSI of the kotlin compiler works on JVM wide singletons (the
     * application and its extension areas): parallel tasks of one daemon
     * serialize their PSI work on this lock.
     */
    private static final Object PSI_LOCK = new Object();

    private final List<File> classpath;
    private final List<String> flavours;
    private final List<String> errors = new ArrayList<>();

    private final Map<String, TypeDecl> ownTypes = new HashMap<>();
    private final Map<String, ClassNode> bytecode = new HashMap<>();
    private final Map<String, List<String>> packageGates = new HashMap<>();
    private final List<PendingGate> pendingGates = new ArrayList<>();
    private final List<Usage> usages = new ArrayList<>();
    private final ClasspathConstants constants;

    // created lazily: only builds that actually have Kotlin sources pay for it
    private KtPsiFactory factory;
    private Application app;
    private Disposable disposable;

    private static final class TypeDecl {
        private final String fqn;
        private final String pkg;
        private final boolean facade;
        private boolean alterTarget;
        private List<String> gate;
        private String companionFqn;
        private final Map<String, PropDecl> props = new HashMap<>();

        private TypeDecl(final String fqn, final String pkg, final boolean facade) {
            this.fqn = fqn;
            this.pkg = pkg;
            this.facade = facade;
        }
    }

    private static final class PropDecl {
        private List<String> gate;
        private boolean marked;
        private boolean alterConst;
    }

    private static final class PendingGate {
        private final Object target;
        private final KtAnnotationEntry onlyIf;
        private final FileCtx ctx;

        private PendingGate(final Object target, final KtAnnotationEntry onlyIf,
                            final FileCtx ctx) {
            this.target = target;
            this.onlyIf = onlyIf;
            this.ctx = ctx;
        }
    }

    private static final class Usage {
        private final KtExpression expr;
        private final String constantFqn;
        private final String field;
        private final List<String> constantGate;
        private final boolean alterTarget;
        private final FileCtx ctx;
        private final String path;

        private Usage(final KtExpression expr, final String constantFqn, final String field,
                      final List<String> constantGate, final boolean alterTarget,
                      final FileCtx ctx, final String path) {
            this.expr = expr;
            this.constantFqn = constantFqn;
            this.field = field;
            this.constantGate = constantGate;
            this.alterTarget = alterTarget;
            this.ctx = ctx;
            this.path = path;
        }
    }

    private static final class Resolved {
        private final List<String> gate;
        private final boolean marked;
        private final boolean alterTarget;

        private Resolved(final List<String> gate, final boolean marked, final boolean alterTarget) {
            this.gate = gate;
            this.marked = marked;
            this.alterTarget = alterTarget;
        }
    }

    public KotlinUsageFinder(final List<File> classpath, final List<String> flavours) {
        this.classpath = classpath == null ? new ArrayList<>() : classpath;
        this.flavours = flavours == null ? new ArrayList<>() : new ArrayList<>(flavours);
        this.constants = new ClasspathConstants(this.classpath);
    }

    /**
     * Verifies the given Kotlin files and returns the errors, in the format
     * of the Java source check so that both appear uniform to the user.
     */
    public List<String> check(final List<Path> ktFiles) throws IOException {
        final List<KtFile> units = new ArrayList<>();
        final List<FileCtx> contexts = new ArrayList<>();
        synchronized (PSI_LOCK) {
            init();
            try {
                app.runWriteAction((Runnable) () -> {
                    for (final Path file : ktFiles) {
                        final String text;
                        try {
                            text = Files.readString(file, StandardCharsets.UTF_8);
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        final KtFile unit = factory.createFile(
                                file.getFileName().toString(), text);
                        units.add(unit);
                        contexts.add(new FileCtx(unit, file.getFileName().toString(),
                                text, file.toString()));
                    }
                    for (int i = 0; i < units.size(); i++) {
                        index(units.get(i), contexts.get(i));
                    }
                    resolveGates();
                    for (int i = 0; i < units.size(); i++) {
                        findUsages(units.get(i), contexts.get(i));
                    }
                    usages.forEach(this::verifyUsage);
                });
            } catch (final UncheckedIOException e) {
                throw e.getCause();
            }
        }
        return errors;
    }

    @Override
    public void close() {
        synchronized (PSI_LOCK) {
            if (disposable != null) {
                final Application environment = app;
                if (environment != null) {
                    environment.runWriteAction((Runnable) () -> Disposer.dispose(disposable));
                }
                disposable = null;
                factory = null;
                app = null;
            }
        }
        constants.close();
    }

    private void init() {
        if (factory != null) {
            return;
        }
        // standalone use of the embeddable compiler: the platform expects an
        // installation home directory (product-info.json) that does not exist
        // outside an IDE installation - a dummy path satisfies it
        System.setProperty("idea.home.path", System.getProperty("java.io.tmpdir"));
        // the factory (and not the constructor) registers the services that
        // the environment expects - the constructor alone fails with
        // 'Extensions storage is not registered'
        final CompilerConfiguration cfg =
                CompilerConfigurationCreationKt.create(CompilerConfiguration.Companion);
        disposable = Disposer.newDisposable();
        final KotlinCoreEnvironment env = KotlinCoreEnvironment.Companion.createForProduction(
                disposable, cfg, EnvironmentConfigFiles.JVM_CONFIG_FILES);
        factory = new KtPsiFactory(env.getProject(), false);
        app = ApplicationManager.getApplication();
    }

    // -------------------------------------------------------------- indexing

    /**
     * The resolution context of one Kotlin file: its package, its imports
     * and its own type declarations, plus the gate of its package (the
     * Java {@code package-info} of the package, read from the compiled
     * model on the classpath - Kotlin has no package level annotations).
     */
    private final class FileCtx {
        private final String pkg;
        private final List<String> imports;
        private final List<String> ownFileTypes;
        private final Map<String, String> singleTypeImports;
        private final List<String> wildcardImports;
        private final List<String> packageGate;
        private final String facadeFqn;
        private final String text;
        private final String path;

        private FileCtx(final KtFile unit, final String fileName,
                        final String text, final String path) {
            this.pkg = unit.getPackageName();
            this.text = text;
            this.path = path;
            final String file = fileName.replaceFirst("\\.kt$", "");
            this.facadeFqn = pkg.isEmpty() ? file + "Kt" : pkg + "." + file + "Kt";
            this.imports = new ArrayList<>();
            this.singleTypeImports = new HashMap<>();
            this.wildcardImports = new ArrayList<>();
            for (final KtImportDirective directive : unit.getImportDirectives()) {
                final String text0 = directive.getText().trim();
                if (!text0.startsWith("import ")) {
                    continue;
                }
                final String name = text0.substring("import ".length())
                        .replaceFirst(" as .*$", "");
                imports.add(name);
                final int idx = name.lastIndexOf('.');
                if (name.endsWith(".*")) {
                    wildcardImports.add(name.substring(0, idx));
                } else {
                    singleTypeImports.put(name.substring(idx + 1), name);
                }
            }
            this.ownFileTypes = new ArrayList<>();
            for (final KtDeclaration decl : unit.getDeclarations()) {
                collectOwnFileTypes(decl, pkg, ownFileTypes);
            }
            this.packageGate = packageGateOf(pkg);
        }
    }

    private void collectOwnFileTypes(final KtDeclaration decl, final String prefix,
                                     final List<String> out) {
        if (!(decl instanceof KtClassOrObject) || decl instanceof KtEnumEntry) {
            return;
        }
        final KtClassOrObject type = (KtClassOrObject) decl;
        final String name = type.getName();
        if (name == null) {
            return;
        }
        final String fqn = prefix.isEmpty() ? name : prefix + "." + name;
        out.add(fqn);
        for (final KtDeclaration member : type.getDeclarations()) {
            collectOwnFileTypes(member, fqn, out);
        }
    }

    private void index(final KtFile unit, final FileCtx ctx) {
        // the top level constants of the file compile into the file facade
        // class (x.y.FooKt), which the constant imports and the bare usages
        // of the own file resolve to
        for (final KtDeclaration decl : unit.getDeclarations()) {
            if (decl instanceof KtProperty) {
                indexProp((KtProperty) decl, facadeOf(ctx), ctx);
            } else {
                indexDeclaration(decl, ctx.pkg, ctx, null);
            }
        }
    }

    private TypeDecl facadeOf(final FileCtx ctx) {
        return ownTypes.computeIfAbsent(ctx.facadeFqn,
                f -> new TypeDecl(f, ctx.pkg, true));
    }

    private void indexDeclaration(final KtDeclaration decl, final String prefix,
                                  final FileCtx ctx, final TypeDecl owner) {
        if (!(decl instanceof KtClassOrObject) || decl instanceof KtEnumEntry) {
            return;
        }
        final KtClassOrObject type = (KtClassOrObject) decl;
        final String name = type.getName();
        if (name == null) {
            return;
        }
        final String fqn = prefix.isEmpty() ? name : prefix + "." + name;
        final TypeDecl indexed = new TypeDecl(fqn, ctx.pkg, false);
        pending(indexed, onlyIfOf(type), ctx);
        indexed.alterTarget = annotationOf(type, "Alter") != null;
        ownTypes.put(fqn, indexed);
        if (type instanceof KtObjectDeclaration && ((KtObjectDeclaration) type).isCompanion()
                && owner != null) {
            owner.companionFqn = fqn;
        }
        for (final KtDeclaration member : type.getDeclarations()) {
            // only objects and companions carry constant properties - a
            // class level val is an instance member and never inlined
            if (member instanceof KtProperty && type instanceof KtObjectDeclaration) {
                indexProp((KtProperty) member, indexed, ctx);
            } else {
                indexDeclaration(member, fqn, ctx, indexed);
            }
        }
    }

    private void indexProp(final KtProperty prop, final TypeDecl owner, final FileCtx ctx) {
        final PropDecl decl = new PropDecl();
        pending(decl, onlyIfOf(prop), ctx);
        decl.marked = annotationOf(prop, "InlinedConstantApproved") != null;
        // a constant with @AlterConstValue has a flavour variant value, so
        // like an @Alter target it verifies as annotation-only
        decl.alterConst = annotationOf(prop, "AlterConstValue") != null;
        if (prop.getName() != null) {
            owner.props.put(prop.getName(), decl);
        }
    }

    private void pending(final Object target, final KtAnnotationEntry onlyIf, final FileCtx ctx) {
        if (onlyIf != null) {
            pendingGates.add(new PendingGate(target, onlyIf, ctx));
        }
    }

    /**
     * Resolves the {@code @OnlyIf} gates of the indexed types and
     * properties. This runs as a second phase, after every file is indexed:
     * the gate values are constant references like {@code Flavour.MPK},
     * which resolve against the classpath.
     */
    private void resolveGates() {
        for (final PendingGate pending : pendingGates) {
            final List<String> gate = flavourArgs(pending.ctx, pending.onlyIf);
            if (pending.target instanceof TypeDecl) {
                ((TypeDecl) pending.target).gate = gate;
            } else {
                ((PropDecl) pending.target).gate = gate;
            }
        }
        pendingGates.clear();
    }

    // ------------------------------------------------------- usage collection

    private void findUsages(final KtFile unit, final FileCtx ctx) {
        // qualified usages: Type.FIELD
        for (final KtDotQualifiedExpression dotted : descendants(unit, KtDotQualifiedExpression.class)) {
            // the qualified name of an import directive is no usage
            if (inImportOrPackage(dotted)) {
                continue;
            }
            final String field = selectorOf(dotted);
            final String receiver = dotted.getReceiverExpression().getText();
            if (field == null || receiver.contains("(")) {
                continue;
            }
            for (final String candidate : candidatesFor(receiver, ctx)) {
                final Resolved resolved = resolve(candidate, field);
                if (resolved == null) {
                    continue;
                }
                // the first visible declaration wins, marked or not: an
                // unmarked declaration is not flavour variant
                if (resolved.marked) {
                    usages.add(new Usage(dotted, candidate, field, resolved.gate,
                            resolved.alterTarget, ctx, ctx.path));
                }
                break;
            }
        }
        // bare usages: imports of the constant, companion members, top
        // level constants of the own file
        for (final KtNameReferenceExpression name
                : descendants(unit, KtNameReferenceExpression.class)) {
            if (!isBareUsage(name)) {
                continue;
            }
            final String field = name.getReferencedName();
            if (field == null) {
                continue;
            }
            for (final String candidate : bareCandidates(name, field, ctx)) {
                final Resolved resolved = resolve(candidate, field);
                if (resolved == null) {
                    continue;
                }
                if (resolved.marked) {
                    usages.add(new Usage(name, candidate, field, resolved.gate,
                            resolved.alterTarget, ctx, ctx.path));
                }
                break;
            }
        }
    }

    /**
     * A bare name usage: not the selector or the receiver of a qualified
     * expression, not a type name, not part of an import or package
     * directive.
     */
    private boolean isBareUsage(final KtNameReferenceExpression name) {
        for (PsiElement parent = name.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof KtDotQualifiedExpression
                    || parent instanceof KtTypeReference
                    || parent instanceof KtImportDirective
                    || parent instanceof org.jetbrains.kotlin.psi.KtPackageDirective) {
                return false;
            }
            if (parent instanceof KtDeclaration) {
                break;
            }
        }
        return true;
    }

    private boolean inImportOrPackage(final KtExpression expr) {
        for (PsiElement parent = expr.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof KtImportDirective
                    || parent instanceof org.jetbrains.kotlin.psi.KtPackageDirective) {
                return true;
            }
        }
        return false;
    }

    private String selectorOf(final KtDotQualifiedExpression dotted) {
        final KtExpression selector = dotted.getSelectorExpression();
        return selector instanceof KtNameReferenceExpression
                ? ((KtNameReferenceExpression) selector).getReferencedName() : null;
    }

    /**
     * The type candidates of a receiver expression, in the visibility
     * order of Kotlin: the own file's types, the imports, the own package,
     * the wildcard imports and - as the fallback - the fully qualified
     * chain, possibly down to a companion or a file facade.
     */
    private Set<String> candidatesFor(final String receiver, final FileCtx ctx) {
        final Set<String> candidates = new LinkedHashSet<>();
        final String[] parts = receiver.split("\\.");
        final String root = parts[0];
        final String tail = parts.length > 1
                ? "." + String.join(".", Arrays.asList(parts).subList(1, parts.length)) : "";
        ctx.ownFileTypes.stream()
                .filter(t -> t.equals(root) || t.endsWith("." + root))
                .forEach(t -> candidates.add(t + tail));
        if (ctx.singleTypeImports.containsKey(root)) {
            candidates.add(ctx.singleTypeImports.get(root) + tail);
        }
        candidates.add(ctx.pkg.isEmpty() ? receiver : ctx.pkg + "." + receiver);
        for (final String imp : ctx.wildcardImports) {
            candidates.add(imp + "." + receiver);
        }
        // the receiver may still be a fully qualified name whose root is a
        // package segment: the own file types and the imports win over it
        candidates.add(receiver);
        for (int idx = receiver.lastIndexOf('.'); idx > 0; idx = receiver.lastIndexOf('.', idx - 1)) {
            candidates.add(receiver.substring(0, idx));
        }
        return candidates;
    }

    /**
     * The declaration candidates of a bare reference, in the visibility
     * order of Kotlin: the import of the constant, the enclosing type
     * chain with their companions, the top level constants of the own
     * file, the facades of the own package and the wildcard imports.
     */
    private Set<String> bareCandidates(final KtNameReferenceExpression name, final String field,
                                      final FileCtx ctx) {
        final Set<String> candidates = new LinkedHashSet<>();
        for (final String imp : ctx.imports) {
            final int idx = imp.lastIndexOf('.');
            if (idx > 0 && field.equals(imp.substring(idx + 1))) {
                // import of the constant itself: the declaring class or the
                // package of a top level constant (x.y.FacadeKt.FIELD is
                // imported as x.y.FIELD)
                String cls = imp.substring(0, idx);
                final int clsIdx = cls.lastIndexOf('.');
                if (clsIdx > 0 && cls.substring(clsIdx + 1).equals("Companion")) {
                    cls = cls.substring(0, clsIdx);
                }
                candidates.add(cls);
                final TypeDecl imported = ownTypes.get(cls);
                if (imported != null) {
                    addCompanion(candidates, imported);
                }
                break;
            }
        }
        for (PsiElement parent = name.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof KtClassOrObject) {
                final TypeDecl type = ownTypes.get(fqnOf((KtClassOrObject) parent, ctx));
                if (type != null) {
                    candidates.add(type.fqn);
                    addCompanion(candidates, type);
                }
            }
        }
        candidates.add(ctx.facadeFqn);
        final String pkgPrefix = ctx.pkg.isEmpty() ? "" : ctx.pkg + ".";
        ownTypes.values().stream()
                .filter(t -> t.facade && t.fqn.startsWith(pkgPrefix) && t.props.containsKey(field))
                .map(t -> t.fqn)
                .forEach(candidates::add);
        for (final String imp : ctx.wildcardImports) {
            candidates.add(imp);
        }
        return candidates;
    }

    private void addCompanion(final Set<String> candidates, final TypeDecl type) {
        if (type.companionFqn != null) {
            candidates.add(type.companionFqn);
        }
    }

    /**
     * The fully qualified name of a type declaration of the own sources,
     * built from the enclosing declaration chain.
     */
    private String fqnOf(final KtClassOrObject type, final FileCtx ctx) {
        final List<String> names = new ArrayList<>();
        for (PsiElement parent = type; parent != null; parent = parent.getParent()) {
            if (parent instanceof KtClassOrObject) {
                final String name = ((KtClassOrObject) parent).getName();
                if (name == null) {
                    return null;
                }
                names.add(0, name);
            }
            if (parent instanceof KtFile) {
                break;
            }
        }
        return (ctx.pkg.isEmpty() ? "" : ctx.pkg + ".") + String.join(".", names);
    }

    // --------------------------------------------------------------- resolving

    private Resolved resolve(final String fqn, final String field) {
        final TypeDecl own = ownTypes.get(fqn);
        if (own != null) {
            // the members of the companion are visible through the class
            // name: Constants.VALUE resolves to the companion property
            final TypeDecl holder = own.props.containsKey(field)
                    ? own : companionOf(own);
            if (holder == null) {
                return null;
            }
            final PropDecl prop = holder.props.get(field);
            if (prop == null) {
                return null;
            }
            List<String> gate = prop.gate;
            boolean alterTarget = prop.alterConst;
            for (TypeDecl enclosing = own; enclosing != null;
                    enclosing = enclosingOfType(enclosing)) {
                gate = intersect(gate, enclosing.gate);
                alterTarget |= enclosing.alterTarget;
            }
            alterTarget |= holder.alterTarget;
            gate = intersect(gate, packageGateOf(own.pkg));
            return new Resolved(gate, prop.marked, alterTarget);
        }
        final ClassNode node = bytecodeOf(fqn);
        if (node == null) {
            return null;
        }
        Resolved resolved = resolveInBytecode(node, field);
        if (resolved == null && !fqn.endsWith(".Companion")) {
            resolved = resolveInBytecode(bytecodeOf(fqn + ".Companion"), field);
        }
        return resolved;
    }

    private TypeDecl companionOf(final TypeDecl type) {
        return type.companionFqn == null ? null : ownTypes.get(type.companionFqn);
    }

    private Resolved resolveInBytecode(final ClassNode node, final String field) {
        if (node == null) {
            return null;
        }
        for (final FieldNode f : node.fields) {
            if (f.name.equals(field) && (f.access & Opcodes.ACC_STATIC) != 0) {
                List<String> gate = JitterAnnotations.onlyIfFlavours(f.visibleAnnotations);
                gate = intersect(gate, JitterAnnotations.onlyIfFlavours(node.visibleAnnotations));
                String internal = node.name;
                int dollar = internal.lastIndexOf('$');
                while (dollar > 0) {
                    final ClassNode outer = ClassIndex.loadFromClasspath(classpath,
                            internal.substring(0, dollar), new StripResult());
                    if (outer != null) {
                        gate = intersect(gate,
                                JitterAnnotations.onlyIfFlavours(outer.visibleAnnotations));
                    }
                    internal = internal.substring(0, dollar);
                    dollar = internal.lastIndexOf('$');
                }
                gate = intersect(gate, externalPackageGate(node));
                return new Resolved(gate,
                        JitterAnnotations.isInlinedConstantApproved(f.visibleAnnotations),
                        !JitterAnnotations.alterAnnotations(node.visibleAnnotations).isEmpty()
                        || !JitterAnnotations.alterConstValues(f.visibleAnnotations).isEmpty());
            }
        }
        return null;
    }

    private TypeDecl enclosingOfType(final TypeDecl type) {
        final int idx = type.fqn.lastIndexOf('.');
        return idx < 0 ? null : ownTypes.get(type.fqn.substring(0, idx));
    }

    private ClassNode bytecodeOf(final String fqn) {
        if (bytecode.containsKey(fqn)) {
            return bytecode.get(fqn);
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
        bytecode.put(fqn, node);
        return node;
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

    private List<String> packageGateOf(final String pkg) {
        if (packageGates.containsKey(pkg)) {
            return packageGates.get(pkg);
        }
        List<String> gate = null;
        if (!pkg.isEmpty()) {
            final ClassNode info = ClassIndex.loadFromClasspath(classpath,
                    pkg.replace('.', '/') + "/package-info", new StripResult());
            gate = info == null ? null : JitterAnnotations.onlyIfFlavours(info);
        }
        packageGates.put(pkg, gate);
        return gate;
    }

    // ------------------------------------------------------------ verification

    private void verifyUsage(final Usage usage) {
        if (usage.constantGate == null) {
            verifyAlteredUsage(usage);
            return;
        }
        final Set<String> surviving = flavours.stream()
                .map(f -> f.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        retain(surviving, usage.ctx.packageGate);
        for (PsiElement parent = usage.expr.getParent(); parent != null;
                parent = parent.getParent()) {
            if (parent instanceof KtDeclaration) {
                final KtAnnotationEntry onlyIf =
                        onlyIfOf((KtDeclaration) parent);
                if (onlyIf != null) {
                    retain(surviving, flavourArgs(usage.ctx, onlyIf));
                }
            }
        }
        final Set<String> leaking = surviving.stream()
                .filter(f -> usage.constantGate.stream().noneMatch(g ->
                        g.toLowerCase(Locale.ROOT).equals(f)))
                .collect(Collectors.toSet());
        if (!leaking.isEmpty()) {
            errors.add(usage.path + ":" + lineOf(usage)
                    + ": the usage of " + usage.constantFqn + "#" + usage.field
                    + (memberOf(usage.expr).isEmpty() ? "" : " in " + memberOf(usage.expr))
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
        for (PsiElement parent = usage.expr; parent != null; parent = parent.getParent()) {
            if (parent instanceof KtAnnotationEntry) {
                inAnnotation = true;
                break;
            }
        }
        if (!inAnnotation) {
            errors.add(usage.path + ":" + lineOf(usage)
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

    private int lineOf(final Usage usage) {
        final int offset = usage.expr.getTextRange().getStartOffset();
        final String text = usage.ctx.text.substring(0, Math.min(offset, usage.ctx.text.length()));
        return text.split("\n", -1).length;
    }

    private String memberOf(final KtExpression expr) {
        for (PsiElement parent = expr; parent != null; parent = parent.getParent()) {
            if (parent instanceof KtAnnotationEntry) {
                final KtAnnotationEntry annotation = (KtAnnotationEntry) parent;
                return "@" + (annotation.getCalleeExpression() == null
                        ? "" : annotation.getCalleeExpression().getText());
            }
            if (parent instanceof KtNamedFunction) {
                return "fun " + ((KtNamedFunction) parent).getName() + "(...)";
            }
            if (parent instanceof KtProperty) {
                return "val " + ((KtProperty) parent).getName();
            }
            if (parent instanceof KtParameter) {
                return "parameter " + ((KtParameter) parent).getName();
            }
        }
        return "";
    }

    // --------------------------------------------------------- gate reading

    private KtAnnotationEntry onlyIfOf(final KtDeclaration decl) {
        return annotationOf(decl, "OnlyIf");
    }

    private KtAnnotationEntry annotationOf(final KtDeclaration decl, final String name) {
        for (final KtAnnotationEntry annotation : decl.getAnnotationEntries()) {
            final String text = annotation.getCalleeExpression() == null
                    ? "" : annotation.getCalleeExpression().getText();
            if (name.equals(text.substring(text.lastIndexOf('.') + 1))) {
                return annotation;
            }
        }
        return null;
    }

    private List<String> flavourArgs(final FileCtx ctx, final KtAnnotationEntry annotation) {
        final List<String> out = new ArrayList<>();
        if (annotation.getValueArgumentList() != null) {
            for (final KtValueArgument arg : annotation.getValueArgumentList().getArguments()) {
                collectValues(ctx, arg.getArgumentExpression(), out);
            }
        }
        return out;
    }

    private void collectValues(final FileCtx ctx, final KtExpression expr,
                              final List<String> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof KtStringTemplateExpression) {
            out.add(expr.getText().replace("\"", ""));
            return;
        }
        if (expr instanceof KtDotQualifiedExpression || expr instanceof KtNameReferenceExpression) {
            final String dotted = expr.getText();
            final int lastDot = dotted.lastIndexOf('.');
            final String field = lastDot < 0 ? dotted : dotted.substring(lastDot + 1);
            final String scope = lastDot < 0 ? "" : dotted.substring(0, lastDot);
            out.add(constants.resolve(scope, field, ctx.imports, ctx.pkg));
            return;
        }
        for (final PsiElement child : expr.getChildren()) {
            if (child instanceof KtExpression) {
                collectValues(ctx, (KtExpression) child, out);
            }
        }
    }

    private void retain(final Set<String> surviving, final List<String> gate) {
        if (gate == null || gate.isEmpty()) {
            return;
        }
        final Set<String> allowed = gate.stream()
                .map(g -> g.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        surviving.retainAll(allowed);
    }

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

    private static String sorted(final Set<String> flavours) {
        return Arrays.toString(flavours.stream().sorted().toArray());
    }

    // --------------------------------------------------------------- traversal

    private <T extends PsiElement> List<T> descendants(final PsiElement root, final Class<T> type) {
        final List<T> out = new ArrayList<>();
        collectDescendants(root, type, out);
        return out;
    }

    private <T extends PsiElement> void collectDescendants(final PsiElement element,
                                                          final Class<T> type, final List<T> out) {
        for (final PsiElement child : element.getChildren()) {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
            collectDescendants(child, type, out);
        }
    }

}
