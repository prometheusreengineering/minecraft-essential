package studio.dreamys.prometheus.essential.verity;

import gg.essential.Essential;
import gg.essential.lib.gson.JsonElement;
import gg.essential.lib.gson.JsonObject;
import gg.essential.lib.gson.JsonParser;
import ing.prometheusreengineer.verity.api.Verity;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Self-checks reported to <a href="https://github.com/prometheusreengineering/verity">Verity</a>, which runs this
 * jar against the Essential build pinned in {@code verity.json}, on each Minecraft version and loader it ships for.
 * Everything here is a no-op unless the game was launched by Verity.
 *
 * <p>The main check proves that every mixin in {@value #MIXIN_CONFIG} was applied to this Essential build: each
 * target class is loaded (which applies our mixins, and fails if a required injection point is missing) and must
 * then carry methods merged from our mixin. None of it needs an Essential account or connection.
 */
public final class VerityChecks {
    static final String MIXIN_CONFIG = "prometheus.essential.mixins.json";
    private static final String MIXIN_MERGED = "org.spongepowered.asm.mixin.transformer.meta.MixinMerged";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

    private static boolean ran;

    private VerityChecks() {}

    /** Called when {@code Essential#init} returns, on the thread that ran it. */
    public static synchronized void afterEssentialInit() {
        if (ran || !Verity.isActive()) return;
        ran = true;

        Verity.pass("Essential#init patched");
        Verity.test("prometheus folder ready", new Runnable() {
            @Override
            public void run() {
                if (!Files.isDirectory(EssentialCosmeticsManager.PROMETHEUS_ESSENTIAL_FOLDER)) {
                    throw new AssertionError("missing " + EssentialCosmeticsManager.PROMETHEUS_ESSENTIAL_FOLDER);
                }
            }
        });
        checkMixins();
        // Offline (no Microsoft account, e.g. pull requests from forks) Essential can't connect, so only the above run.
        GameChecks.start(!"false".equals(System.getProperty("prometheus.verity.online")));
    }

    private static void checkMixins() {
        final ClassLoader own = VerityChecks.class.getClassLoader();
        final ClassLoader game = Essential.class.getClassLoader();
        List<String> mixins;
        try {
            mixins = mixinClasses(own);
        } catch (Throwable t) {
            Verity.fail("read " + MIXIN_CONFIG, t);
            return;
        }
        for (final String mixin : mixins) {
            Verity.test("mixin " + mixin.substring(mixin.lastIndexOf('.') + 1) + " applied", new Runnable() {
                @Override
                public void run() {
                    Set<String> targets;
                    try {
                        targets = targetsOf(own, mixin);
                    } catch (IOException e) {
                        throw new IllegalStateException("cannot read " + mixin, e);
                    }
                    if (targets.isEmpty()) throw new AssertionError("no @Mixin targets in " + mixin);
                    for (String target : targets) {
                        Class<?> cls;
                        try {
                            // Loading (not initializing) the class is what applies our mixins to it.
                            cls = Class.forName(target, false, game);
                        } catch (ClassNotFoundException e) {
                            throw new AssertionError(target + " does not exist in this Essential build");
                        }
                        if (!hasMergedMethod(cls, mixin)) {
                            throw new AssertionError(target + " has no methods merged from " + mixin);
                        }
                    }
                }
            });
        }
    }

    /** Fully qualified names of the mixins listed in our config. */
    static List<String> mixinClasses(ClassLoader loader) throws IOException {
        InputStream in = loader.getResourceAsStream(MIXIN_CONFIG);
        if (in == null) throw new IOException(MIXIN_CONFIG + " not found");
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            @SuppressWarnings("deprecation") // the static parse methods don't exist in older Gson builds
            JsonObject config = new JsonParser().parse(reader).getAsJsonObject();
            String pkg = config.get("package").getAsString();
            List<String> names = new ArrayList<>();
            for (String key : new String[] {"mixins", "client"}) {
                if (!config.has(key)) continue;
                for (JsonElement e : config.getAsJsonArray(key)) names.add(pkg + "." + e.getAsString());
            }
            return names;
        }
    }

    /** Reads {@code @Mixin(value = ..., targets = ...)} from the class file; mixin classes must never be loaded directly. */
    static Set<String> targetsOf(ClassLoader loader, String mixin) throws IOException {
        InputStream in = loader.getResourceAsStream(mixin.replace('.', '/') + ".class");
        if (in == null) throw new IOException(mixin + ".class not found");
        final Set<String> targets = new LinkedHashSet<>();
        try {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (!MIXIN_DESC.equals(desc)) return null;
                    return new AnnotationVisitor(Opcodes.ASM5) {
                        @Override
                        public AnnotationVisitor visitArray(final String name) {
                            return new AnnotationVisitor(Opcodes.ASM5) {
                                @Override
                                public void visit(String ignored, Object value) {
                                    if ("value".equals(name) && value instanceof Type) {
                                        targets.add(((Type) value).getClassName());
                                    } else if ("targets".equals(name) && value instanceof String) {
                                        targets.add(((String) value).replace('/', '.'));
                                    }
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            in.close();
        }
        return targets;
    }

    /** Mixin tags every method it merges into a target with {@code @MixinMerged(mixin = "<mixin class>")}. */
    private static boolean hasMergedMethod(Class<?> cls, String mixin) {
        for (Method m : cls.getDeclaredMethods()) {
            for (Annotation a : m.getDeclaredAnnotations()) {
                if (!a.annotationType().getName().equals(MIXIN_MERGED)) continue;
                try {
                    if (mixin.equals(a.annotationType().getMethod("mixin").invoke(a))) return true;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot read @MixinMerged", e);
                }
            }
        }
        return false;
    }

}
