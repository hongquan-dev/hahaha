package com.fooddelivery.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-wide check that {@code ARCHITECTURE.md} at the repository root mentions every module and every top-level
 * class of every module's {@code src/main/java}, written as {@code `ClassName`}, so the document cannot silently fall
 * behind the code.
 *
 * <p>Lives in common-lib because it is built first and has no code of its own; it reads the other modules' sources as
 * files. Maven runs tests with the module directory as working directory, so the repository root is its parent.
 */
class ArchitectureDocTest {
    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    @Test
    void everyModuleIsDocumented() throws IOException {
        String doc = readDoc();

        assertThat(modules()).isNotEmpty()
                .allSatisfy(module -> assertThat(doc).as("module %s in ARCHITECTURE.md", module)
                        .contains("`" + module.getFileName() + "`"));
    }

    @Test
    void everyMainClassIsDocumented() throws IOException {
        String doc = readDoc();

        List<String> missing = mainClassNames().stream()
                .filter(name -> !doc.contains("`" + name + "`"))
                .toList();

        assertThat(missing).as("classes missing from ARCHITECTURE.md (add them to the class map)").isEmpty();
    }

    private static String readDoc() throws IOException {
        return Files.readString(ROOT.resolve("ARCHITECTURE.md"));
    }

    // Subdirectories of the root with their own pom.xml, e.g. fd-auth-service
    private static List<Path> modules() throws IOException {
        try (Stream<Path> children = Files.list(ROOT)) {
            return children.filter(dir -> Files.isRegularFile(dir.resolve("pom.xml"))).sorted().toList();
        }
    }

    // File names of src/main/java/**/*.java in every module, e.g. "AuthService"
    private static List<String> mainClassNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (Path module : modules()) {
            Path sources = module.resolve("src/main/java");
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sources)) {
                files.map(file -> file.getFileName().toString())
                        .filter(name -> name.endsWith(".java") && !name.equals("package-info.java"))
                        .map(name -> name.substring(0, name.length() - ".java".length()))
                        .forEach(names::add);
            }
        }
        assertThat(names).as("main classes found under %s", ROOT).isNotEmpty();
        return names;
    }
}
