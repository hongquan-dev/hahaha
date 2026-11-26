# Quy ước gRPC: thêm một API gRPC giữa các service

Tài liệu này là **mẫu bắt buộc** cho mọi API gRPC của backend, dù là cái đầu tiên hay cái thứ mười. Làm đủ các bước ở
mục 1, chép code mẫu ở mục 2-4 rồi đổi tên. `GrpcConventionsTest` (common-lib) fail nếu `.proto` hay class gRPC sai
quy ước: khi đó **sửa code cho đúng quy ước, không sửa test**.

Ví dụ xuyên suốt: auth-service cung cấp `UserService/GetUser`, order-service gọi nó. Khi làm API khác, thay:

| Trong ví dụ | Ý nghĩa | Ví dụ khác |
|---|---|---|
| `auth` | Service **nhận** (sở hữu dữ liệu, chạy gRPC server) | `restaurant` |
| `order` | Service **gọi** (chạy gRPC client) | `order`, `delivery` |
| `UserService` / `GetUser` | Tên service gRPC / tên rpc | `RestaurantService` / `GetRestaurant` |
| `User` | Tên dùng cho class: `UserGrpcService`, `AuthGrpcClient` (client đặt theo service nhận) | `RestaurantGrpcService`, `RestaurantGrpcClient` |
| `AUTH` | Tên service nhận trong mã lỗi của client (`AUTH_UNAVAILABLE`) | `RESTAURANT` |

Những gì **đã có sẵn** trong `fd-grpc-api`, service **không viết lại**: ánh xạ `BusinessException` sang status gRPC
(`BusinessExceptionGrpcHandler`), log mỗi lời gọi (`GrpcLoggingInterceptor`), header `x-caller-service`, ánh xạ lỗi
phía client (`GrpcClientErrors`), deadline mặc định 2s cho mọi lời gọi (`DefaultDeadlineClientInterceptor`), request ID
đi theo lời gọi và có trên mọi dòng log (`RequestIdClientInterceptor`, `RequestIdServerInterceptor`), server chỉ
nghe `127.0.0.1` và tắt reflection (`GrpcServerDefaultsEnvironmentPostProcessor`).

---

## 1. Các bước

Làm theo thứ tự; mỗi bước tham chiếu mục có code mẫu.

1. **Điều kiện:** service nhận và service gọi dùng `common-lib` (cần `BusinessException`). Service chưa dùng (vd.
   restaurant, delivery, chat) phải chuyển sang `common-lib` trước, kèm database riêng (xem `ARCHITECTURE.md` mục 2).
2. **Hợp đồng** (mục 2): `fd-grpc-api/src/main/proto/<nhận>/v1/<tên>_service.proto`.
3. **Server** ở service nhận (mục 3): pom, `application.properties`, validator, service nghiệp vụ (nếu chưa có),
   `XxxGrpcService`, test.
4. **Client** ở service gọi (mục 4): pom, `application.properties`, `GrpcClientConfig`, record kết quả,
   `XxxGrpcClient`, test.
5. **Tài liệu** (mục 5): `ARCHITECTURE.md`, README của hai service, `README.md` gốc.
6. **Kiểm tra** (mục 6): `./mvnw clean install` pass (gồm `GrpcConventionsTest`, `ArchitectureDocTest`); chạy service
   nhận và gọi bằng grpcurl; chạy service gọi nếu đã có chỗ dùng client.

---

## 2. Hợp đồng `.proto`

`fd-grpc-api/src/main/proto/auth/v1/user_service.proto`:

```proto
// Users of auth-service for other services inside the network, e.g. order-service.
//
// Field numbers are the wire format: never change or reuse one; add new fields with new numbers instead, and mark
// removed ones reserved.
syntax = "proto3";

package fooddelivery.auth.v1;

option java_package = "com.fooddelivery.grpc.auth.v1";
option java_multiple_files = true;
option java_outer_classname = "UserServiceProto";

service UserService {
  // A user by ID, active or locked. Errors, with the error code in the "error-code" trailer:
  //   INVALID_ARGUMENT (INVALID_USER_ID): user_id is not the 14-digit ID of a user
  //   NOT_FOUND (USER_NOT_FOUND): no user has this ID
  rpc GetUser(GetUserRequest) returns (UserResponse);
}

message GetUserRequest {
  // 14-digit ID from IdUtils, e.g. 17939313751001
  int64 user_id = 1;
}

message UserResponse {
  int64 id = 1;
  // Lowercase, e.g. "quan.nguyen"
  string username = 2;
  // e.g. "CUSTOMER"
  string role = 3;
  // False if the account is locked
  bool active = 4;
}
```

