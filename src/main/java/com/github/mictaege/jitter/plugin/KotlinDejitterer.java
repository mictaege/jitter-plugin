package com.github.mictaege.jitter.plugin;

import org.jetbrains.kotlin.cli.CompilerConfigurationCreationKt;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtEnumEntry;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtNamedDeclaration;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtParameterList;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;
import org.jetbrains.kotlin.psi.KtValueArgument;
import org.jetbrains.kotlin.psi.KtValueArgumentName;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dejitters Kotlin sources in the same way the {@link Dejitterer} dejitters
 * Java sources: the Kotlin PSI of the kotlin-compiler-embeddable only READS
 * the structure, every edit is an offset based text surgery on the original
 * file text, so comments, formatting and line numbers outside the edited
 * spans survive untouched.
 *
 * <p>{@code @OnlyIf} removes classes, objects, members, constructor
 * parameters and enum entries, {@code @Fork} replaces the gateway body with
 * the template body and removes the template, {@code @Alter} replaces the
 * target declaration with its template, and the jitter annotations are
 * removed from surviving elements.
 *
 * <p>Kotlin has no package level annotations, so flavour gating of whole
 * packages happens through the Java {@code package-info.java} of the
 * package and is handled by the {@link Dejitterer} before this class sees
 * any file. A top level {@code @Alter} replaces the whole file like in the
 * Java dejittering - the file of the target is the variant of the target.
 */
public final class KotlinDejitterer implements KotlinDejitter {

    private static final Set<String> JITTER_ANNOS = Set.of(
            "OnlyIf", "Fork", "Gateway", "Alter", "Alternatives");
    private static final String JITTER_API_IMPORT = "com.github.mictaege.jitter.api.";

    /**
     * The PSI of the kotlin compiler works on JVM wide singletons (the
     * application and its extension areas): parallel dejitter tasks of one
     * daemon serialize their PSI work on this lock.
     */
    private static final Object PSI_LOCK = new Object();

    private final String activeFlavour;
    private final Set<Path> sourceDirs;
    private final Path outputDir;
    private final ClasspathConstants constants;
    private final Map<File, List<File>> externalTemplateDirs;

    // created lazily: only builds that actually have Kotlin sources pay for it
    private Disposable disposable;
    private KtPsiFactory factory;
    private Application app;

