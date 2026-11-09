package studio.dreamys.prometheus.essential.verity;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks every mixin against the Essential build in {@code lib/essential.mock.jar} without starting a game: each
 * method an injector names exists in its target, each {@code INVOKE} it injects at is really called there, and each
 * {@code @Shadow} and {@code @Overwrite} matches. An Essential update that renames something fails here first; Verity
 * then checks the other Minecraft versions' builds in game.
 */
class MixinTargetsTest {
    private static final ClassLoader LOADER = MixinTargetsTest.class.getClassLoader();
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final List<String> INJECTORS = Arrays.asList(INJECT, MODIFY_ARG,
            "Lorg/spongepowered/asm/mixin/injection/Redirect;", "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;");

    private static ClassNode read(String className) throws IOException {
        InputStream in = LOADER.getResourceAsStream(className.replace('.', '/') + ".class");
        assertNotNull(in, className + " not on the classpath");
        try {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } finally {
            in.close();
        }
    }

    @Test
    void theConfigListsEveryMixin() throws IOException {
        List<String> mixins = VerityChecks.mixinClasses(LOADER);
        assertEquals(Arrays.asList(
                "studio.dreamys.prometheus.essential.mixin.gg.essential.MixinEssential",
                "studio.dreamys.prometheus.essential.mixin.gg.essential.cosmetics.MixinExtensionsKt",
                "studio.dreamys.prometheus.essential.mixin.gg.essential.network.connectionmanager.cosmetics.MixinCosmeticsManager",
                "studio.dreamys.prometheus.essential.mixin.gg.essential.network.connectionmanager.cosmetics.MixinOutfitManager",
                "studio.dreamys.prometheus.essential.mixin.gg.essential.network.cosmetics.MixinConversionsKt",
                "studio.dreamys.prometheus.essential.mixin.gg.essential.network.connectionmanager.handler.cosmetics.MixinServerCosmeticsPopulatePacketHandler"),
                mixins);
    }

    @Test
    void eachMixinTargetsTheEssentialClassItsPackageMirrors() throws IOException {
        for (String mixin : VerityChecks.mixinClasses(LOADER)) {
            Set<String> targets = VerityChecks.targetsOf(LOADER, mixin);
            String mirrored = mixin.substring("studio.dreamys.prometheus.essential.mixin.".length()).replace(".Mixin", ".");
            assertEquals(Collections.singleton(mirrored), targets, mixin);
        }
    }