Quy tắc:

- Đường dẫn `<service nhận>/v<n>/<tên>_service.proto`; `package fooddelivery.<service>.v<n>`;
  `java_package = "com.fooddelivery.grpc.<service>.v<n>"`; `java_multiple_files = true`;
  `java_outer_classname = "<Tên>ServiceProto"` (khác tên mọi message). *(kiểm bởi test)*
- Mỗi `rpc` có comment ngay trên: nó trả gì, và **mọi lỗi** dạng `STATUS (ERROR_CODE): khi nào`. *(kiểm bởi test)*
- Field có comment khi ý nghĩa không hiển nhiên (format, ví dụ, ý nghĩa khi không đặt), theo quy ước comment của
  `CLAUDE.md`.
- **Chỉ có field bên gọi cần.** Không trả email, SĐT, hash... "phòng khi cần": cần thì thêm field mới sau.
- ID là `int64`. Field có thể vắng thì `optional` (Java có `hasXxx()`); proto3 không có `null`.
- Đã commit thì không đổi số / kiểu field; bỏ field thì `reserved 3; reserved "phone_number";`. Thay đổi phá hợp
  đồng thì tạo `v2`, không sửa `v1`.
- Tên: service `XxxService`, rpc động từ + danh từ (`GetUser`, `ListUsers`), message `<Rpc>Request` và
  `XxxResponse`.

---

## 3. Server (service nhận, vd. fd-auth-service)

### 3.1 `pom.xml`

```xml
        <!-- gRPC server (UserGrpcService) on spring.grpc.server.port; version and Logback exclusion from the parent -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-grpc-server</artifactId>
        </dependency>

        <!-- Generated classes of the .proto contracts, e.g. UserServiceGrpc -->
        <dependency>
            <groupId>com.fooddelivery</groupId>
            <artifactId>fd-grpc-api</artifactId>
        </dependency>

        <!-- Standard health service grpc.health.v1.Health, for probes and grpc_health_probe -->
        <dependency>
            <groupId>io.grpc</groupId>
            <artifactId>grpc-services</artifactId>
        </dependency>

        <!-- InProcessGrpcServer for UserGrpcServiceTest: the interceptors of a running service, in their order -->
        <dependency>
            <groupId>com.fooddelivery</groupId>
            <artifactId>fd-grpc-api</artifactId>
            <type>test-jar</type>
            <scope>test</scope>
        </dependency>

        <!-- In-memory gRPC server and channel for UserGrpcServiceTest -->
        <dependency>
            <groupId>io.grpc</groupId>
            <artifactId>grpc-inprocess</artifactId>
            <scope>test</scope>
        </dependency>
```

Chỉ `groupId` + `artifactId` (+ `type`, `scope`): version và exclusion Logback lấy từ `pom.xml` gốc. Đã có thì không
thêm lần nữa.

### 3.2 `application.properties`

```properties
# gRPC server for services inside the network: HTTP port + 1000; address and reflection default from fd-grpc-api
spring.grpc.server.port=9081
```

- Port gRPC = port HTTP + 1000 (auth 8081 -> 9081, restaurant 8082 -> 9082, order 8083 -> 9083...). *(kiểm bởi
  test)*
- **Không** ghi `spring.grpc.server.address` hay `spring.grpc.server.reflection.enabled`: `fd-grpc-api` đã đặt
  `127.0.0.1` và `false`. Khi deploy, biến môi trường `SPRING_GRPC_SERVER_ADDRESS=0.0.0.0` ghi đè.

