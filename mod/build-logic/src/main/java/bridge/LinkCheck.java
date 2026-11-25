package bridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Checks that every class, field and method the given classes use exists on a game version's
 * classpath, mixin targets included, so shared code that would fail to link on a supported version
 * fails the build instead. The JDK is taken as present.
 */
public abstract class LinkCheck extends DefaultTask {

    private static final Pattern TYPE = Pattern.compile("L([^;]+);");
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/";

    /** The classes to check: jars or class folders. */
    @Classpath
    public abstract ConfigurableFileCollection getClasses();

    /** Classes of other version modules: checked as well when the classes above use them. */
    @Classpath
    public abstract ConfigurableFileCollection getBorrowed();

    /** The game version's classpath they must link against. */
    @Classpath
    public abstract ConfigurableFileCollection getTarget();

    /** Written when the check passes, so it reruns only on change. */
    @OutputFile
    public abstract RegularFileProperty getReport();

    private final Map<String, ClassNode> ours = new HashMap<>();
    private final Map<String, ClassNode> theirs = new HashMap<>();
    private final Map<String, ClassNode> borrowed = new HashMap<>();
    private final java.util.ArrayDeque<ClassNode> queue = new java.util.ArrayDeque<>();
    private final Map<String, Set<String>> problems = new TreeMap<>();

    @TaskAction
    public void check() throws IOException {
        for (final File f : getClasses()) {
            read(f, ours, false);
        }
        for (final File f : getBorrowed()) {
            read(f, borrowed, false);
        }
        for (final File f : getTarget()) {
            read(f, theirs, true);
        }
        queue.addAll(ours.values());
        while (!queue.isEmpty()) {
            checkClass(queue.poll());
        }
        if (!problems.isEmpty()) {
            final StringBuilder b = new StringBuilder(problems.size() + " missing on this game version:\n");
            problems.forEach((what, users) -> b.append("  ").append(what).append("  <- ").append(String.join(", ", users)).append('\n'));
            throw new GradleException(b.toString());
        }
        Files.writeString(getReport().get().getAsFile().toPath(), "ok: " + ours.size() + " classes\n");
    }