    @TestFactory
    List<DynamicTest> everyInjectionPointExistsInEssential() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (final String mixin : VerityChecks.mixinClasses(LOADER)) {
            final ClassNode mixinNode = read(mixin);
            for (final String target : VerityChecks.targetsOf(LOADER, mixin)) {
                final ClassNode targetNode = read(target);
                for (final MethodNode handler : mixinNode.methods) {
                    for (final AnnotationNode a : annotations(handler.invisibleAnnotations, handler.visibleAnnotations)) {
                        String label = simple(mixin) + "." + handler.name + " -> " + simple(target);
                        if (INJECTORS.contains(a.desc)) {
                            tests.add(DynamicTest.dynamicTest(label, () -> checkInjector(a, targetNode)));
                        } else if (OVERWRITE.equals(a.desc)) {
                            tests.add(DynamicTest.dynamicTest(label + " (overwrite)", () -> {
                                assertNotNull(find(targetNode, handler.name, handler.desc),
                                        "no " + handler.name + handler.desc + " to overwrite in " + target);
                            }));
                        } else if (SHADOW.equals(a.desc)) {
                            tests.add(DynamicTest.dynamicTest(label + " (shadow)", () -> {
                                assertNotNull(find(targetNode, handler.name, handler.desc),
                                        "no " + handler.name + handler.desc + " to shadow in " + target);
                            }));
                        }
                    }
                }
                for (final FieldNode field : mixinNode.fields) {
                    for (AnnotationNode a : annotations(field.invisibleAnnotations, field.visibleAnnotations)) {
                        if (!SHADOW.equals(a.desc)) continue;
                        tests.add(DynamicTest.dynamicTest(simple(mixin) + "." + field.name + " (shadow field)", () -> {
                            FieldNode found = null;
                            for (FieldNode f : targetNode.fields) if (f.name.equals(field.name)) found = f;
                            assertNotNull(found, "no field " + field.name + " in " + target);
                            assertEquals(field.desc, found.desc, "type of " + field.name);
                        }));
                    }
                }
            }
        }
        assertFalse(tests.isEmpty());
        return tests;
    }

    private static void checkInjector(AnnotationNode injector, ClassNode target) {
        List<String> methods = stringList(value(injector, "method"));
        assertFalse(methods.isEmpty(), "the injector names no method");
        for (String method : methods) {
            List<MethodNode> matches = resolve(target, method);
            assertFalse(matches.isEmpty(), "no method " + method + " in " + target.name);
            for (Object at : list(value(injector, "at"))) {
                AnnotationNode atNode = (AnnotationNode) at;
                if (!"INVOKE".equals(value(atNode, "value"))) continue;
                String invoke = (String) value(atNode, "target");
                assertNotNull(invoke, "@At(INVOKE) without a target");
                checkInvokeTargetExists(invoke);
                for (MethodNode m : matches) {
                    assertTrue(calls(m, invoke), target.name + "." + m.name + m.desc + " never calls " + invoke);
                }
            }
        }
    }

    /** {@code Lowner;name(desc)ret} exists. */
    private static void checkInvokeTargetExists(String invoke) {
        int semi = invoke.indexOf(';');
        String owner = invoke.substring(1, semi);
        String nameAndDesc = invoke.substring(semi + 1);
        int paren = nameAndDesc.indexOf('(');
        try {
            assertNotNull(find(read(owner), nameAndDesc.substring(0, paren), nameAndDesc.substring(paren)),
                    "no " + nameAndDesc + " in " + owner);
        } catch (IOException e) {
            fail("cannot read " + owner, e);
        }
    }

    private static boolean calls(MethodNode method, String invoke) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (invoke.equals("L" + call.owner + ";" + call.name + call.desc)) return true;
        }
        return false;
    }

    /** Mixin's method selector: {@code name}, or {@code name(desc)ret}. */
    private static List<MethodNode> resolve(ClassNode target, String selector) {
        int paren = selector.indexOf('(');
        String name = paren < 0 ? selector : selector.substring(0, paren);
        String desc = paren < 0 ? null : selector.substring(paren);
        List<MethodNode> found = new ArrayList<>();
        for (MethodNode m : target.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) found.add(m);
        }
        return found;
    }

    private static MethodNode find(ClassNode target, String name, String desc) {
        for (MethodNode m : target.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    @SafeVarargs
    private static List<AnnotationNode> annotations(List<AnnotationNode>... lists) {
        List<AnnotationNode> all = new ArrayList<>();
        for (List<AnnotationNode> l : lists) if (l != null) all.addAll(l);
        return all;
    }

    private static Object value(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) {
            if (key.equals(a.values.get(i))) {
                Object v = a.values.get(i + 1);
                // enums come as {descriptor, name}
                if (v instanceof String[]) return ((String[]) v)[1];
                return v;
            }
        }
        return null;
    }

    private static List<?> list(Object v) {
        if (v == null) return Collections.emptyList();
        return v instanceof List ? (List<?>) v : Collections.singletonList(v);
    }

    private static List<String> stringList(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : list(v)) out.add((String) o);
        return out;
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    @Test
    void theHelperSpotsAMissingCall() throws IOException {
        // Guards the check itself: a call that isn't there must not count as found.
        ClassNode essential = read("gg.essential.Essential");
        for (MethodNode m : resolve(essential, "init")) {
            assertFalse(calls(m, "Ljava/lang/Object;noSuchMethod()V"));
        }
        assertTrue(resolve(essential, "noSuchMethod").isEmpty());
        assertTrue(resolve(essential, "init()Lno/Such;").isEmpty(), "a selector's descriptor must match too");
    }
}
