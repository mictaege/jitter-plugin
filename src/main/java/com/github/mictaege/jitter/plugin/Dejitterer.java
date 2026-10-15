package com.github.mictaege.jitter.plugin;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Dejitters the sources of one source set on the source level: the dejittered
 * source variant of the active flavour is the original source minus the
 * flavour specific code, mirroring the semantics of the {@link BytecodeStripper}
 * on the source instead of the compiled classes.
 *
 * <p>Each edit is a text span surgery on the original file, so comments,
 * formatting and line numbers outside the edited spans survive untouched -
 * the dejittered variant reads like authored source, not like decompiled
 * code. {@code @OnlyIf} removes classes, packages (with all classes of the
 * package), fields, constructors, methods and enum constants,
 * {@code @Fork} replaces the gateway body with the template body and removes
 * the template, {@code @Alter} replaces the target class with its template
 * class, and the jitter annotations are removed from surviving elements.
 *
 * <p>Annotation values may be compile time constants, like
 * {@code @OnlyIf(Flavour.MPK)} - javac would bake them into the bytecode, the
 * source surgery resolves them by reflecting the referenced constants class
 * from the compile classpath of the source set.
 *
 * <p>The removals are recorded like the stripper records them (the internal
 * class names of the removed classes), so the task can cross check them
 * against the removal manifest of the stripped model: every source level
 * removal must also have been removed from the bytecode, otherwise the
 * source surgery applied something the bytecode stripping did not.
 */
public final class Dejitterer implements AutoCloseable {

    private static final List<String> JITTER_ANNOS = List.of(
            "OnlyIf", "Fork", "Gateway", "Alter", "Alternatives");

    /**
     * One parser per dejittering run: the parser instances of JavaParser are
     * not thread safe, and parallel builds run the dejitter tasks of several
     * projects concurrently in one daemon.
     */
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private final String activeFlavour;
    private final List<File> compileClasspath;
    private final Map<File, List<File>> externalTemplateDirs;
    private final StripResult result = new StripResult();
    private final Set<String> removedPackages = new HashSet<>();
    private final Set<Path> deletedFiles = new LinkedHashSet<>();
    private ClasspathConstants constants;
    private KotlinDejitter kotlin;
    private List<File> kotlinCompilerJars = List.of();

    public Dejitterer(final String activeFlavour, final List<File> compileClasspath) {
        this(activeFlavour, compileClasspath, Map.of());
    }

    /**
     * @param externalTemplateDirs the classes output directories of all source
     *        sets of the build, mapped to their source directories: the lookup
     *        for {@code @Alter} templates of other source sets, mirroring the
     *        bytecode stripping which resolves them from the classpath. A
     *        template of another source set stays where it is - only the
     *        target takes over its content.
     */
    public Dejitterer(final String activeFlavour, final List<File> compileClasspath,
                      final Map<File, List<File>> externalTemplateDirs) {
        this.activeFlavour = activeFlavour == null ? "" : activeFlavour;
        this.compileClasspath = compileClasspath == null ? List.of() : compileClasspath;
        this.externalTemplateDirs = externalTemplateDirs == null ? Map.of() : externalTemplateDirs;
        this.constants = new ClasspathConstants(compileClasspath);
    }

    /**
     * @param kotlinCompilerJars the resolved files of the kotlin compiler
     *        embeddable, including its dependencies: the Kotlin PSI is loaded
     *        in an isolated classloader so that it can never clash with a
     *        Kotlin toolchain of the applying build. Without jars the
     *        Kotlin PSI must already be on the classpath, like in the tests.
     */
    public void setKotlinCompilerJars(final List<File> kotlinCompilerJars) {
        this.kotlinCompilerJars = kotlinCompilerJars == null ? List.of() : kotlinCompilerJars;
    }

    @Override
    public void close() {
        if (kotlin != null) {
            kotlin.close();
        }
        constants.close();
    }

