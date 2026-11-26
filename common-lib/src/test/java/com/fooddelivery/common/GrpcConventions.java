package com.fooddelivery.common;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rules of {@code docs/grpc-conventions.md} that can be read from the source files, so a gRPC added by hand or by an
 * AI agent that does not follow them fails the build of common-lib. Each check returns one message per broken rule,
 * empty if the file follows them all; {@link #checkRepository} runs every check on the whole repository.
 *
 * <p>Reads files as text, like {@code ArchitectureDocTest}: no class is loaded and no other module is compiled.
 */
final class GrpcConventions {

    // Where the conventions are explained, appended to every message
    static final String DOC = " (see docs/grpc-conventions.md)";

    // <service>/v<n>/<name>_service.proto under fd-grpc-api/src/main/proto, e.g. "auth/v1/user_service.proto"
    private static final Pattern PROTO_PATH =
            Pattern.compile("([a-z][a-z0-9]*)/(v\\d+)/[a-z][a-z0-9_]*_service\\.proto");
    private static final Pattern RPC = Pattern.compile("^\\s*rpc\\s+(\\w+)");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    // Classes generated from a contract, e.g. "import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;"
    private static final Pattern GENERATED_IMPORT =
            Pattern.compile("^import\\s+com\\.fooddelivery\\.grpc\\.[a-z0-9]+\\.v\\d+\\.", Pattern.MULTILINE);
    private static final Pattern GRPC_IMPORT = Pattern.compile("^import\\s+io\\.grpc\\.", Pattern.MULTILINE);
    private static final Pattern GRPC_SERVICE_ANNOTATION = Pattern.compile("^@GrpcService\\b", Pattern.MULTILINE);
    private static final Pattern IMPORT_GRPC_CLIENTS = Pattern.compile("^@ImportGrpcClients\\b", Pattern.MULTILINE);

    // Modules that hold the shared gRPC code or no code, not services
    private static final List<String> NOT_SERVICES = List.of("common-lib", "fd-grpc-api");

    private GrpcConventions() {
    }

    /**
     * Rules of a contract: path, {@code package}, {@code java_package}, {@code java_multiple_files}, and a comment
     * above every {@code rpc} (it lists the errors).
     *
     * @param path    path under {@code fd-grpc-api/src/main/proto}, with {@code /}, e.g.
     *                {@code "auth/v1/user_service.proto"}
     * @param content the file
     */
    static List<String> checkProto(String path, String content) {
        List<String> problems = new ArrayList<>();
        Matcher matcher = PROTO_PATH.matcher(path);
        if (!matcher.matches()) {
            problems.add(path + ": must be <service>/v<n>/<name>_service.proto, e.g. auth/v1/user_service.proto" + DOC);
            return problems;
        }
        String service = matcher.group(1);
        String version = matcher.group(2);
        if (!content.contains("package fooddelivery." + service + "." + version + ";")) {
            problems.add(path + ": must declare package fooddelivery." + service + "." + version + DOC);
        }
        if (!content.contains("option java_package = \"com.fooddelivery.grpc." + service + "." + version + "\";")) {
            problems.add(path + ": must declare option java_package = \"com.fooddelivery.grpc." + service + "."
                    + version + "\"" + DOC);
        }
        if (!content.contains("option java_multiple_files = true;")) {
            problems.add(path + ": must declare option java_multiple_files = true" + DOC);
        }
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher rpc = RPC.matcher(lines[i]);
            if (rpc.find() && (i == 0 || !lines[i - 1].trim().startsWith("//"))) {
                problems.add(path + ": rpc " + rpc.group(1) + " needs a comment above it listing its errors" + DOC);
            }
        }
        return problems;
    }

    /**
     * Rules of a main Java class of a service: what a gRPC server class, a gRPC client class and the stub
     * configuration are called and where they live, that only they touch gRPC or protobuf classes, that every client
     * maps errors with {@code GrpcClientErrors}, and that servers and clients have a test.
     *
     * @param path       path under {@code src/main/java}, with {@code /}, e.g.
     *                   {@code "com/fooddelivery/auth/grpc/UserGrpcService.java"}
     * @param content    the file
     * @param testExists whether a path under {@code src/test/java} exists, e.g.
     *                   {@code "com/fooddelivery/auth/grpc/UserGrpcServiceTest.java"}
     */
    static List<String> checkJava(String path, String content, Predicate<String> testExists) {
        List<String> problems = new ArrayList<>();
        String name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".java".length());
        Matcher packageMatcher = PACKAGE.matcher(content);
        String pkg = packageMatcher.find() ? packageMatcher.group(1) : "";
        String testPath = path.substring(0, path.length() - ".java".length()) + "Test.java";

        boolean server = GRPC_SERVICE_ANNOTATION.matcher(content).find();
        boolean config = IMPORT_GRPC_CLIENTS.matcher(content).find();
        boolean client = name.endsWith("GrpcClient");
        boolean usesGrpc = GENERATED_IMPORT.matcher(content).find() || GRPC_IMPORT.matcher(content).find();

        if (server) {
            if (!name.endsWith("GrpcService") || !pkg.endsWith(".grpc")) {
                problems.add(path + ": a @GrpcService class must be named XxxGrpcService in package <service>.grpc"
                        + DOC);
            }
            if (!testExists.test(testPath)) {
                problems.add(path + ": needs " + name + "Test in src/test/java, same package" + DOC);
            }
        }
        if (config && !(name.equals("GrpcClientConfig") && pkg.endsWith(".config"))) {
            problems.add(path + ": @ImportGrpcClients belongs in GrpcClientConfig in package <service>.config" + DOC);
        }
        if (client) {
            if (!pkg.endsWith(".client")) {
                problems.add(path + ": a gRPC client must be in package <service>.client" + DOC);
            }
            if (!content.contains("GrpcClientErrors.toBusinessException")) {
                problems.add(path + ": a gRPC client must turn StatusRuntimeException into BusinessException with "
                        + "GrpcClientErrors.toBusinessException" + DOC);
            }
            if (!testExists.test(testPath)) {
                problems.add(path + ": needs " + name + "Test in src/test/java, same package" + DOC);
            }
        }
        if (usesGrpc && !server && !config && !client) {
            problems.add(path + ": only XxxGrpcService, XxxGrpcClient and GrpcClientConfig may use gRPC or protobuf "
                    + "classes; return records to the rest of the service" + DOC);
        }
        return problems;
    }

    /**
     * Rule of the ports of a service with a gRPC server: gRPC port = HTTP port + 1000, e.g. 8081 -> 9081.
     *
     * @param module     module name, for the message
     * @param properties its {@code application.properties.example}, the template every local
     *                   {@code application.properties} is copied from
     */
    static List<String> checkServerPort(String module, String properties) throws IOException {
        Properties values = new Properties();
        values.load(new StringReader(properties));
        String http = values.getProperty("server.port");
        String grpc = values.getProperty("spring.grpc.server.port");
        if (http == null || grpc == null || Integer.parseInt(grpc.trim()) != Integer.parseInt(http.trim()) + 1000) {
            return List.of(module + ": has a gRPC server, so application.properties.example needs "
                    + "spring.grpc.server.port = server.port + 1000, got server.port=" + http
                    + ", spring.grpc.server.port=" + grpc + DOC);
        }
        return List.of();
    }

    // Every check on the repository whose root is given: the contracts of fd-grpc-api, then each service module
    static List<String> checkRepository(Path root) throws IOException {
        List<String> problems = new ArrayList<>();
        Path protoRoot = root.resolve("fd-grpc-api/src/main/proto");
        for (Path proto : files(protoRoot, ".proto")) {
            problems.addAll(checkProto(slashed(protoRoot.relativize(proto)), Files.readString(proto)));
        }
        for (Path module : modules(root)) {
            Path main = module.resolve("src/main/java");
            Path test = module.resolve("src/test/java");
            boolean hasServer = false;
            for (Path source : files(main, ".java")) {
                String content = Files.readString(source);
                hasServer |= GRPC_SERVICE_ANNOTATION.matcher(content).find();
                problems.addAll(checkJava(slashed(main.relativize(source)), content,
                        testPath -> Files.isRegularFile(test.resolve(testPath))));
            }
            // The committed template: application.properties itself is ignored by git, so a fresh clone has none
            Path properties = module.resolve("src/main/resources/application.properties.example");
            if (hasServer && Files.isRegularFile(properties)) {
                problems.addAll(checkServerPort(module.getFileName().toString(), Files.readString(properties)));
            }
        }
        return problems;
    }

    // Service modules: subdirectories of the root with their own pom.xml, except NOT_SERVICES
    private static List<Path> modules(Path root) throws IOException {
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(dir -> Files.isRegularFile(dir.resolve("pom.xml")))
                    .filter(dir -> !NOT_SERVICES.contains(dir.getFileName().toString()))
                    .sorted().toList();
        }
    }

    // Files with this extension under a directory, none if it does not exist
    private static List<Path> files(Path dir, String extension) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(file -> file.toString().endsWith(extension)).sorted().toList();
        }
    }

    private static String slashed(Path path) {
        return path.toString().replace('\\', '/');
    }
}
