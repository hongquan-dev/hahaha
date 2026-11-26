package com.fooddelivery.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-wide check that every gRPC contract and class follows {@code docs/grpc-conventions.md}
 * ({@link #repositoryFollowsTheGrpcConventions}), and unit tests of each rule of {@link GrpcConventions} on sample
 * files written like the templates of that document, so the rules are proven before the first gRPC exists.
 *
 * <p>When the repository check fails, fix the code to follow the conventions, not this test.
 */
class GrpcConventionsTest {
    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    private static final String PROTO = """
            syntax = "proto3";

            package fooddelivery.restaurant.v1;

            option java_package = "com.fooddelivery.grpc.restaurant.v1";
            option java_multiple_files = true;

            service RestaurantService {
              // A restaurant by ID. Errors, with the error code in the "error-code" trailer:
              //   NOT_FOUND (RESTAURANT_NOT_FOUND): no restaurant has this ID
              rpc GetRestaurant(GetRestaurantRequest) returns (RestaurantResponse);
            }
            """;

    private static final String SERVER = """
            package com.fooddelivery.restaurant.grpc;

            import com.fooddelivery.grpc.restaurant.v1.RestaurantServiceGrpc;
            import io.grpc.stub.StreamObserver;
            import org.springframework.grpc.server.service.GrpcService;

            @GrpcService
            public class RestaurantGrpcService extends RestaurantServiceGrpc.RestaurantServiceImplBase {
            }
            """;

    private static final String CLIENT = """
            package com.fooddelivery.order.client;

            import com.fooddelivery.grpc.restaurant.v1.RestaurantServiceGrpc;
            import com.fooddelivery.grpc.support.GrpcClientErrors;
            import io.grpc.StatusRuntimeException;

            @Component
            public class RestaurantGrpcClient {
                public RestaurantInfo getRestaurant(long id) {
                    try {
                        return null;
                    } catch (StatusRuntimeException e) {
                        throw GrpcClientErrors.toBusinessException(e, "RESTAURANT");
                    }
                }
            }
            """;

    private static final String CONFIG = """
            package com.fooddelivery.order.config;

            import com.fooddelivery.grpc.restaurant.v1.RestaurantServiceGrpc;
            import org.springframework.grpc.client.ImportGrpcClients;

            @Configuration
            @ImportGrpcClients(target = "restaurant", types = RestaurantServiceGrpc.RestaurantServiceBlockingStub.class)
            public class GrpcClientConfig {
            }
            """;

    private static final Predicate<String> ALL_TESTS_EXIST = path -> true;
    private static final Predicate<String> NO_TEST_EXISTS = path -> false;

    @Test
    void repositoryFollowsTheGrpcConventions() throws IOException {
        assertThat(GrpcConventions.checkRepository(ROOT)).as("gRPC code not following docs/grpc-conventions.md")
                .isEmpty();
    }

    @Test
    void protoWrittenLikeTheTemplatePasses() {
        assertThat(GrpcConventions.checkProto("restaurant/v1/restaurant_service.proto", PROTO)).isEmpty();
    }

    @Test
    void protoInTheWrongPlaceIsReported() {
        assertThat(GrpcConventions.checkProto("restaurant/restaurant.proto", PROTO))
                .singleElement().asString().contains("<service>/v<n>/<name>_service.proto");
    }

    @Test
    void protoWithAnotherPackageOrJavaPackageIsReported() {
        String content = PROTO.replace("package fooddelivery.restaurant.v1;", "package restaurant;")
                .replace("com.fooddelivery.grpc.restaurant.v1", "com.example.restaurant");

        assertThat(GrpcConventions.checkProto("restaurant/v1/restaurant_service.proto", content))
                .anySatisfy(problem -> assertThat(problem).contains("package fooddelivery.restaurant.v1"))
                .anySatisfy(problem -> assertThat(problem).contains("java_package"))
                .hasSize(2);
    }

    @Test
    void protoWithoutMultipleFilesIsReported() {
        String content = PROTO.replace("option java_multiple_files = true;\n", "");

        assertThat(GrpcConventions.checkProto("restaurant/v1/restaurant_service.proto", content))
                .singleElement().asString().contains("java_multiple_files");
    }

    @Test
    void rpcWithoutCommentIsReported() {
        String content = PROTO.replace("//   NOT_FOUND (RESTAURANT_NOT_FOUND): no restaurant has this ID\n", "\n");

        assertThat(GrpcConventions.checkProto("restaurant/v1/restaurant_service.proto", content))
                .singleElement().asString().contains("rpc GetRestaurant needs a comment");
    }

    @Test
    void classesWrittenLikeTheTemplatesPass() {
        assertThat(GrpcConventions.checkJava("com/fooddelivery/restaurant/grpc/RestaurantGrpcService.java", SERVER,
                ALL_TESTS_EXIST)).isEmpty();
        assertThat(GrpcConventions.checkJava("com/fooddelivery/order/client/RestaurantGrpcClient.java", CLIENT,
                ALL_TESTS_EXIST)).isEmpty();
        assertThat(GrpcConventions.checkJava("com/fooddelivery/order/config/GrpcClientConfig.java", CONFIG,
                NO_TEST_EXISTS)).isEmpty();
    }

    @Test
    void serverWithAnotherNameOrPackageIsReported() {
        String content = SERVER.replace("package com.fooddelivery.restaurant.grpc;",
                "package com.fooddelivery.restaurant.service;");

        assertThat(GrpcConventions.checkJava("com/fooddelivery/restaurant/service/RestaurantServer.java", content,
                ALL_TESTS_EXIST)).singleElement().asString().contains("XxxGrpcService in package <service>.grpc");
    }

    @Test
    void serverWithoutTestIsReported() {
        assertThat(GrpcConventions.checkJava("com/fooddelivery/restaurant/grpc/RestaurantGrpcService.java", SERVER,
                NO_TEST_EXISTS)).singleElement().asString().contains("RestaurantGrpcServiceTest");
    }

    @Test
    void clientOutsideTheClientPackageIsReported() {
        String content = CLIENT.replace("package com.fooddelivery.order.client;",
                "package com.fooddelivery.order.service;");

        assertThat(GrpcConventions.checkJava("com/fooddelivery/order/service/RestaurantGrpcClient.java", content,
                ALL_TESTS_EXIST)).singleElement().asString().contains("package <service>.client");
    }

    @Test
    void clientThatMapsErrorsItselfIsReported() {
        String content = CLIENT.replace("GrpcClientErrors.toBusinessException(e, \"RESTAURANT\")",
                "new BusinessException(ApiStatus.BAD_GATEWAY, \"FAILED\", \"failed\")");

        assertThat(GrpcConventions.checkJava("com/fooddelivery/order/client/RestaurantGrpcClient.java", content,
                ALL_TESTS_EXIST)).singleElement().asString().contains("GrpcClientErrors.toBusinessException");
    }

    @Test
    void clientWithoutTestIsReported() {
        assertThat(GrpcConventions.checkJava("com/fooddelivery/order/client/RestaurantGrpcClient.java", CLIENT,
                NO_TEST_EXISTS)).singleElement().asString().contains("RestaurantGrpcClientTest");
    }

    @Test
    void stubConfigurationWithAnotherNameIsReported() {
        assertThat(GrpcConventions.checkJava("com/fooddelivery/order/config/StubConfig.java",
                CONFIG.replace("class GrpcClientConfig", "class StubConfig"), NO_TEST_EXISTS))
                .singleElement().asString().contains("GrpcClientConfig in package <service>.config");
    }

    @Test
    void otherClassUsingProtobufOrGrpcIsReported() {
        String service = """
                package com.fooddelivery.order.service;

                import com.fooddelivery.grpc.restaurant.v1.RestaurantResponse;

                public class OrderService {
                }
                """;
        String helper = """
                package com.fooddelivery.order.service;

                import io.grpc.StatusRuntimeException;

                public class Retry {
                }
                """;

        assertThat(GrpcConventions.checkJava("com/fooddelivery/order/service/OrderService.java", service,
                ALL_TESTS_EXIST)).singleElement().asString().contains("only XxxGrpcService, XxxGrpcClient");
        assertThat(GrpcConventions.checkJava("com/fooddelivery/order/service/Retry.java", helper, ALL_TESTS_EXIST))
                .hasSize(1);
    }

    @Test
    void classUsingOnlyTheSharedSupportIsNotAGrpcClass() {
        String content = """
                package com.fooddelivery.order.service;

                import com.fooddelivery.grpc.GrpcMetadataKeys;

                public class OrderService {
                }
                """;

        assertThat(GrpcConventions.checkJava("com/fooddelivery/order/service/OrderService.java", content,
                ALL_TESTS_EXIST)).isEmpty();
    }

    @Test
    void grpcPortIsHttpPortPlusOneThousand() throws IOException {
        assertThat(GrpcConventions.checkServerPort("fd-restaurant-service",
                "server.port=8082\nspring.grpc.server.port=9082\n")).isEmpty();
        assertThat(GrpcConventions.checkServerPort("fd-restaurant-service",
                "server.port=8082\nspring.grpc.server.port=9090\n")).singleElement().asString()
                .contains("server.port + 1000");
        assertThat(GrpcConventions.checkServerPort("fd-restaurant-service", "server.port=8082\n")).hasSize(1);
    }

    @Test
    void everyProblemPointsToTheConventions() {
        List<String> problems = GrpcConventions.checkProto("restaurant.proto", "");

        assertThat(problems).allSatisfy(problem -> assertThat(problem).endsWith(GrpcConventions.DOC));
    }
}