### 3.3 Validator: `validation/UserGrpcValidator`

gRPC không đi qua API flow (`api-routes.xml`, JSON Schema): protobuf đảm bảo cấu trúc, validator kiểm giá trị. Cùng
quy tắc với validator HTTP: **không truy vấn DB**, lỗi là `BusinessException` 400 kèm field lỗi.

```java
package com.fooddelivery.auth.validation;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.web.exception.BusinessException;

/**
 * Checks of the requests of the gRPC {@code UserService}, called by {@code UserGrpcService} at request time before
 * the business service.
 *
 * <p>Only checks the values themselves and never queries the database; whether the user exists is checked by
 * {@code UserQueryService}. gRPC has no API flow, so these are plain static methods rather than {@code <validator>}
 * entries of {@code api-routes.xml}.
 */
public final class UserGrpcValidator {

    private UserGrpcValidator() {
    }

    /**
     * Checks that {@code userId} is the ID of a user, e.g. the {@code user_id} of {@code GetUserRequest}.
     *
     * <p>Throws {@code BusinessException} 400 {@code INVALID_USER_ID}, with a field error on {@code userId}, if the ID
     * is not 14 digits or not of {@code EntityType.USER}, e.g. {@code 0} (field not set) or the ID of an order.
     *
     * @param userId the ID to check
     */
    public static void checkUserId(long userId) {
        if (!isUserId(userId)) {
            throw BusinessException.ofField(ApiStatus.BAD_REQUEST, "INVALID_USER_ID", "userId",
                    "userId must be the 14-digit ID of a user");
        }
    }

    // True if the ID decodes to EntityType.USER; any other ID, known or not, is not a user ID
    private static boolean isUserId(long id) {
        try {
            return IdUtils.decodeObjectType(id) == EntityType.USER;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
```

Field số không đặt có giá trị `0`, chuỗi không đặt là `""`: validator phải coi đó là thiếu.

### 3.4 Service nghiệp vụ

Logic và mọi kiểm tra cần DB nằm ở service nghiệp vụ như với HTTP (vd. `service/user/UserQueryService.getById`
ném 404 `USER_NOT_FOUND`), trả record của service (vd. `InternalUserDto`), **không** trả class protobuf. Nếu HTTP và
gRPC cùng cần một nghiệp vụ thì dùng chung service này.

### 3.5 `grpc/UserGrpcService`

```java
package com.fooddelivery.auth.grpc;

import com.fooddelivery.auth.dto.InternalUserDto;
import com.fooddelivery.auth.service.user.UserQueryService;
import com.fooddelivery.auth.validation.UserGrpcValidator;
import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC {@code fooddelivery.auth.v1.UserService} (contract in {@code auth/v1/user_service.proto} of fd-grpc-api), served
 * on {@code spring.grpc.server.port} for other services inside the network, e.g. order-service.
 *
 * <p>Plays the role of the controller for gRPC: checks the request with {@link UserGrpcValidator}, calls
 * {@link UserQueryService} and maps the result to the proto message. A {@code BusinessException} thrown here becomes a
 * gRPC status with an {@code error-code} trailer through {@code BusinessExceptionGrpcHandler} of fd-grpc-api.
 */
@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    private final UserQueryService userQueryService;

    public UserGrpcService(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    /**
     * {@code UserService/GetUser}: the user with this ID, active or locked.
     *
     * <p>Flow: {@link UserGrpcValidator#checkUserId} ({@code INVALID_ARGUMENT}) -> {@link UserQueryService#getById}
     * ({@code NOT_FOUND}) -> {@link UserResponse}.
     */
    @Override
    public void getUser(GetUserRequest request, StreamObserver<UserResponse> responseObserver) {
        UserGrpcValidator.checkUserId(request.getUserId());

        InternalUserDto user = userQueryService.getById(request.getUserId());
        responseObserver.onNext(toProto(user));
        responseObserver.onCompleted();
    }

    // Proto message of a user; protobuf has no null, so a null optional field is left unset
    private static UserResponse toProto(InternalUserDto user) {
        return UserResponse.newBuilder()
                .setId(user.id())
                .setUsername(user.username())
                .setRole(user.role())
                .setActive(user.active())
                .build();
    }
}
```

