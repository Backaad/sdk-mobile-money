package io.github.backaad.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Rules enforced on the code itself. */
class CodeRulesTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /**
     * Rule 3: signatures and tokens are compared in constant time. Every comparison goes through
     * {@code ConstantTime}, which is the only place allowed to call {@code MessageDigest.isEqual}.
     */
    @Test
    void signaturesAreComparedInConstantTimeOnly() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                boolean isConstantTime = file.endsWith(Path.of("internal", "ConstantTime.java"));
                if (!isConstantTime && source.contains("MessageDigest.isEqual")) {
                    offenders.add(file + " calls MessageDigest.isEqual directly");
                }
                if (source.contains("Arrays.equals(")) {
                    offenders.add(file + " compares arrays with Arrays.equals");
                }
                if (source.matches("(?s).*(signature|Signature|token|Token)\\w*\\.equals\\(.*")) {
                    offenders.add(file + " compares a signature or token with equals()");
                }
            }
        }
        assertThat(offenders).isEmpty();
        String jwt = Files.readString(MAIN_SOURCES.resolve(
                "io/github/backaad/mobilemoney/internal/Hs256Jwt.java"), StandardCharsets.UTF_8);
        assertThat(jwt).contains("ConstantTime.equals(expected, provided)");
    }

    /** Jackson is an implementation detail: no public signature outside {@code .internal} mentions it. */
    @Test
    void jacksonNeverAppearsInThePublicApi() throws IOException, URISyntaxException, ClassNotFoundException {
        Path classes = Path.of(MobileMoney.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('/', '.').replace(".class", "");
                if (name.contains(".internal.") || name.endsWith("package-info")) {
                    continue;
                }
                Class<?> type = Class.forName(name);
                if (!Modifier.isPublic(type.getModifiers())) {
                    continue;
                }
                for (Method m : type.getDeclaredMethods()) {
                    if (isApi(m.getModifiers())) {
                        check(offenders, type, m.toGenericString());
                    }
                }
                for (Constructor<?> c : type.getDeclaredConstructors()) {
                    if (isApi(c.getModifiers())) {
                        check(offenders, type, c.toGenericString());
                    }
                }
                for (Field f : type.getDeclaredFields()) {
                    if (isApi(f.getModifiers())) {
                        check(offenders, type, f.toGenericString());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static boolean isApi(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void check(List<String> offenders, Class<?> type, String signature) {
        if (signature.contains("tools.jackson") || signature.contains("com.fasterxml")) {
            offenders.add(type.getName() + ": " + signature);
        }
    }
}