    /**
     * @param externalTemplateDirs the classes output directories of all source
     *        sets of the build, mapped to their source directories: the
     *        lookup for {@code @Alter} templates of other source sets,
     *        mirroring the bytecode stripping which resolves classpath
     *        templates. A template of another source set stays where it is.
     */
    public KotlinDejitterer(final String activeFlavour, final Set<Path> sourceDirs, final Path outputDir,
                     final List<File> compileClasspath,
                     final Map<File, List<File>> externalTemplateDirs) {
        this.activeFlavour = activeFlavour == null ? "" : activeFlavour;
        this.sourceDirs = sourceDirs;
        this.outputDir = outputDir;
        this.constants = new ClasspathConstants(compileClasspath);
        this.externalTemplateDirs = externalTemplateDirs == null ? Map.of() : externalTemplateDirs;
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

    /**
     * Dejitters one Kotlin file into the output directory, mirroring the
     * relative path. Records the removals and kept classes of the file like
     * the Java dejittering does. Returns without writing anything when the
     * active flavour removes the file as a whole.
     */
    @Override
    public void dejitter(final Path file, final StripResult result, final Set<Path> deletedFiles)
            throws IOException {
        final String text = Files.readString(file, StandardCharsets.UTF_8);
        final String outText;
        synchronized (PSI_LOCK) {
            init();
            outText = app.runWriteAction((Computable<String>) () -> {
                try {
                    return dejitterUnderPsi(file, text, result, deletedFiles);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        if (outText != null) {
            final Path out = outputDir.resolve(relative(file));
            Files.createDirectories(out.getParent());
            Files.writeString(out, outText, StandardCharsets.UTF_8);
        }
    }

    /**
     * Records the top level classes of a Kotlin file of a flavour removed
     * package as removed classes, mirroring the bytecode removals of the
     * whole package.
     */
    @Override
    public void recordPackageRemoval(final Path file, final StripResult result) throws IOException {
        final String text = Files.readString(file, StandardCharsets.UTF_8);
        synchronized (PSI_LOCK) {
            init();
            app.runWriteAction((Computable<Object>) () -> {
                final KtFile ktFile = factory.createFile(fileName(file), text);
                for (final KtDeclaration decl : ktFile.getDeclarations()) {
                    if (decl instanceof KtClassOrObject && !(decl instanceof KtEnumEntry)) {
                        final String name = nameOf((KtClassOrObject) decl);
                        if (name != null) {
                            result.removedClass(internal(ktFile.getPackageName(), name));
                        }
                    }
                }
                return null;
            });
        }
    }

    // ------------------------------------------------------------ dejittering

    private String dejitterUnderPsi(final Path file, final String text, final StripResult result,
                                    final Set<Path> deletedFiles) throws IOException {
        final KtFile ktFile = factory.createFile(fileName(file), text);
        final FileCtx ctx = new FileCtx(file, text, ktFile, result, deletedFiles);
        final List<KtDeclaration> declarations = ktFile.getDeclarations();
        int gatedTopLevel = 0;
        for (final KtDeclaration decl : declarations) {
            if (onlyIfOf(ctx, decl) != null && !activeOnlyIf(ctx, decl)) {
                gatedTopLevel++;
            }
        }
        walk(declarations, "", ctx);
        if (ctx.replacement != null) {
            // a top level @Alter replaces the whole file, like in the Java
            // dejittering - the target's file becomes the template's variant
            return pruneImports(ctx.replacement, ctx.replacementImports);
        }
        if (!declarations.isEmpty() && gatedTopLevel == declarations.size()) {
            // the bytecode stripping removes the classes of the flavour
            // specific file as a whole, each class is already recorded
            ctx.deletedFiles.add(file);
            ctx.result.info("Remove file " + file.getFileName());
            return null;
        }
        final String out = pruneImports(ctx.edits.apply(), ctx.imports);
        for (final KtDeclaration decl : declarations) {
            if (ctx.removed.contains(decl)) {
                continue;
            }
            final String name = topNameOf(decl);
            if (name != null) {
                result.keptSourceClass(internal(ctx.pkg, name));
            }
        }
        return out;
    }

    /**
     * Walks the declarations: fork templates are consumed first (a template
     * declared before its gateway would otherwise get its annotations
     * cleaned and then be consumed afterwards - two edits on one span),
     * then forks apply, inactive elements are removed and survivors get
     * their jitter annotations cleaned.
     */
    private void walk(final List<KtDeclaration> decls, final String internalPrefix,
                      final FileCtx ctx) throws IOException {
        final Set<KtDeclaration> consumed = new LinkedHashSet<>();
        for (final KtDeclaration decl : decls) {
            if (decl instanceof KtNamedFunction) {
                final KtAnnotationEntry fork = activeFork(ctx, decl);
                if (fork != null) {
                    final KtNamedFunction alt = findAltOf(ctx, (KtNamedFunction) decl,
                            argValue(ctx, fork, "to"));
                    if (alt != null) {
                        consumed.add(alt);
                    }
                }
            }
        }
        for (final KtDeclaration decl : decls) {
            if (consumed.contains(decl) || ctx.removed.contains(decl)) {
                continue;
            }
            if (decl instanceof KtNamedFunction) {
                final KtAnnotationEntry fork = activeFork(ctx, decl);
                if (fork != null) {
                    applyFork((KtNamedFunction) decl, fork, ctx);
                    continue;
                }
            }
            if (onlyIfOf(ctx, decl) != null && !activeOnlyIf(ctx, decl)) {
                removeDecl(decl, internalPrefix, ctx);
                continue;
            }
            if (decl instanceof KtClassOrObject && !(decl instanceof KtEnumEntry)) {
                final KtAnnotationEntry alter = activeAlter(ctx, decl);
                if (alter != null) {
                    applyAlter((KtClassOrObject) decl, alter, internalPrefix, ctx);
                    continue;
                }
            }
            cleanJitterAnnotations(decl, ctx);
            if (decl instanceof KtClassOrObject) {
                final KtClassOrObject clazz = (KtClassOrObject) decl;
                final String internal = internalOf(clazz, internalPrefix, ctx);
                walkParameters(clazz, internal, ctx);
                if (clazz.getBody() != null) {
                    walk(clazz.getBody().getDeclarations(), internal, ctx);
                }
            }
        }
    }

    private void walkParameters(final KtClassOrObject clazz, final String internal, final FileCtx ctx) {
        final KtParameterList list = clazz.getPrimaryConstructorParameterList();
        if (list == null) {
            return;
        }
        final List<KtParameter> params = list.getParameters();
        for (int i = 0; i < params.size(); i++) {
            final KtParameter param = params.get(i);
            if (onlyIfOf(ctx, param) != null && !activeOnlyIf(ctx, param)) {
                final int prevEnd = i > 0
                        ? params.get(i - 1).getTextRange().getEndOffset() : 0;
                final int nextStart = i < params.size() - 1
                        ? params.get(i + 1).getTextRange().getStartOffset() : Integer.MAX_VALUE;
                ctx.edits.removeListElement(
                        param.getTextRange().getStartOffset(), param.getTextRange().getEndOffset(),
                        prevEnd, nextStart);
                ctx.result.info("Remove constructor parameter " + param.getName()
                        + " of " + internal.replace('/', '.'));
            } else {
                cleanJitterAnnotations(param, ctx);
            }
        }
    }

    // ------------------------------------------------------------------- fork

    private void applyFork(final KtNamedFunction gateway, final KtAnnotationEntry fork,
                           final FileCtx ctx) {
        final String to = argValue(ctx, fork, "to");
        final KtNamedFunction alt = findAltOf(ctx, gateway, to);
        if (alt == null) {
            ctx.result.error("The given alternative method " + to
                    + " could not be found in " + ctx.file.getFileName());
            return;
        }
        final String altBody = bodyText(alt, ctx.text);
        if (gateway.hasBody()) {
            final int bodyStart = gateway.hasBlockBody()
                    ? gateway.getBodyExpression().getTextRange().getStartOffset()
                    : gateway.getEqualsToken().getTextRange().getStartOffset();
            ctx.edits.replace(bodyStart, gateway.getTextRange().getEndOffset(), altBody);
        } else {
            ctx.edits.insert(gateway.getTextRange().getEndOffset(), " " + altBody);
        }
        ctx.edits.removeSpan(alt.getTextRange().getStartOffset(), alt.getTextRange().getEndOffset());
        ctx.result.info("Replace body of " + gateway.getName() + " with " + alt.getName());
        cleanJitterAnnotations(gateway, ctx);
    }

    private KtNamedFunction findAltOf(final FileCtx ctx, final KtNamedFunction gateway,
                                      final String altName) {
        if (altName == null) {
            return null;
        }
        for (final KtDeclaration sibling : siblingsOf(gateway)) {
            if (sibling instanceof KtNamedFunction
                    && sibling != gateway
                    && altName.equals(((KtNamedFunction) sibling).getName())) {
                return (KtNamedFunction) sibling;
            }
        }
        return null;
    }

    private List<KtDeclaration> siblingsOf(final KtDeclaration decl) {
        final List<KtDeclaration> out = new ArrayList<>();
        if (decl.getParent() != null && decl.getParent().getParent() instanceof KtClassOrObject) {
            final KtClassOrObject owner = (KtClassOrObject) decl.getParent().getParent();
            if (owner.getBody() != null) {
                out.addAll(owner.getBody().getDeclarations());
            }
        } else if (decl.getParent() instanceof KtFile) {
            out.addAll(((KtFile) decl.getParent()).getDeclarations());
        }
        return out;
    }

    /**
     * The body of a function as source text: a block body as the block, an
     * expression body as {@code = expression}.
     */
    private String bodyText(final KtNamedFunction function, final String text) {
        if (function.hasBlockBody()) {
            final KtExpression body = function.getBodyExpression();
            return text.substring(body.getTextRange().getStartOffset(),
                    function.getTextRange().getEndOffset());
        }
        return text.substring(function.getEqualsToken().getTextRange().getStartOffset(),
                function.getTextRange().getEndOffset());
    }

    // ------------------------------------------------------------------ alter

    private void applyAlter(final KtClassOrObject target, final KtAnnotationEntry alter,
                            final String internalPrefix, final FileCtx ctx) throws IOException {
        if (!internalPrefix.isEmpty()) {
            ctx.result.error("The @Alter target " + nameOf(target)
                    + " is not a top level declaration of " + ctx.file.getFileName()
                    + " - alternative classes of nested declarations are not supported"
                    + " by the source level dejittering");
            return;
        }
        final String with = argValue(ctx, alter, "with");
        final TemplateSource template = findTemplate(with, ctx.file);
        if (template == null) {
            ctx.result.error("The given alternative class " + with + " could not be found"
                    + " as a Kotlin source - alternative Kotlin classes of Java targets"
                    + " or of non indexed source sets are not supported");
            return;
        }
        final String targetName = nameOf(target);
        final String templateName = with.substring(with.lastIndexOf('.') + 1);
        String out = template.text;
        final String templatePkg = packageOfText(out);
        if (!ctx.pkg.equals(templatePkg)) {
            out = rewritePackage(out, templatePkg, ctx.pkg);
        }
        // the template takes over the target's visibility and simple name
        out = transferVisibility(target, out, templateName);
        out = out.replaceAll("(?<![A-Za-z0-9_$])" + Pattern.quote(templateName) + "(?![A-Za-z0-9_$])",
                Matcher.quoteReplacement(targetName));
        // the replacement content may itself carry flavour annotations
        final KtFile merged = factory.createFile(fileName(ctx.file), out);
        final FileCtx mergedCtx = new FileCtx(ctx.file, out, merged, ctx.result, ctx.deletedFiles);
        walk(merged.getDeclarations(), "", mergedCtx);
        ctx.replacement = mergedCtx.edits.apply();
        ctx.replacementImports = mergedCtx.imports;
        // the template source leaves the variant - a template of another
        // source set stays where it is, like the bytecode stripping keeps
        // classpath templates
        if (template.file != null) {
            ctx.result.removedClass(internal(ctx.pkg, templateName));
            ctx.result.unkeepSourceClass(internal(ctx.pkg, templateName));
            ctx.deletedFiles.add(template.file);
            // the template may already have been dejittered and written before
            // its target was reached - remove what came out of that
            Files.deleteIfExists(outputDir.resolve(relative(template.file)));
        }
        ctx.result.keptSourceClass(internal(ctx.pkg, targetName));
        ctx.result.info("Replace class " + targetName + " with " + with);
    }

    private String rewritePackage(final String text, final String oldPkg, final String newPkg) {
        final String oldLine = oldPkg.isEmpty()
                ? null : "(?m)^package " + Pattern.quote(oldPkg) + "[^\\n]*\\n?";
        if (oldLine != null) {
            final String replacement = newPkg.isEmpty() ? "" : "package " + newPkg + "\n";
            return text.replaceFirst(oldLine, Matcher.quoteReplacement(replacement));
        }
        return text;
    }

    private String packageOfText(final String templateText) {
        for (final String line : templateText.split("\n", 50)) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                return trimmed.substring("package ".length()).replace(";", "").trim();
            }
        }
        return "";
    }

    /**
     * The template takes over the target's visibility. The implicit
     * visibility of Kotlin is public, so a declaration only carries the
     * keyword when it is not public.
     */
    private String transferVisibility(final KtClassOrObject target, final String templateText,
                                      final String templateName) {
        final String targetVisibility = visibilityOf(modifiersOf(target));
        final String templateVisibility = visibilityOf(templateDeclarationLine(templateText, templateName));
        if (targetVisibility.equals(templateVisibility)) {
            return templateText;
        }
        if (templateVisibility.isEmpty()) {
            return templateText.replaceFirst(
                    "(?m)^(\\s*)(final\\s+)?(class|interface|object|enum class) "
                            + Pattern.quote(templateName),
                    Matcher.quoteReplacement("$1" + targetVisibility + " $2$3 " + templateName));
        }
        return templateText.replaceFirst(
                "(?m)^(\\s*)" + templateVisibility + "\\s+(final\\s+)?",
                targetVisibility.isEmpty()
                        ? "$1" : Matcher.quoteReplacement("$1" + targetVisibility + " "));
    }

    private String templateDeclarationLine(final String text, final String name) {
        final Pattern line = Pattern.compile("^(.*\\s)?(final\\s+)?(class|interface|object|enum class) "
                + Pattern.quote(name) + "\\b");
        for (final String candidate : text.split("\n", 500)) {
            if (line.matcher(candidate.trim()).matches()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String modifiersOf(final KtClassOrObject decl) {
        return decl.getModifierList() == null ? "" : decl.getModifierList().getText();
    }

    private String visibilityOf(final String modifiers) {
        for (final String keyword : new String[]{"public", "internal", "private", "protected"}) {
            if (Pattern.compile("(?<![A-Za-z0-9_$])" + keyword + "(?![A-Za-z0-9_$])")
                    .matcher(modifiers).find()) {
                return keyword;
            }
        }
        return "";
    }

    /**
     * The source of an {@code @Alter} template of the Kotlin target: a
     * simple name is a neighbor file, a fully qualified name is resolved by
     * package within the source directories, and as the fallback the
     * template is a Kotlin source of another source set of the build,
     * mirroring the bytecode stripping which resolves classpath templates.
     */
    private TemplateSource findTemplate(final String with, final Path targetFile) throws IOException {
        if (with.indexOf('.') < 0) {
            final Path neighbor = targetFile.getParent().resolve(with + ".kt");
            if (Files.isRegularFile(neighbor)) {
                return new TemplateSource(read(neighbor), neighbor);
            }
        } else {
            for (final Path dir : sourceDirs) {
                final Path candidate = dir.resolve(with.replace('.', '/') + ".kt");
                if (Files.isRegularFile(candidate)) {
                    return new TemplateSource(read(candidate), candidate);
                }
            }
        }
        final String dotted = with.replace('.', '/');
        for (final Map.Entry<File, List<File>> entry : externalTemplateDirs.entrySet()) {
            if (!new File(entry.getKey(), dotted + ".class").isFile()) {
                continue;
            }
            for (final File srcDir : entry.getValue()) {
                final Path candidate = srcDir.toPath().resolve(dotted + ".kt");
                if (Files.isRegularFile(candidate)) {
                    return new TemplateSource(read(candidate), null);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ annotations

    private void cleanJitterAnnotations(final KtDeclaration decl, final FileCtx ctx) {
        for (final KtAnnotationEntry annotation : decl.getAnnotationEntries()) {
            if (JITTER_ANNOS.contains(annotationName(annotation))) {
                ctx.edits.removeSpan(annotation.getTextRange().getStartOffset(),
                        annotation.getTextRange().getEndOffset());
            }
        }
    }

    private boolean activeOnlyIf(final FileCtx ctx, final KtDeclaration decl) {
        final KtAnnotationEntry onlyIf = onlyIfOf(ctx, decl);
        return onlyIf == null || activeOnlyIf(ctx, onlyIf);
    }

    private boolean activeOnlyIf(final FileCtx ctx, final KtAnnotationEntry onlyIf) {
        for (final String flavour : flavourArgs(ctx, onlyIf)) {
            if (active(flavour)) {
                return true;
            }
        }
        return false;
    }

    private KtAnnotationEntry onlyIfOf(final FileCtx ctx, final KtDeclaration decl) {
        return annotationOf(ctx, decl, "OnlyIf");
    }

    private KtAnnotationEntry annotationOf(final FileCtx ctx, final KtDeclaration decl,
                                          final String name) {
        for (final KtAnnotationEntry annotation : decl.getAnnotationEntries()) {
            if (name.equals(annotationName(annotation))) {
                return annotation;
            }
        }
        return null;
    }

    private KtAnnotationEntry activeFork(final FileCtx ctx, final KtDeclaration decl) {
        return activeAnnotation(ctx, decl, "Fork");
    }

    private KtAnnotationEntry activeAlter(final FileCtx ctx, final KtDeclaration decl) {
        return activeAnnotation(ctx, decl, "Alter");
    }

    private KtAnnotationEntry activeAnnotation(final FileCtx ctx, final KtDeclaration decl,
                                               final String name) {
        if (activeFlavour.isEmpty()) {
            return null;
        }
        final KtAnnotationEntry annotation = annotationOf(ctx, decl, name);
        if (annotation == null) {
            return null;
        }
        final String ifActive = argValue(ctx, annotation, "ifActive");
        return ifActive != null && active(ifActive) ? annotation : null;
    }

    private String annotationName(final KtAnnotationEntry annotation) {
        final String text = annotation.getCalleeExpression() == null
                ? "" : annotation.getCalleeExpression().getText();
        return text.substring(text.lastIndexOf('.') + 1);
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

    private void collectValues(final FileCtx ctx, final KtExpression expr, final List<String> out) {
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
        for (final var child : expr.getChildren()) {
            if (child instanceof KtExpression) {
                collectValues(ctx, (KtExpression) child, out);
            }
        }
    }

    /**
     * The value of a named annotation argument, like
     * {@code @Fork(ifActive = Flavour.MPK, to = "flavouredMpk")}. Positional
     * arguments map by declaration order: the first one is
     * {@code ifActive}, the second one the template name.
     */
    private String argValue(final FileCtx ctx, final KtAnnotationEntry annotation, final String name) {
        if (annotation.getValueArgumentList() == null) {
            return null;
        }
        final List<KtValueArgument> args = annotation.getValueArgumentList().getArguments();
        int positional = 0;
        for (final KtValueArgument arg : args) {
            final KtValueArgumentName argName = arg.getArgumentName();
            final boolean matches = argName != null
                    ? name.equals(argName.getAsName().getIdentifier())
                    : ("ifActive".equals(name) ? positional == 0 : positional == 1);
            if (matches) {
                return arg.getArgumentExpression() == null
                        ? null : constantOf(ctx, arg.getArgumentExpression());
            }
            if (argName == null) {
                positional++;
            }
        }
        return null;
    }

    private String constantOf(final FileCtx ctx, final KtExpression expr) {
        final List<String> out = new ArrayList<>();
        collectValues(ctx, expr, out);
        return out.isEmpty() ? expr.getText() : out.get(0);
    }

    private boolean active(final String flavour) {
        return activeFlavour.length() == 0 || activeFlavour.equalsIgnoreCase(flavour);
    }

    // -------------------------------------------------------------- removals

    private void removeDecl(final KtDeclaration decl, final String internalPrefix, final FileCtx ctx) {
        ctx.edits.removeSpan(decl.getTextRange().getStartOffset(), decl.getTextRange().getEndOffset());
        ctx.removed.add(decl);
        if (decl instanceof KtClassOrObject && !(decl instanceof KtEnumEntry)) {
            final String internal = internalOf((KtClassOrObject) decl, internalPrefix, ctx);
            ctx.result.removedClass(internal);
            ctx.result.info("Remove class " + internal.replace('/', '.'));
            return;
        }
        final String name = decl instanceof KtNamedDeclaration
                ? ((KtNamedDeclaration) decl).getName() : null;
        if (name != null) {
            ctx.result.info("Remove " + kindOf(decl) + " " + name);
        }
    }

    private String kindOf(final KtDeclaration decl) {
        if (decl instanceof KtEnumEntry) {
            return "enum entry";
        }
        if (decl instanceof KtNamedFunction) {
            return "method";
        }
        if (decl instanceof KtProperty) {
            return "property";
        }
        return decl.getClass().getSimpleName();
    }

    private String internalOf(final KtClassOrObject decl, final String internalPrefix, final FileCtx ctx) {
        final String name = nameOf(decl);
        return internalPrefix.isEmpty()
                ? internal(ctx.pkg, name == null ? "" : name)
                : internalPrefix + "$" + name;
    }

    private String nameOf(final KtClassOrObject decl) {
        if (decl instanceof KtObjectDeclaration && ((KtObjectDeclaration) decl).isCompanion()) {
            return "Companion";
        }
        return ((KtNamedDeclaration) decl).getName();
    }

    private String topNameOf(final KtDeclaration decl) {
        if (decl instanceof KtClassOrObject && !(decl instanceof KtEnumEntry)) {
            return nameOf((KtClassOrObject) decl);
        }
        return null;
    }

    private String internal(final String pkg, final String name) {
        return pkg.replace('.', '/') + (pkg.isEmpty() ? "" : "/") + name;
    }

    // ----------------------------------------------------------------- output

    /**
     * The jitter imports leave the variant together with their
     * annotations: an import is dropped when the annotation it names no
     * longer occurs.
     */
    private String pruneImports(final String text, final List<String> imports) {
        String out = text;
        for (final String imp : imports) {
            if (!imp.startsWith(JITTER_API_IMPORT) || !out.contains("import " + imp)) {
                continue;
            }
            final String simple = imp.substring(JITTER_API_IMPORT.length());
            final boolean used = Pattern
                    .compile("(?<![A-Za-z0-9_.])" + Pattern.quote(simple) + "(?![A-Za-z0-9_])")
                    .matcher(out.replace("import " + imp, "")).find();
            if (!used) {
                out = out.replaceFirst("(?m)^import " + Pattern.quote(imp) + "\\n", "");
            }
        }
        return out;
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

    private String read(final Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private String fileName(final Path file) {
        return file.getFileName().toString();
    }

    private Path relative(final Path file) {
        for (final Path dir : sourceDirs) {
            if (file.startsWith(dir)) {
                return dir.relativize(file);
            }
        }
        return file.getFileName();
    }

    // --------------------------------------------------------------- contexts

    private final class FileCtx {
        private final Path file;
        private final String text;
        private final String pkg;
        private final List<String> imports;
        private final StripResult result;
        private final Set<Path> deletedFiles;
        private final Edits edits;
        private final Set<KtDeclaration> removed = new HashSet<>();
        // the merged content of a top level @Alter that replaces the file
        private String replacement;
        private List<String> replacementImports = List.of();

        private FileCtx(final Path file, final String text, final KtFile ktFile,
                        final StripResult result, final Set<Path> deletedFiles) {
            this.file = file;
            this.text = text;
            this.pkg = ktFile.getPackageName();
            this.imports = importsOf(ktFile);
            this.result = result;
            this.deletedFiles = deletedFiles;
            this.edits = new Edits(text);
        }
    }

    private List<String> importsOf(final KtFile ktFile) {
        final List<String> out = new ArrayList<>();
        for (final KtImportDirective directive : ktFile.getImportDirectives()) {
            final String text = directive.getText().trim();
            if (text.startsWith("import ")) {
                out.add(text.substring("import ".length()));
            }
        }
        return out;
    }

    /**
     * Offset based edits over the original text: the spans are collected in
     * a first pass over the PSI and applied back to front in one second
     * pass, so comments, formatting and line numbers outside the edited
     * spans survive untouched.
     */
    private static final class Edits {
        private static final class Span {
            private final int start;
            private final int end;
            private final String replacement;

            private Span(final int start, final int end, final String replacement) {
                this.start = start;
                this.end = end;
                this.replacement = replacement;
            }
        }

        private final String text;
        private final List<Span> spans = new ArrayList<>();

        private Edits(final String text) {
            this.text = text;
        }

        private void remove(final int start, final int end) {
            spans.add(new Span(start, end, ""));
        }

        /**
         * Removes the span: as a whole line when it fills its line, an
         * annotation alone on its line takes the whole line with it,
         * otherwise together with one adjacent whitespace run so no double
         * blanks remain.
         */
        private void removeSpan(final int start, final int end) {
            int s = start;
            int e = end;
            final int lineStart = s == 0 ? 0 : text.lastIndexOf('\n', s - 1) + 1;
            final int nl = text.indexOf('\n', e);
            final int lineEnd = nl < 0 ? text.length() : nl;
            final boolean prefixBlank = text.substring(lineStart, s).isBlank();
            final boolean suffixBlank = text.substring(e, lineEnd).isBlank();
            if (prefixBlank && suffixBlank) {
                remove(lineStart, nl < 0 ? text.length() : nl + 1);
            } else {
                final int wsAfter = whitespaceRun(text, e, lineEnd, true);
                if (wsAfter > 0) {
                    e += wsAfter;
                } else {
                    s -= whitespaceRun(text, lineStart, s, false);
                }
                remove(s, e);
            }
        }

        private int whitespaceRun(final String text, final int from, final int to,
                                  final boolean forward) {
            int count = 0;
            if (forward) {
                for (int i = from; i < to && (text.charAt(i) == ' ' || text.charAt(i) == '\t'); i++) {
                    count++;
                }
            } else {
                for (int i = to - 1; i >= from
                        && (text.charAt(i) == ' ' || text.charAt(i) == '\t'); i--) {
                    count++;
                }
            }
            return count;
        }

        /**
         * Removes a list element (constructor parameter) together with its
         * separating comma.
         */
        private void removeListElement(final int start, final int end,
                                       final int prevEnd, final int nextStart) {
            int s = start;
            int e = end;
            final int commaAfter = text.indexOf(',', e);
            if (commaAfter >= e && commaAfter < nextStart) {
                e = commaAfter + 1;
            } else {
                final int commaBefore = text.lastIndexOf(',', s);
                if (commaBefore >= prevEnd && commaBefore < s) {
                    s = commaBefore;
                }
            }
            removeSpan(s, e);
        }

        private void replace(final int start, final int end, final String replacement) {
            spans.add(new Span(start, end, replacement));
        }

        private void insert(final int at, final String text) {
            spans.add(new Span(at, at, text));
        }

        private String apply() {
            spans.sort((a, b) -> Integer.compare(b.start, a.start));
            final StringBuilder out = new StringBuilder(text);
            int frontier = Integer.MAX_VALUE;
            for (final Span span : spans) {
                if (span.end > frontier) {
                    continue; // inside an already removed span
                }
                out.replace(span.start, span.end, span.replacement);
                frontier = span.start;
            }
            return out.toString();
        }
    }

    private static final class TemplateSource {
        private final String text;
        private final Path file;

        private TemplateSource(final String text, final Path file) {
            this.text = text;
            this.file = file;
        }
    }

}
