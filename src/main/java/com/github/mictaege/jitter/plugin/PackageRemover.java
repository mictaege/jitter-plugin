package com.github.mictaege.jitter.plugin;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.tree.ClassNode;

/**
 * Removes whole packages whose package-info carries an {@code @OnlyIf} gate
 * that does not include the active flavour. A package gate covers only its
 * direct classes, as under the source transformation - nested packages are
 * not swept along.
 */
final class PackageRemover {

    private static final String PACKAGE_INFO = "/package-info";

    private PackageRemover() {
        super();
    }

    static void remove(final StripContext ctx) {
        final List<String> removedPrefixes = new ArrayList<>();
        for (final ClassNode node : ctx.nodes.values()) {
            if (node.name.endsWith(PACKAGE_INFO)) {
                final List<String> flavours = JitterAnnotations.onlyIfFlavours(node);
                if (flavours != null && flavours.stream().noneMatch(JitterUtil::active)) {
                    final String prefix = node.name.substring(0, node.name.length() - PACKAGE_INFO.length()) + "/";
                    removedPrefixes.add(prefix);
                    ctx.result.info("Remove package " + prefix.replace('/', '.'));
                }
            }
        }
        if (!removedPrefixes.isEmpty()) {
            final Set<String> removed = new TreeSet<>();
            ctx.nodes.keySet().removeIf(name -> {
                for (final String prefix : removedPrefixes) {
                    if (name.startsWith(prefix)
                            && !name.substring(prefix.length()).contains("/")) {
                        removed.add(name);
                        return true;
                    }
                }
                return false;
            });
            removed.forEach(ctx.result::removedClass);
            removed.forEach(name -> ctx.dirty.add(name));
        }
    }

}