Quy tắc:

- Tên `XxxGrpcService`, package `<service>.grpc`, `@GrpcService`, kế thừa `XxxServiceImplBase`. *(kiểm bởi test)*
- Mỗi method: validator -> service nghiệp vụ -> `onNext(...)` một lần -> `onCompleted()`.
- Lỗi: **ném `BusinessException`**, không tự gọi `onError`, không tự tạo `Status`. Không bắt exception để log: lời
  gọi đã được `GrpcLoggingInterceptor` log.
- Không `null` trong proto: field `optional` vắng thì không gọi `setXxx`.
- Chỉ `XxxGrpcService` (và client, config ở mục 4) được import `io.grpc.*` hay class sinh từ `.proto`. *(kiểm bởi
  test)*
- RPC nặng (nhiều query, vòng lặp) kiểm `Context.current().isCancelled()` giữa các bước để dừng khi bên gọi đã bỏ
  cuộc.

### 3.6 Test: `grpc/UserGrpcServiceTest`

Gọi qua **stub thật** trên `InProcessGrpcServer` (test jar của fd-grpc-api): server in-process có đúng các interceptor
của service đang chạy (request ID, log, xử lý lỗi), nên test thấy đúng status và `error-code` mà client thật nhận.
Repository mock. Mỗi lỗi trong comment của `rpc` có một test (status + `error-code`).

```java
package com.fooddelivery.auth.grpc;

import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.auth.service.user.UserQueryService;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import com.fooddelivery.grpc.testing.InProcessGrpcServer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link UserGrpcService} through a real client stub, on {@link InProcessGrpcServer} (no port, the
 * interceptors of a running service). The repository is mocked.
 */
class UserGrpcServiceTest {

    private static final long USER_ID = 17939477151001L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private InProcessGrpcServer server;
    private UserServiceGrpc.UserServiceBlockingStub stub;

    @BeforeEach
    void start() {
        server = InProcessGrpcServer.start(new UserGrpcService(new UserQueryService(userRepository)));
        stub = UserServiceGrpc.newBlockingStub(server.channel());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void returnsTheUserWithThisId() {
        User user = new User(USER_ID, "quan", "$2a$10$hash", "quan@test.com", null, "CUSTOMER");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        UserResponse response = stub.getUser(GetUserRequest.newBuilder().setUserId(USER_ID).build());

        assertThat(response.getId()).isEqualTo(USER_ID);
        assertThat(response.getUsername()).isEqualTo("quan");
        assertThat(response.getActive()).isTrue();
    }

    @Test
    void unknownUserGivesNotFoundWithErrorCode() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        StatusRuntimeException e = call(USER_ID);

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(e.getTrailers().get(GrpcMetadataKeys.ERROR_CODE)).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void idThatIsNotAUserIdGivesInvalidArgumentWithoutQueryingTheDatabase() {
        StatusRuntimeException e = call(0);

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(e.getTrailers().get(GrpcMetadataKeys.ERROR_CODE)).isEqualTo("INVALID_USER_ID");
        verifyNoInteractions(userRepository);
    }

    private StatusRuntimeException call(long userId) {
        return catchThrowableOfType(StatusRuntimeException.class,
                () -> stub.getUser(GetUserRequest.newBuilder().setUserId(userId).build()));
    }
}
```

Validator có test riêng (`UserGrpcValidatorTest`, kỹ như mọi validator: hợp lệ, từng lỗi, biên). Test của server
không lặp lại từng trường hợp của validator, chỉ một trường hợp để chắc nó được gọi.

---

## 4. Client (service gọi, vd. fd-order-service)

### 4.1 `pom.xml`