    /**
     * Dejitters the given source directories into the output directory:
     * Java sources as the dejittered variant, every other file as a verbatim
     * copy. Files of flavour removed packages are left out entirely.
     */
    public StripResult dejitter(final Set<Path> sourceDirs, final Path outputDir) throws IOException {
        final List<Path> javaFiles = new ArrayList<>();
        final List<Path> kotlinFiles = new ArrayList<>();
        final List<Path> otherFiles = new ArrayList<>();
        for (final Path dir : sourceDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).sorted().forEach(f -> {
                    final String name = f.getFileName().toString();
                    if (name.endsWith(".java")) {
                        javaFiles.add(f);
                    } else if (name.endsWith(".kt")) {
                        kotlinFiles.add(f);
                    } else {
                        otherFiles.add(f);
                    }
                });
            }
        }

        // flavour removed packages first: their files must not be processed
        // no matter in which order the files are visited - the package gate
        // covers every language of the package, the Kotlin files of a
        // flavour specific package carry no annotations of their own
        collectRemovedPackages(javaFiles);

        for (final Path file : javaFiles) {
            if (deletedFiles.contains(file)) {
                continue;
            }
            if (inRemovedPackage(file, sourceDirs)) {
                result.removedClass(removedClassOf(file, parsePackage(file)));
                deletedFiles.add(file);
                result.info("Remove class " + removedClassOf(file, parsePackage(file))
                        .replace('/', '.'));
                continue;
            }
            processFile(file, sourceDirs, outputDir, javaFiles);
        }

        // created lazily so that builds without Kotlin sources pay nothing
        KotlinDejitter kotlinDejitterer = null;
        try {
            for (final Path file : kotlinFiles) {
                if (deletedFiles.contains(file)) {
                    continue;
                }
                if (inRemovedPackage(file, sourceDirs)) {
                    if (kotlinDejitterer == null) {
                        kotlinDejitterer = kotlin(sourceDirs, outputDir);
                    }
                    kotlinDejitterer.recordPackageRemoval(file, result);
                    deletedFiles.add(file);
                    result.info("Remove file " + file.getFileName()
                            + " of flavour removed package " + file.getParent().getFileName());
                    continue;
                }
                if (kotlinDejitterer == null) {
                    kotlinDejitterer = kotlin(sourceDirs, outputDir);
                }
                kotlinDejitterer.dejitter(file, result, deletedFiles);
            }
        } finally {
            if (kotlinDejitterer != null) {
                kotlinDejitterer.close();
            }
        }

        for (final Path file : otherFiles) {
            if (deletedFiles.contains(file) || inRemovedPackage(file, sourceDirs)) {
                continue;
            }
            copy(file, sourceDirs, outputDir);
        }

        // the kept source files of the variant for the symmetric manifest
        // cross check: a class the bytecode stripping removed must not have
        // a kept source file. The Kotlin dejittering records its kept
        // classes itself, from the declarations and not the file names
        recordKeptSourceClasses(javaFiles, sourceDirs);
        return result;
    }

    private KotlinDejitter kotlin(final Set<Path> sourceDirs, final Path outputDir) throws IOException {
        if (kotlin == null) {
            kotlin = kotlinCompilerJars.isEmpty()
                    ? new KotlinDejitterer(activeFlavour, sourceDirs, outputDir,
                            compileClasspath, externalTemplateDirs)
                    : new IsolatedKotlinDejitter(activeFlavour, sourceDirs, outputDir,
                            compileClasspath, externalTemplateDirs, kotlinCompilerJars);
        }
        return kotlin;
    }

    private void recordKeptSourceClasses(final List<Path> files, final Set<Path> sourceDirs)
            throws IOException {
        for (final Path file : files) {
            if (deletedFiles.contains(file) || inRemovedPackage(file, sourceDirs)) {
                continue;
            }
            final String relative = relative(file, sourceDirs).toString().replace('\\', '/');
            result.keptSourceClass(relative.substring(0, relative.lastIndexOf('.')));
        }
    }

    // ---------------------------------------------------------------- packages

    /**
     * Finds the package-infos of inactive flavours: their packages are
     * flavour specific, all classes of the package directory are removed.
     */
    private void collectRemovedPackages(final List<Path> javaFiles) throws IOException {
        for (final Path file : javaFiles) {
            if (!file.getFileName().toString().equals("package-info.java")) {
                continue;
            }
            final ParseResult<CompilationUnit> parsed = parser.parse(read(file));
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                result.error("PARSE ERROR in " + file + ": " + parsed.getProblems());
                continue;
            }
            final CompilationUnit cu = parsed.getResult().get();
            if (cu.getPackageDeclaration().isEmpty()
                    || cu.getPackageDeclaration().get().getAnnotations().isEmpty()) {
                continue;
            }
            final List<String> flavours = onlyIfFlavours(cu,
                    cu.getPackageDeclaration().get().getAnnotations());
            if (flavours != null && flavours.stream().noneMatch(this::active)) {
                final String pkg = cu.getPackageDeclaration().get().getNameAsString();
                removedPackages.add(pkg.replace('.', '/'));
                deletedFiles.add(file);
                result.info("Remove package " + pkg);
            }
        }
    }

    /**
     * The package gate of a package-info covers every file of the package,
     * no matter which source root it comes from: like the bytecode
     * stripping, which knows packages and not files, the generated sources
     * of a package often live in their own source root beside the hand
     * written package-info.
     */
    private boolean inRemovedPackage(final Path file, final Set<Path> sourceDirs) {
        if (removedPackages.isEmpty()) {
            return false;
        }
        final String relative = relative(file, sourceDirs).toString().replace('\\', '/');
        final int lastSlash = relative.lastIndexOf('/');
        final String pkgPath = lastSlash < 0 ? "" : relative.substring(0, lastSlash);
        return removedPackages.contains(pkgPath);
    }

    // ---------------------------------------------------------------- files

    private void processFile(final Path file, final Set<Path> sourceDirs, final Path outputDir,
                             final List<Path> javaFiles) throws IOException {
        final String text = read(file);
        final ParseResult<CompilationUnit> parsed = parser.parse(text);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            result.error("PARSE ERROR in " + file + ": " + parsed.getProblems());
            copy(file, sourceDirs, outputDir);
            return;
        }
        final CompilationUnit cu = parsed.getResult().get();

        // the package declarations of surviving packages keep their javadoc,
        // only the flavour gate annotation is cleaned
        if (file.getFileName().toString().equals("package-info.java")) {
            final FileCtx ctx = new FileCtx(text);
            if (cu.getPackageDeclaration().isPresent()) {
                for (final AnnotationExpr annotation
                        : new ArrayList<>(cu.getPackageDeclaration().get().getAnnotations())) {
                    for (final String name : JITTER_ANNOS) {
                        if (nameIs(annotation, name)) {
                            ctx.removeAnnotation(annotation);
                        }
                    }
                }
            }
            final String edited = pruneImports(ctx.apply());
            if (edited.equals(text)) {
                copy(file, sourceDirs, outputDir);
            } else {
                write(file, sourceDirs, outputDir, edited);
            }
            return;
        }

        // @Alter replaces the whole file by its template
        for (final TypeDeclaration<?> type : cu.getTypes()) {
            final AnnotationExpr alter = activeAlter(cu, type.getAnnotations());
            if (alter != null) {
                applyAlter(file, sourceDirs, outputDir, javaFiles, cu, type, alter);
                return;
            }
        }

        final String pkgPath = parsePackage(file).replace('.', '/');
        final FileCtx ctx = new FileCtx(text);
        boolean anyTypeLeft = false;
        for (final TypeDeclaration<?> type : cu.getTypes()) {
            anyTypeLeft |= walkType(cu, type,
                    pkgPath.isEmpty() ? type.getNameAsString()
                            : pkgPath + "/" + type.getNameAsString(),
                    ctx);
        }
        if (!anyTypeLeft) {
            deletedFiles.add(file);
            return;
        }
        final String edited = pruneImports(ctx.apply());
        if (edited.equals(text)) {
            copy(file, sourceDirs, outputDir);
        } else {
            write(file, sourceDirs, outputDir, edited);
        }
    }

    /** @return true if the type survived. */
    private boolean walkType(final CompilationUnit cu, final TypeDeclaration<?> type,
                             final String internal, final FileCtx ctx) {
        final List<String> flavours = onlyIfFlavours(cu, type.getAnnotations());
        if (flavours != null && flavours.stream().noneMatch(this::active)) {
            ctx.removeElement(type);
            result.removedClass(internal);
            result.info("Remove class " + internal.replace('/', '.'));
            return false;
        }
        cleanJitterAnnotations(type, ctx);
        walkMembers(cu, type, internal, ctx);
        return true;
    }

    private void walkMembers(final CompilationUnit cu, final TypeDeclaration<?> type,
                             final String internal, final FileCtx ctx) {
        // the fork templates are consumed before any member is processed: a
        // template declared before its gateway would otherwise get its
        // annotation cleaned in the walk, and the gateway's consumption would
        // remove the same member afterwards - two edits on one span
        for (final BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration) {
                final MethodDeclaration method = (MethodDeclaration) member;
                final AnnotationExpr fork = activeFork(cu, method.getAnnotations());
                if (fork != null) {
                    final MethodDeclaration alt = findAlt(type, method,
                            annotationValue(cu, fork, "to"));
                    if (alt != null) {
                        ctx.consumed.add(alt);
                    }
                }
            }
        }
        for (final BodyDeclaration<?> member : new ArrayList<>(type.getMembers())) {
            if (ctx.consumed.contains(member)) {
                continue;
            }
            if (member instanceof FieldDeclaration) {
                final FieldDeclaration field = (FieldDeclaration) member;
                if (inactive(cu, field)) {
                    ctx.removeElement(field);
                } else {
                    cleanJitterAnnotations(field, ctx);
                }
            } else if (member instanceof MethodDeclaration) {
                final MethodDeclaration method = (MethodDeclaration) member;
                final AnnotationExpr fork = activeFork(cu, method.getAnnotations());
                if (fork != null) {
                    applyFork(type, method, annotationValue(cu, fork, "to"), ctx);
                } else if (inactive(cu, method)) {
                    ctx.removeElement(method);
                } else {
                    cleanJitterAnnotations(method, ctx);
                }
            } else if (member instanceof ConstructorDeclaration) {
                final ConstructorDeclaration constructor = (ConstructorDeclaration) member;
                if (inactive(cu, constructor)) {
                    ctx.removeElement(constructor);
                } else {
                    cleanJitterAnnotations(constructor, ctx);
                }
            } else if (member instanceof TypeDeclaration) {
                final TypeDeclaration<?> nested = (TypeDeclaration<?>) member;
                walkType(cu, nested, internal + "$" + nested.getNameAsString(), ctx);
            }
        }
        if (type instanceof EnumDeclaration) {
            final EnumDeclaration enumDecl = (EnumDeclaration) type;
            for (final EnumConstantDeclaration entry : enumDecl.getEntries()) {
                if (inactive(cu, entry)) {
                    ctx.removeEnumEntry(entry);
                } else {
                    cleanJitterAnnotations(entry, ctx);
                }
            }
        }
    }

    // ---------------------------------------------------------------- fork

    private void applyFork(final TypeDeclaration<?> owner, final MethodDeclaration gateway,
                           final String altName, final FileCtx ctx) {
        final MethodDeclaration alt = findAlt(owner, gateway, altName);
        if (alt == null) {
            result.error("The given alternative method " + altName + " could not be found in "
                    + owner.getNameAsString());
            return;
        }
        if (gateway.getBody().isEmpty() || alt.getBody().isEmpty()) {
            result.error("The fork gateway " + owner.getNameAsString()
                    + "#" + gateway.getNameAsString() + " or its alternative " + altName
                    + " has no body to work on");
            return;
        }
        final Range bodyOfGateway = gateway.getBody().get().getRange().orElseThrow(IllegalStateException::new);
        final Range bodyOfAlt = alt.getBody().get().getRange().orElseThrow(IllegalStateException::new);
        final String templateBody = ctx.text(bodyOfAlt);
        final String throwsOfTemplate = alt.getThrownExceptions().isEmpty()
                ? ""
                : " throws " + alt.getThrownExceptions().stream()
                        .map(Object::toString).collect(Collectors.joining(", "));
        if (gateway.getThrownExceptions().isEmpty()) {
            // a gateway without its own throws clause takes over the template's
            ctx.replace(bodyOfGateway, throwsOfTemplate.isEmpty()
                    ? templateBody : throwsOfTemplate.trim() + " " + templateBody);
        } else {
            if (!gateway.getThrownExceptions().toString()
                    .equals(alt.getThrownExceptions().toString())) {
                result.info("The fork gateway " + owner.getNameAsString()
                        + "#" + gateway.getNameAsString() + " keeps its own differing"
                        + " throws clause, the template's is ignored");
            }
            ctx.replace(bodyOfGateway, templateBody);
        }
        ctx.removeElement(alt);
        ctx.consumed.add(alt);
        result.info("Fork " + owner.getNameAsString() + "#" + gateway.getNameAsString()
                + " <- " + altName);
        cleanJitterAnnotations(gateway, ctx);
    }

    /**
     * The alternative method of a fork gateway: the one with the gateway's
     * own signature if present, otherwise the first with the given name -
     * like the bytecode stripping, which prefers the matching signature and
     * falls back to the name alone.
     */
    private MethodDeclaration findAlt(final TypeDeclaration<?> owner, final MethodDeclaration gateway,
                                      final String altName) {
        MethodDeclaration alt = null;
        for (final BodyDeclaration<?> member : owner.getMembers()) {
            if (member instanceof MethodDeclaration) {
                final MethodDeclaration cand = (MethodDeclaration) member;
                if (!cand.equals(gateway) && cand.getNameAsString().equals(altName)) {
                    if (signature(cand).equals(signature(gateway))) {
                        return cand;
                    }
                    if (alt == null) {
                        alt = cand;
                    }
                }
            }
        }
        return alt;
    }

    private String signature(final MethodDeclaration method) {
        return method.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.joining(","));
    }

    // ---------------------------------------------------------------- alter

    private void applyAlter(final Path file, final Set<Path> sourceDirs, final Path outputDir,
                            final List<Path> javaFiles, final CompilationUnit targetCu,
                            final TypeDeclaration<?> target, final AnnotationExpr alter)
            throws IOException {
        final String with = annotationValue(targetCu, alter, "with");
        final TemplateSource templateSource = findTemplate(with, file, sourceDirs);
        if (templateSource == null) {
            result.error("The given alternative class " + with + " could not be found in the"
                    + " source set - alternative classes of other source sets"
                    + " are not supported by the source level dejittering");
            return;
        }
        // the template takes over the target's simple name: with a fully
        // qualified template only the simple name is replaced
        final String templateName = with.substring(with.lastIndexOf('.') + 1);
        final String templateText = templateSource.text;
        final ParseResult<CompilationUnit> parsed = parser.parse(templateText);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()
                || parsed.getResult().get().getTypes().isEmpty()) {
            result.error("The given alternative class " + with + " does not parse");
            return;
        }
        final CompilationUnit templateCu = parsed.getResult().get();
        final TypeDeclaration<?> template = templateCu.getTypes().stream()
                .filter(t -> t.getNameAsString().equals(templateName))
                .findFirst()
                .orElse(templateCu.getTypes().get(0));
        final boolean targetPublic = target.getModifiers().contains(Modifier.publicModifier());
        final boolean templatePublic = template.getModifiers().contains(Modifier.publicModifier());
        String out = templateText;
        // the template takes over the target's visibility
        if (targetPublic && !templatePublic) {
            final Range typeRange = template.getRange().orElseThrow(IllegalStateException::new);
            out = insert(out, offset(out, typeRange.begin), "public ");
        } else if (!targetPublic && templatePublic) {
            final Range modifierRange = template.getModifiers().get(0).getRange()
                    .orElseThrow(IllegalStateException::new);
            out = delete(out, offset(out, modifierRange.begin), offset(out, modifierRange.end) + 1);
        }
        // the template takes over the target's simple name
        out = out.replaceAll("(?<![A-Za-z0-9_$])" + Pattern.quote(templateName) + "(?![A-Za-z0-9_$])",
                java.util.regex.Matcher.quoteReplacement(target.getNameAsString()));
        // the template moves into the target's package
        final String targetPkg = targetCu.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        final String templatePkg = templateCu.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        if (!targetPkg.equals(templatePkg)) {
            out = out.replace("package " + templatePkg + ";", "package " + targetPkg + ";");
        }
        result.info("Replace class " + target.getNameAsString() + " with " + with);
        // the replacement content may itself carry flavour annotations
        final ParseResult<CompilationUnit> outParsed = parser.parse(out);
        final CompilationUnit outCu = outParsed.getResult()
                .orElseThrow(() -> new IllegalStateException("altered template does not parse"));
        final FileCtx ctx = new FileCtx(out);
        for (final TypeDeclaration<?> type : outCu.getTypes()) {
            walkType(outCu, type,
                    targetPkg.replace('.', '/') + "/" + target.getNameAsString(), ctx);
        }
        write(file, sourceDirs, outputDir, pruneImports(ctx.apply()));
        deletedFiles.add(file);
        if (templateSource.file != null) {
            deletedFiles.add(templateSource.file);
            // the template may already have been visited and written before
            // its target was reached - remove what came out of that
            Files.deleteIfExists(outputDir.resolve(relative(templateSource.file, sourceDirs)));
        }
    }

    // ------------------------------------------------------------ annotations

    private List<String> onlyIfFlavours(final CompilationUnit cu,
                                         final NodeList<AnnotationExpr> annotations) {
        for (final AnnotationExpr annotation : annotations) {
            if (nameIs(annotation, "OnlyIf")) {
                if (annotation instanceof SingleMemberAnnotationExpr) {
                    return evalFlavours(cu, ((SingleMemberAnnotationExpr) annotation).getMemberValue());
                }
                if (annotation instanceof NormalAnnotationExpr) {
                    for (final MemberValuePair pair : ((NormalAnnotationExpr) annotation).getPairs()) {
                        if (pair.getNameAsString().equals("value")) {
                            return evalFlavours(cu, pair.getValue());
                        }
                    }
                }
                return List.of();
            }
        }
        return null;
    }

    private boolean inactive(final CompilationUnit cu, final NodeWithAnnotations<?> node) {
        final List<String> flavours = onlyIfFlavours(cu, node.getAnnotations());
        return flavours != null && flavours.stream().noneMatch(this::active);
    }

    private boolean active(final String flavour) {
        return activeFlavour.length() == 0 || activeFlavour.equalsIgnoreCase(flavour);
    }

    private AnnotationExpr activeFork(final CompilationUnit cu, final NodeList<AnnotationExpr> annotations) {
        if (activeFlavour.isEmpty()) {
            return null;
        }
        for (final AnnotationExpr annotation : repeatable(annotations, "Fork", "Gateway")) {
            if (active(annotationValue(cu, annotation, "ifActive"))) {
                return annotation;
            }
        }
        return null;
    }

    private AnnotationExpr activeAlter(final CompilationUnit cu, final NodeList<AnnotationExpr> annotations) {
        if (activeFlavour.isEmpty()) {
            return null;
        }
        for (final AnnotationExpr annotation : repeatable(annotations, "Alter", "Alternatives")) {
            if (active(annotationValue(cu, annotation, "ifActive"))) {
                return annotation;
            }
        }
        return null;
    }

    private List<AnnotationExpr> repeatable(final NodeList<AnnotationExpr> annotations,
                                           final String single, final String container) {
        final List<AnnotationExpr> out = new ArrayList<>();
        for (final AnnotationExpr annotation : annotations) {
            if (nameIs(annotation, single)) {
                out.add(annotation);
            } else if (nameIs(annotation, container)) {
                final Expression value;
                if (annotation instanceof SingleMemberAnnotationExpr) {
                    value = ((SingleMemberAnnotationExpr) annotation).getMemberValue();
                } else if (annotation instanceof NormalAnnotationExpr) {
                    value = ((NormalAnnotationExpr) annotation).getPairs().isEmpty()
                            ? null : ((NormalAnnotationExpr) annotation).getPairs().get(0).getValue();
                } else {
                    value = null;
                }
                if (value instanceof ArrayInitializerExpr) {
                    for (final Expression element : ((ArrayInitializerExpr) value).getValues()) {
                        if (element instanceof AnnotationExpr) {
                            out.add((AnnotationExpr) element);
                        }
                    }
                }
            }
        }
        return out;
    }

    private String annotationValue(final CompilationUnit cu, final AnnotationExpr annotation,
                                   final String name) {
        if (annotation instanceof NormalAnnotationExpr) {
            for (final MemberValuePair pair : ((NormalAnnotationExpr) annotation).getPairs()) {
                if (pair.getNameAsString().equals(name)) {
                    return constantValue(cu, pair.getValue());
                }
            }
        }
        return null;
    }

    /**
     * Evaluates an annotation value: string literals pass through, references
     * to compile time constants are resolved by reflecting the referenced
     * class from the compile classpath, everything else is used as its plain
     * text so that the unresolvable case stays visible in the result.
     */
    private String constantValue(final CompilationUnit cu, final Expression expr) {
        if (expr instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) expr).getValue();
        }
        if (expr instanceof FieldAccessExpr || expr instanceof NameExpr) {
            final String dotted = scopeText(expr);
            final int lastDot = dotted.lastIndexOf('.');
            final String field = lastDot < 0 ? dotted : dotted.substring(lastDot + 1);
            final String scope = lastDot < 0 ? "" : dotted.substring(0, lastDot);
            return resolveConstant(cu, scope, field);
        }
        return expr.toString();
    }

    private String scopeText(final Expression expr) {
        if (expr instanceof NameExpr) {
            return ((NameExpr) expr).getNameAsString();
        }
        final FieldAccessExpr access = (FieldAccessExpr) expr;
        final Expression scope = access.getScope();
        final String prefix = scope instanceof NameExpr || scope instanceof FieldAccessExpr
                ? scopeText(scope) + "."
                : "";
        return prefix + access.getNameAsString();
    }

    private String resolveConstant(final CompilationUnit cu, final String scope, final String field) {
        final String pkg = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        return constants.resolve(scope, field, importsOf(cu), pkg);
    }

    private List<String> importsOf(final CompilationUnit cu) {
        final List<String> out = new ArrayList<>();
        for (final ImportDeclaration imp : cu.getImports()) {
            out.add(imp.getNameAsString());
        }
        return out;
    }

    /**
     * The source of an {@code @Alter} template, mirroring the bytecode
     * stripping, which knows classes and not files: a simple name is a
     * neighbor file first, a fully qualified name is resolved by package
     * within the source directories, and as the fallback the template is a
     * top level type declared in any file of its package - javac allows more
     * than one top level type per file.
     */
    private TemplateSource findTemplate(final String with, final Path targetFile,
                                        final Set<Path> sourceDirs) throws IOException {
        if (with.indexOf('.') < 0) {
            final Path neighbor = targetFile.getParent().resolve(with + ".java");
            if (Files.isRegularFile(neighbor)) {
                return new TemplateSource(read(neighbor), neighbor);
            }
        } else {
            for (final Path dir : sourceDirs) {
                final Path candidate = dir.resolve(with.replace('.', '/') + ".java");
                if (Files.isRegularFile(candidate)) {
                    return new TemplateSource(read(candidate), candidate);
                }
            }
        }
        final String templateName = with.substring(with.lastIndexOf('.') + 1);
        final Path packageDir = templatePackageDir(with, targetFile, sourceDirs);
        if (packageDir != null) {
            try (Stream<Path> files = Files.list(packageDir)) {
                for (final Path candidate : files.sorted().collect(Collectors.toList())) {
                    if (!candidate.toString().endsWith(".java")) {
                        continue;
                    }
                    final String text = read(candidate);
                    final ParseResult<CompilationUnit> parsed = parser.parse(text);
                    if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                        continue;
                    }
                    for (final TypeDeclaration<?> type : parsed.getResult().get().getTypes()) {
                        if (type.getNameAsString().equals(templateName)) {
                            return new TemplateSource(
                                    typeSource(parsed.getResult().get(), text, type), null);
                        }
                    }
                }
            }
        }
        // the template of another source set of the build, mirroring the
        // bytecode stripping which resolves classpath templates
        return findExternalTemplate(with);
    }

    /**
     * Resolves an {@code @Alter} template of another source set: the source
     * set whose compiled classes contain the template class provides the
     * template source. The template stays in its own source set's dejittered
     * variant - like the bytecode stripping, which keeps classpath templates
     * where they are.
     */
    private TemplateSource findExternalTemplate(final String with) throws IOException {
        final String templatePath = with.replace('.', '/');
        for (final Map.Entry<File, List<File>> entry : externalTemplateDirs.entrySet()) {
            if (!new File(entry.getKey(), templatePath + ".class").isFile()) {
                continue;
            }
            for (final File srcDir : entry.getValue()) {
                final Path candidate = srcDir.toPath().resolve(templatePath + ".java");
                if (Files.isRegularFile(candidate)) {
                    return new TemplateSource(read(candidate), null);
                }
            }
        }
        return null;
    }

    private Path templatePackageDir(final String with, final Path targetFile, final Set<Path> sourceDirs) {
        if (with.indexOf('.') < 0) {
            return targetFile.getParent();
        }
        final String pkg = with.substring(0, with.lastIndexOf('.'));
        for (final Path dir : sourceDirs) {
            final Path candidate = dir.resolve(pkg.replace('.', '/'));
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The source file of one top level type of another file: the package
     * declaration and imports of the host file with the type's own text.
     */
    private String typeSource(final CompilationUnit host, final String hostText,
                             final TypeDeclaration<?> type) {
        final StringBuilder out = new StringBuilder();
        if (host.getPackageDeclaration().isPresent()) {
            out.append("package ").append(host.getPackageDeclaration().get().getNameAsString())
                    .append(";\n\n");
        }
        for (final ImportDeclaration imp : host.getImports()) {
            out.append(imp);
        }
        if (!host.getImports().isEmpty()) {
            out.append('\n');
        }
        Range range = type.getRange().orElseThrow(IllegalStateException::new);
        if (type.getJavadocComment().isPresent()
                && type.getJavadocComment().get().getRange().isPresent()) {
            range = new Range(
                    type.getJavadocComment().get().getRange().get().begin.isBefore(range.begin)
                            ? type.getJavadocComment().get().getRange().get().begin : range.begin,
                    range.end);
        }
        out.append(hostText.substring(offset(hostText, range.begin), offset(hostText, range.end) + 1));
        out.append('\n');
        return out.toString();
    }

    private static final class TemplateSource {
        private final String text;
        private final Path file;

        private TemplateSource(final String text, final Path file) {
            this.text = text;
            this.file = file;
        }
    }

    private List<String> evalFlavours(final CompilationUnit cu, final Expression expr) {
        final List<String> out = new ArrayList<>();
        collectFlavours(cu, expr, out);
        return out;
    }

    private void collectFlavours(final CompilationUnit cu, final Expression expr,
                                 final List<String> out) {
        if (expr instanceof ArrayInitializerExpr) {
            for (final Expression element : ((ArrayInitializerExpr) expr).getValues()) {
                collectFlavours(cu, element, out);
            }
        } else {
            out.add(constantValue(cu, expr));
        }
    }

    private boolean nameIs(final AnnotationExpr annotation, final String simple) {
        final String name = annotation.getNameAsString();
        return name.equals(simple) || name.endsWith("." + simple);
    }

    private void cleanJitterAnnotations(final NodeWithAnnotations<?> node, final FileCtx ctx) {
        for (final AnnotationExpr annotation : new ArrayList<>(node.getAnnotations())) {
            for (final String name : JITTER_ANNOS) {
                if (nameIs(annotation, name)) {
                    ctx.removeAnnotation(annotation);
                }
            }
        }
    }

    /**
     * Removes import statements whose imported name no longer occurs outside
     * import lines: javac resolves imports even when unused, so imports of
     * flavour removed classes must not survive the edit.
     */
    private String pruneImports(final String text) {
        final List<String> lines = new ArrayList<>(readLines(text));
        final StringBuilder body = new StringBuilder();
        final Map<Integer, String> imports = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            final String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("import ")) {
                String name = trimmed.substring(7);
                if (name.endsWith(";")) {
                    name = name.substring(0, name.length() - 1).trim();
                }
                if (name.startsWith("static ")) {
                    name = name.substring(7).trim();
                }
                if (!name.endsWith(".*")) {
                    imports.put(i, name.substring(name.lastIndexOf('.') + 1));
                }
            } else {
                body.append(lines.get(i)).append('\n');
            }
        }
        final String bodyText = body.toString();
        for (final Map.Entry<Integer, String> entry : imports.entrySet()) {
            if (!Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(entry.getValue())
                    + "(?![A-Za-z0-9_$])").matcher(bodyText).find()) {
                lines.set(entry.getKey(), "");
            }
        }
        return String.join("\n", lines) + (text.endsWith("\n") ? "\n" : "");
    }

    // ------------------------------------------------------------ io helpers

    private List<String> readLines(final String text) {
        final List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private String read(final Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private String parsePackage(final Path file) throws IOException {
        final ParseResult<CompilationUnit> parsed = parser.parse(read(file));
        return parsed.getResult()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString())
                .orElse("");
    }

    private String removedClassOf(final Path file, final String pkg) {
        final String name = file.getFileName().toString()
                .substring(0, file.getFileName().toString().length() - ".java".length());
        return pkg.replace('.', '/') + (pkg.isEmpty() ? "" : "/") + name;
    }

    private void copy(final Path file, final Set<Path> sourceDirs, final Path outputDir)
            throws IOException {
        final Path target = outputDir;
        final Path relative = relative(file, sourceDirs);
        final Path out = target.resolve(relative);
        Files.createDirectories(out.getParent());
        Files.copy(file, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void write(final Path file, final Set<Path> sourceDirs, final Path outputDir,
                       final String content) throws IOException {
        final Path out = outputDir.resolve(relative(file, sourceDirs));
        Files.createDirectories(out.getParent());
        Files.writeString(out, content, StandardCharsets.UTF_8);
    }

    private Path relative(final Path file, final Set<Path> sourceDirs) {
        for (final Path dir : sourceDirs) {
            if (file.startsWith(dir)) {
                return dir.relativize(file);
            }
        }
        throw new IllegalArgumentException(file + " is below none of the source directories");
    }

    private static String insert(final String text, final int at, final String what) {
        return text.substring(0, at) + what + text.substring(at);
    }

    private static String delete(final String text, final int from, final int to) {
        return text.substring(0, from) + text.substring(to);
    }

    private static int offset(final String text, final com.github.javaparser.Position position) {
        int line = 1;
        int index = 0;
        while (line < position.line) {
            index = text.indexOf('\n', index) + 1;
            line++;
        }
        return index + position.column - 1;
    }

    // ------------------------------------------------------------ text edits

    private final class FileCtx {
        private final String text;
        private final List<Edit> edits = new ArrayList<>();
        private final Set<BodyDeclaration<?>> consumed = new HashSet<>();

        FileCtx(final String text) {
            this.text = text;
        }

        String text(final Range range) {
            return text.substring(offset(text, range.begin), offset(text, range.end) + 1);
        }

        void removeElement(final BodyDeclaration<?> element) {
            Range range = element.getRange().orElseThrow(IllegalStateException::new);
            if (element instanceof NodeWithJavadoc
                    && ((NodeWithJavadoc<?>) element).getJavadocComment().isPresent()) {
                range = union(range, ((NodeWithJavadoc<?>) element).getJavadocComment().get()
                        .getRange().orElse(range));
            }
            removeRange(range);
        }

        void removeAnnotation(final AnnotationExpr annotation) {
            removeRange(annotation.getRange().orElseThrow(IllegalStateException::new));
        }

        void removeEnumEntry(final EnumConstantDeclaration entry) {
            Range range = entry.getRange().orElseThrow(IllegalStateException::new);
            if (entry.getJavadocComment().isPresent()) {
                range = union(range, entry.getJavadocComment().get().getRange().orElse(range));
            }
            int start = offset(text, range.begin);
            int end = offset(text, range.end) + 1;
            // the following separator is swallowed, but never the ';' of the
            // constant list: a leftover trailing comma stays legal, a missing
            // ';' before the enum body declarations does not
            int p = end;
            while (p < text.length() && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) {
                p++;
            }
            if (p < text.length() && text.charAt(p) == ',') {
                end = p + 1;
            }
            removeOffset(extendBack(start), extendFwd(end));
        }

        void removeRange(final Range range) {
            removeOffset(extendBack(offset(text, range.begin)),
                    extendFwd(offset(text, range.end) + 1));
        }

        void replace(final Range range, final String replacement) {
            edits.add(new Edit(offset(text, range.begin), offset(text, range.end) + 1, replacement));
        }

        private void removeOffset(final int start, final int end) {
            edits.add(new Edit(start, end, ""));
        }

        private int extendBack(final int start) {
            int s = start;
            while (s > 0) {
                final char c = text.charAt(s - 1);
                if (c == ' ' || c == '\t') {
                    s--;
                } else if (c == '\n') {
                    return s;
                } else {
                    return start;
                }
            }
            return s;
        }

        private int extendFwd(final int end) {
            int e = end;
            while (e < text.length()) {
                final char c = text.charAt(e);
                if (c == ' ' || c == '\t') {
                    e++;
                } else if (c == '\n') {
                    return e + 1;
                } else {
                    return end;
                }
            }
            return e;
        }

        String apply() {
            edits.sort(Comparator.comparingInt(Edit::start).reversed());
            for (int i = 1; i < edits.size(); i++) {
                if (edits.get(i - 1).start < edits.get(i).end) {
                    result.error("EDIT OVERLAP in a dejittered file near offset "
                            + edits.get(i).start + ": [" + edits.get(i) + "] vs ["
                            + edits.get(i - 1) + "]");
                }
            }
            String out = text;
            for (final Edit edit : edits) {
                out = out.substring(0, edit.start) + edit.replacement + out.substring(edit.end);
            }
            return out;
        }

        private Range union(final Range a, final Range b) {
            return new Range(
                    a.begin.isBefore(b.begin) ? a.begin : b.begin,
                    a.end.isAfter(b.end) ? a.end : b.end);
        }
    }

    private static final class Edit {
        private final int start;
        int start() { return start; }
        private final int end;
        private final String replacement;

        private Edit(final int start, final int end, final String replacement) {
            this.start = start;
            this.end = end;
            this.replacement = replacement;
        }

        @Override
        public String toString() {
            return "Edit[" + start + ".." + end + "]";
        }
    }

}