    private static void read(final File f, final Map<String, ClassNode> into, final boolean skipCode) throws IOException {
        if (!f.exists()) {
            return;
        }
        final int flags = skipCode ? ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES : 0;
        if (f.isDirectory()) {
            try (Stream<Path> walk = Files.walk(f.toPath())) {
                for (final Path p : walk.filter(x -> x.toString().endsWith(".class")).toList()) {
                    put(into, Files.readAllBytes(p), flags);
                }
            }
            return;
        }
        if (f.getName().endsWith(".class")) {
            put(into, Files.readAllBytes(f.toPath()), flags);
            return;
        }
        if (!f.getName().endsWith(".jar")) {
            return;
        }
        try (ZipFile z = new ZipFile(f)) {
            for (final ZipEntry e : java.util.Collections.list(z.entries())) {
                final String name = e.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
                    continue;
                }
                try (InputStream in = z.getInputStream(e)) {
                    put(into, in.readAllBytes(), flags);
                }
            }
        }
    }

    private static void put(final Map<String, ClassNode> into, final byte[] bytes, final int flags) {
        final ClassNode n = new ClassNode();
        new ClassReader(bytes).accept(n, flags);
        into.putIfAbsent(n.name, n);
    }

    private final Map<String, ClassNode> platform = new HashMap<>();

    private ClassNode find(final String name) {
        ClassNode c = ours.get(name);
        if (c == null && borrowed.containsKey(name)) {
            // Used from a borrowed module: it must link here too.
            c = borrowed.remove(name);
            ours.put(name, c);
            queue.add(c);
        }
        if (c == null) {
            c = theirs.get(name);
        }
        if (c == null && !name.startsWith("org/spongepowered/") && !name.startsWith("org/objectweb/asm/")) {
            c = platform.computeIfAbsent(name, LinkCheck::readPlatform);
        }
        return c;
    }

    /** A JDK class, read like the others, or null. */
    private static ClassNode readPlatform(final String name) {
        try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
            if (in == null) {
                return null;
            }
            final ClassNode n = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(n, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return n;
        } catch (final IOException e) {
            return null;
        }
    }

    /** The JDK, and Mixin and ASM, which both loaders provide. */
    private static boolean jdk(final String name) {
        return name.startsWith("org/spongepowered/") || name.startsWith("org/objectweb/asm/") || ClassLoader.getPlatformClassLoader().getResource(name + ".class") != null;
    }

    private void problem(final String what, final ClassNode user) {
        problems.computeIfAbsent(what, k -> new TreeSet<>()).add(user.name.substring(user.name.lastIndexOf('/') + 1));
    }

    private void needClass(final String raw, final ClassNode user) {
        final String name = raw.replaceAll("^\\[+", "").replaceAll("^L(.*);$", "$1");
        if (name.length() <= 1 || find(name) != null || jdk(name)) {
            return;
        }
        problem("class " + name, user);
    }

    private void needTypes(final String desc, final ClassNode user) {
        final Matcher m = TYPE.matcher(desc);
        while (m.find()) {
            needClass(m.group(1), user);
        }
    }

    /** Whether a member is found walking up from owner; a JDK class on the way counts as found. */
    private boolean has(final String owner, final String name, final String desc, final boolean method, final Set<String> seen) {
        if (!seen.add(owner)) {
            return false;
        }
        final ClassNode c = find(owner);
        if (c == null) {
            return owner.startsWith("org/spongepowered/") || owner.startsWith("org/objectweb/asm/");
        }
        if (method) {
            for (final MethodNode m : c.methods) {
                if (m.name.equals(name) && (desc == null || m.desc.equals(desc) || polymorphic(m))) {
                    return true;
                }
            }
        } else {
            for (final FieldNode f : c.fields) {
                if (f.name.equals(name) && (desc == null || f.desc.equals(desc))) {
                    return true;
                }
            }
        }
        if (c.superName != null && has(c.superName, name, desc, method, seen)) {
            return true;
        }
        for (final String i : c.interfaces) {
            if (has(i, name, desc, method, seen)) {
                return true;
            }
        }
        return false;
    }

    /** MethodHandle.invokeExact and its kind take any signature. */
    private static boolean polymorphic(final MethodNode m) {
        final int both = org.objectweb.asm.Opcodes.ACC_NATIVE | org.objectweb.asm.Opcodes.ACC_VARARGS;
        return (m.access & both) == both;
    }

    private void needMember(final String rawOwner, final String name, final String desc, final boolean method, final ClassNode user) {
        if (rawOwner.startsWith("[")) {
            return;
        }
        if (find(rawOwner) == null) {
            needClass(rawOwner, user);
            return;
        }
        if (!has(rawOwner, name, desc, method, new HashSet<>())) {
            problem((method ? "method " : "field ") + rawOwner + "." + name + (desc == null ? "" : desc), user);
        }
    }

    private void checkClass(final ClassNode c) {
        if (c.superName != null) {
            needClass(c.superName, c);
        }
        c.interfaces.forEach(i -> needClass(i, c));
        final List<String> targets = mixinTargets(c);
        for (final FieldNode f : c.fields) {
            needTypes(f.desc, c);
            if (annotation(f.invisibleAnnotations, "Shadow") != null || annotation(f.visibleAnnotations, "Shadow") != null) {
                targets.forEach(t -> needMember(t, f.name, f.desc, false, c));
            }
        }
        for (final MethodNode m : c.methods) {
            if (annotation(m.invisibleAnnotations, "VersionSpecific", "Lio/github/pix3lpirat3/mfbridge/common/version/") != null) {
                continue;
            }
            needTypes(m.desc, c);
            checkMixinMethod(c, m, targets);
            if (m.instructions == null) {
                continue;
            }
            for (final AbstractInsnNode i : m.instructions) {
                if (i instanceof MethodInsnNode mi) {
                    if (!targets.isEmpty() && mi.owner.equals(c.name)) {
                        continue;
                    }
                    needMember(mi.owner, mi.name, mi.desc, true, c);
                } else if (i instanceof FieldInsnNode fi) {
                    if (!targets.isEmpty() && fi.owner.equals(c.name)) {
                        continue;
                    }
                    needMember(fi.owner, fi.name, fi.desc, false, c);
                } else if (i instanceof TypeInsnNode ti) {
                    needClass(ti.desc, c);
                } else if (i instanceof LdcInsnNode l && l.cst instanceof Type t && t.getSort() == Type.OBJECT) {
                    needClass(t.getInternalName(), c);
                } else if (i instanceof InvokeDynamicInsnNode id) {
                    for (final Object o : id.bsmArgs) {
                        if (o instanceof Handle h && !h.getOwner().equals(c.name)) {
                            needMember(h.getOwner(), h.getName(), h.getDesc(), h.getTag() > 4, c);
                        }
                    }
                }
            }
        }
    }

    /** The classes a mixin applies to, or an empty list for a plain class. */
    private List<String> mixinTargets(final ClassNode c) {
        final List<String> out = new ArrayList<>();
        final AnnotationNode a = annotation(c.invisibleAnnotations, "Mixin");
        if (a == null || a.values == null) {
            return out;
        }
        for (int i = 0; i < a.values.size(); i += 2) {
            final Object v = a.values.get(i + 1);
            if (v instanceof List<?> list) {
                for (final Object o : list) {
                    final String name = o instanceof Type t ? t.getInternalName() : String.valueOf(o).replace('.', '/');
                    out.add(name);
                    needClass(name, c);
                }
            }
        }
        return out;
    }

    private void checkMixinMethod(final ClassNode c, final MethodNode m, final List<String> targets) {
        if (targets.isEmpty()) {
            return;
        }
        final List<AnnotationNode> all = new ArrayList<>();
        if (m.invisibleAnnotations != null) {
            all.addAll(m.invisibleAnnotations);
        }
        if (m.visibleAnnotations != null) {
            all.addAll(m.visibleAnnotations);
        }
        for (final AnnotationNode a : all) {
            if (!a.desc.startsWith(MIXIN)) {
                continue;
            }
            final String kind = a.desc.substring(a.desc.lastIndexOf('/') + 1, a.desc.length() - 1);
            switch (kind) {
                case "Shadow" -> targets.forEach(t -> needMember(t, m.name, m.desc, true, c));
                case "Accessor" -> {
                    final String name = value(a, "value", accessorName(m.name));
                    targets.forEach(t -> needMember(t, name, null, false, c));
                }
                case "Invoker" -> {
                    final String name = value(a, "value", invokerName(m.name));
                    targets.forEach(t -> needMember(t, name, null, true, c));
                }
                default -> {
                    for (final String spec : values(a, "method")) {
                        final String name = spec.replaceAll("^L[^;]+;", "").replaceAll("\\(.*$", "");
                        final String desc = spec.contains("(") ? spec.substring(spec.indexOf('(')) : null;
                        targets.forEach(t -> needMember(t, name, desc, true, c));
                    }
                }
            }
        }
    }

    private static String accessorName(final String method) {
        final String bare = method.replaceFirst("^(get|set|is)", "");
        return Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
    }

    private static String invokerName(final String method) {
        final String bare = method.replaceFirst("^(invoke|call)", "");
        return Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
    }

    private static AnnotationNode annotation(final List<AnnotationNode> list, final String simple, final String pkg) {
        if (list != null) {
            for (final AnnotationNode a : list) {
                if (a.desc.equals(pkg + simple + ";")) {
                    return a;
                }
            }
        }
        return null;
    }

    private static AnnotationNode annotation(final List<AnnotationNode> list, final String simple) {
        if (list == null) {
            return null;
        }
        for (final AnnotationNode a : list) {
            if (a.desc.equals(MIXIN + simple + ";") || a.desc.endsWith("/" + simple + ";") && a.desc.startsWith(MIXIN)) {
                return a;
            }
        }
        return null;
    }

    private static List<String> values(final AnnotationNode a, final String key) {
        final List<String> out = new ArrayList<>();
        if (a.values == null) {
            return out;
        }
        for (int i = 0; i < a.values.size(); i += 2) {
            if (a.values.get(i).equals(key)) {
                final Object v = a.values.get(i + 1);
                if (v instanceof List<?> list) {
                    list.forEach(o -> out.add(String.valueOf(o)));
                } else {
                    out.add(String.valueOf(v));
                }
            }
        }
        return out;
    }

    private static String value(final AnnotationNode a, final String key, final String fallback) {
        final List<String> v = values(a, key);
        return v.isEmpty() || v.get(0).isEmpty() ? fallback : v.get(0);
    }

}