```xml
        <!-- Generated classes of the .proto contracts, e.g. UserServiceGrpc.UserServiceBlockingStub -->
        <dependency>
            <groupId>com.fooddelivery</groupId>
            <artifactId>fd-grpc-api</artifactId>
        </dependency>

        <!-- gRPC client: channels and @ImportGrpcClients stubs; version and Logback exclusion from the parent -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-grpc-client</artifactId>
        </dependency>

        <!-- InProcessGrpcServer for AuthGrpcClientTest: a fake auth-service -->
        <dependency>
            <groupId>com.fooddelivery</groupId>
            <artifactId>fd-grpc-api</artifactId>
            <type>test-jar</type>
            <scope>test</scope>
        </dependency>

        <!-- In-memory gRPC server and channel for AuthGrpcClientTest -->
        <dependency>
            <groupId>io.grpc</groupId>
            <artifactId>grpc-inprocess</artifactId>
            <scope>test</scope>
        </dependency>
```

### 4.2 `application.properties`

```properties
# gRPC channel "auth" of GrpcClientConfig: gRPC port of auth-service, plaintext inside the network
spring.grpc.client.channel.auth.target=static://localhost:9081
```

- Tên channel là tên service nhận viết thường (`auth`, `restaurant`). Port = port gRPC của service nhận.
- Deadline: mặc định 2s cho mọi lời gọi (`fooddelivery.grpc.client.default-deadline`, đổi được). Một lời gọi cần
  khác thì client đặt `stub.withDeadlineAfter(...)`. **Không** gọi mà không có deadline.

### 4.3 `config/GrpcClientConfig`

Một class cho mọi stub của service gọi; mỗi service nhận một dòng `@ImportGrpcClients` (annotation lặp được).

```java
package com.fooddelivery.order.config;

import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

/**
 * gRPC stubs of the services order-service calls, created at startup as beans by Spring gRPC.
 *
 * <p>Each stub uses the channel named by {@code target}, whose address is
 * {@code spring.grpc.client.channel.<target>.target}. A channel connects at the first call, so order-service starts
 * even when the called service is down.
 */
@Configuration
@ImportGrpcClients(target = "auth", types = UserServiceGrpc.UserServiceBlockingStub.class)
public class GrpcClientConfig {
}
```

*(kiểm bởi test: `@ImportGrpcClients` chỉ ở `GrpcClientConfig` trong package `<service>.config`)*

### 4.4 Record kết quả: `client/AuthUser`

Phần còn lại của service gọi chỉ thấy record này, không thấy class protobuf.

```java
package com.fooddelivery.order.client;

/**
 * User of auth-service as order-service sees it, returned by {@link AuthGrpcClient}.
 *
 * @param id       14-digit ID, e.g. 17939313751001
 * @param username lowercase, e.g. "quan.nguyen"
 * @param role     e.g. "CUSTOMER"
 * @param active   false if the account is locked
 */
public record AuthUser(long id, String username, String role, boolean active) {
}
```

### 4.5 `client/AuthGrpcClient`

```java
package com.fooddelivery.order.client;

import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import com.fooddelivery.grpc.support.GrpcClientErrors;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

/**
 * Calls the gRPC {@code fooddelivery.auth.v1.UserService} of auth-service through the stub of
 * {@code GrpcClientConfig}, so the rest of order-service never sees protobuf classes or gRPC statuses.
 *
 * <p>Every call has the default deadline of fd-grpc-api. Failures become a {@code BusinessException} through
 * {@code GrpcClientErrors}: the error codes of auth-service as they are (e.g. 404 {@code USER_NOT_FOUND}), 503
 * {@code AUTH_UNAVAILABLE} if it cannot be reached in time, 502 {@code AUTH_CALL_FAILED} otherwise.
 */
@Component
public class AuthGrpcClient {

    private final UserServiceGrpc.UserServiceBlockingStub stub;

    public AuthGrpcClient(UserServiceGrpc.UserServiceBlockingStub stub) {
        this.stub = stub;
    }

    /**
     * Fetches a user from auth-service over gRPC.
     *
     * <p>Throws {@code BusinessException} 404 {@code USER_NOT_FOUND} or 400 {@code INVALID_USER_ID} as answered by
     * auth-service, 503 {@code AUTH_UNAVAILABLE} if it cannot be reached in time, 502 {@code AUTH_CALL_FAILED}
     * otherwise.
     *
     * @param userId 14-digit ID of a user
     * @return the user, active or locked
     */
    public AuthUser getUser(long userId) {
        try {
            UserResponse response = stub.getUser(GetUserRequest.newBuilder().setUserId(userId).build());
            return new AuthUser(response.getId(), response.getUsername(), response.getRole(), response.getActive());
        } catch (StatusRuntimeException e) {
            throw GrpcClientErrors.toBusinessException(e, "AUTH");
        }
    }
}
```

Quy tắc:

- Tên `XxxGrpcClient` theo service nhận (`AuthGrpcClient`, `RestaurantGrpcClient`), package `<service>.client`,
  `@Component`, nhận stub qua constructor. *(kiểm bởi test)*
- Bắt `StatusRuntimeException` và ném `GrpcClientErrors.toBusinessException(e, "<NHẬN>")`; không tự viết switch
  status. *(kiểm bởi test)*
- Trả record của service gọi; field `optional` vắng -> `null` trong record (`response.hasXxx() ? ... : null`).
- Không retry, không bắt lỗi để trả giá trị mặc định: lỗi đi lên như `BusinessException` để client cuối thấy.

### 4.6 Test: `client/AuthGrpcClientTest`

Service nhận **giả** (không phải service thật) trên `InProcessGrpcServer`, mỗi test đặt cách nó trả lời;
`stopServer()` giả lập service nhận đang tắt.

```java
package com.fooddelivery.order.client;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.grpc.GrpcMetadataKeys;
import com.fooddelivery.grpc.auth.v1.GetUserRequest;
import com.fooddelivery.grpc.auth.v1.UserResponse;
import com.fooddelivery.grpc.auth.v1.UserServiceGrpc;
import com.fooddelivery.grpc.testing.InProcessGrpcServer;
import com.fooddelivery.web.exception.BusinessException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tests of {@link AuthGrpcClient} against a fake {@code UserService} on {@link InProcessGrpcServer}. Each test sets
 * how the fake answers: a user, or a status with an {@code error-code} trailer.
 */
class AuthGrpcClientTest {

    private static final long USER_ID = 17939477151001L;

    // How the fake service answers the current test
    private BiConsumer<GetUserRequest, StreamObserver<UserResponse>> answer;
    private InProcessGrpcServer server;
    private AuthGrpcClient client;

    @BeforeEach
    void start() {
        server = InProcessGrpcServer.start(new UserServiceGrpc.UserServiceImplBase() {
            @Override
            public void getUser(GetUserRequest request, StreamObserver<UserResponse> responseObserver) {
                answer.accept(request, responseObserver);
            }
        });
        client = new AuthGrpcClient(UserServiceGrpc.newBlockingStub(server.channel()));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void returnsTheUserAsARecord() {
        answer = (request, observer) -> {
            observer.onNext(UserResponse.newBuilder().setId(request.getUserId()).setUsername("quan")
                    .setRole("CUSTOMER").setActive(true).build());
            observer.onCompleted();
        };

        assertThat(client.getUser(USER_ID)).isEqualTo(new AuthUser(USER_ID, "quan", "CUSTOMER", true));
    }

    @Test
    void errorOfAuthKeepsItsCode() {
        answer = (request, observer) -> {
            Metadata trailers = new Metadata();
            trailers.put(GrpcMetadataKeys.ERROR_CODE, "USER_NOT_FOUND");
            observer.onError(Status.NOT_FOUND.withDescription("User not found").asRuntimeException(trailers));
        };

        BusinessException e = catchThrowableOfType(BusinessException.class, () -> client.getUser(USER_ID));

        assertThat(e.getStatus()).isEqualTo(ApiStatus.NOT_FOUND);
        assertThat(e.getCode()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void authDownGivesUnavailable() {
        server.stopServer();

        BusinessException e = catchThrowableOfType(BusinessException.class, () -> client.getUser(USER_ID));

        assertThat(e.getStatus()).isEqualTo(ApiStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    }
}
```

Mỗi lỗi trong comment của `rpc` mà service gọi dùng có một test giữ mã lỗi. Không test lại toàn bộ bảng ánh xạ của
`GrpcClientErrors` (đã có `GrpcClientErrorsTest`).

---

## 5. Tài liệu

Cùng commit với code (quy tắc của `CLAUDE.md`):

- `ARCHITECTURE.md`:
  - mục 2: port gRPC của service nhận trong bảng module, mũi tên `fd-grpc-api` sang service nhận (server) và gọi
    (client);
  - mục 3: một dòng cho mỗi class mới (`UserGrpcService`, `UserGrpcValidator`, `GrpcClientConfig`, `AuthGrpcClient`,
    `AuthUser`, service và DTO mới) kèm test; dòng `.proto` trong bảng của fd-grpc-api;
  - mục 4 "Gọi giữa service": luồng thật thay cho câu "Chưa có lời gọi nào";
  - mục 6: property mới (`spring.grpc.client.channel.<tên>.target`).
- README của **service nhận**: mục "gRPC `XxxService`" gồm bảng lỗi (status gRPC, `error-code`, khi nào) và lệnh thử:

  ```bash
  grpcurl -plaintext -import-path fd-grpc-api/src/main/proto -proto auth/v1/user_service.proto \
    -d '{"user_id": 17939522301001}' localhost:9081 fooddelivery.auth.v1.UserService/GetUser
  grpc_health_probe -addr=localhost:9081
  ```

- README của **service gọi**: mục cấu hình (channel), cấu trúc source (`client/`, `config/GrpcClientConfig`).
- `README.md` gốc: dòng port gRPC trong bảng "Service Ports" (health check `grpc_health_probe -addr=localhost:<port>`),
  lệnh chạy ghi `HTTP <port> + gRPC <port>`.
- Không có file cURL: thư mục `postman/` chỉ dành cho API HTTP.

---

## 6. Kiểm tra trước khi báo xong

1. `./mvnw clean install` pass. Đọc kỹ nếu fail:
   - `GrpcConventionsTest`: sửa code theo thông báo (mỗi thông báo nói rõ quy tắc nào);
   - `ArchitectureDocTest`: thêm class còn thiếu vào `ARCHITECTURE.md`.
2. Chạy service nhận: log có `Registered gRPC service: fooddelivery.<service>.v1.XxxService` và
   `gRPC Server started, listening on address: [/127.0.0.1:<port>]`.
3. Gọi bằng grpcurl (lệnh ở mục 5) cho trường hợp thành công và từng lỗi trong comment của `rpc`; lỗi phải có trailer
   `error-code` (thêm `-v` để thấy). grpcurl và grpc_health_probe chỉ cần tải về, không bắt buộc cài.
4. Service gọi: khởi động được khi service nhận đang tắt (channel kết nối lười).
5. Báo lại số test đã chạy theo từng module.

---

## 7. Không làm

| Không | Vì sao |
|---|---|
| API HTTP nội bộ (`/internal/**`) thay cho gRPC | Service gọi nhau chỉ qua gRPC (`ARCHITECTURE.md` mục 2) |
| Bật reflection, đặt `spring.grpc.server.address=0.0.0.0` trong `application.properties` | Lộ hợp đồng và cổng ra LAN; deploy ghi đè bằng biến môi trường |
| Trả class protobuf (`UserResponse`) ra khỏi `XxxGrpcClient` | Đổi hợp đồng thì phải sửa cả service gọi; dùng record |
| Tự viết switch `Status.Code` -> `BusinessException` ở client | Dùng `GrpcClientErrors` để mọi client giống nhau |
| `responseObserver.onError(...)` trong `XxxGrpcService` | Ném `BusinessException`; handler chung ánh xạ và thêm `error-code` |
| Field "phòng khi cần" trong message (email, SĐT...) | Lộ dữ liệu không cần; thêm field mới khi có người dùng |
| Đổi số / kiểu field đã commit, dùng lại số đã bỏ | Service chạy bản cũ đọc sai dữ liệu; dùng `reserved` hoặc `v2` |
| Đặt `.proto` trong service | Hợp đồng chỉ nằm trong `fd-grpc-api`, hai bên cùng phụ thuộc |
