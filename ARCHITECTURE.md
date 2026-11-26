# Kiến trúc food-delivery-backend

Bản đồ cấu trúc của backend: **được xây thế nào, sửa ở đâu**. Cách chạy và thử (port, Postman) nằm trong
[README.md](README.md) và README của từng service; quy tắc làm việc trong [CLAUDE.md](CLAUDE.md). File này không chép
lại.

Đọc file này trước khi thêm, xoá, đổi tên, di chuyển class/package/module, hay đổi một hợp đồng ở mục 7. Mọi thay đổi
như vậy phải cập nhật file này trong cùng commit. `ArchitectureDocTest` (common-lib) fail nếu một class hoặc module
trong `src/main` không được nhắc tới ở đây.

---

## 1. Tổng quan

Backend microservice của ứng dụng giao đồ ăn: Maven multi-module, Java 21, Spring Boot 4.1.2, Spring Cloud 2025.1
(Gateway WebFlux), Spring gRPC, PostgreSQL + Flyway, Log4j2. Build bằng Maven Wrapper `./mvnw` (Maven 3.9.16).

Thư viện dùng chung nằm ở repo riêng `food-delivery-libs` (format `ApiResponse`, luồng `api-routes.xml` -> role
`<roles>` -> JSON Schema -> validator, enum `UserRole`, ID 14 chữ số; xem `ARCHITECTURE.md` của repo đó), cài vào
`~/.m2` trước khi build backend.

Trạng thái hiện tại: gateway và auth-service đã có chức năng (đăng ký, đăng nhập, phiên, kiểm tra token ở gateway).
Restaurant-service có API đầu tiên (chủ quán tạo nhà hàng, giới hạn theo role). Order, delivery, chat mới chỉ có
health check. Chưa có API gRPC nào: `fd-grpc-api` mới có phần hỗ trợ
dùng chung, sẵn cho API gRPC đầu tiên.

---

## 2. Module và phụ thuộc

```
food-delivery-libs (~/.m2)
        │
        ▼
   common-lib ─────────────┬──────────────► fd-auth-service
   (bundle dependency)     ├──────────────► fd-order-service
                           └──────────────► fd-restaurant-service

   common-dto-lib ─────────────────────────► fd-gateway-service (WebFlux, không dùng common-lib)

   spring-boot-starter-webmvc ─────────────► fd-delivery-service, fd-chat-service

   fd-grpc-api ── (chưa service nào dùng) ──► service có gRPC server / client sau này
```

| Module | Port | Vai trò | Database |
|---|---:|---|---|
| `common-lib` | - | Không có code Java: gom dependency chung cho service Spring MVC (4 lib của `food-delivery-libs`, JPA, PostgreSQL, Flyway, Log4j2) và `log4j2-spring.xml` chung | - |
| `fd-grpc-api` | - | Thư viện (jar), không chạy như service. Hợp đồng gRPC giữa service: file `.proto` trong `src/main/proto` (sinh code bằng `protobuf-maven-plugin`; hiện chưa có), `GrpcMetadataKeys`; và phần hỗ trợ gRPC dùng chung, tự đăng ký vào mọi service có gRPC server/client (`GrpcSupportAutoConfiguration`) | - |
| `fd-gateway-service` | 8090 | Cổng duy nhất cho client: route `/auth/**`, `/order/**`, `/restaurant/**`; kiểm tra access token (`JwtAuth`) trước order và restaurant | - |
| `fd-auth-service` | 8081 | Tài khoản, đăng nhập, phiên (refresh token), ký access token RS256, JWKS | `FD_auth_db` |
| `fd-order-service` | 8083 | Đơn hàng (chưa có nghiệp vụ, chỉ có health check) | `FD_order_db` (chưa có bảng) |
| `fd-restaurant-service` | 8082 | Nhà hàng (chủ quán tạo quán của mình), sau này món ăn, menu | `FD_restaurant_db` |
| `fd-delivery-service` | 8084 | Giao hàng (chưa có nghiệp vụ) | - |
| `fd-chat-service` | 8085 | Chat (chưa có nghiệp vụ) | - |

Quy tắc phụ thuộc (không được phá):

- Service **không** phụ thuộc service khác; gọi nhau **chỉ qua gRPC**, hợp đồng gRPC chỉ nằm trong `fd-grpc-api`.
  Không viết API HTTP nội bộ (`/internal/**`) cho service khác gọi: HTTP chỉ dành cho client đi qua gateway và các
  chuẩn bắt buộc là HTTP (JWKS cho gateway, callback/webhook của bên thứ ba). Một đường gọi duy nhất nên xác thực,
  log, retry, ánh xạ lỗi chỉ làm một lần ở `fd-grpc-api`.
- Service Spring MVC kéo thư viện qua `common-lib`, không khai báo từng lib. Gateway là WebFlux nên chỉ dùng
  `common-dto-lib` (cùng format response), không dùng `common-lib`.
- `common-lib` chỉ chứa thứ **mọi** service nghiệp vụ cần; thư viện của riêng một service khai báo ở pom của service
  đó (vd. `spring-security-crypto`, `nimbus-jose-jwt` ở auth). `common-lib` có JPA: service dùng nó phải có database
  PostgreSQL riêng; service không có database thì không dùng `common-lib` (hoặc tách phần JPA ra module riêng).
- Mỗi service (không phải `common-lib`, `fd-grpc-api`) khai báo `spring-boot-maven-plugin` để `package` ra jar chạy
  được bằng `java -jar`.
- Comment trong mọi `pom.xml` chỉ một dòng.
- Mọi starter kéo Logback (vd. gRPC) phải exclude `spring-boot-starter-logging`: dự án dùng Log4j2. Hai starter
  gRPC đã có sẵn exclusion trong `dependencyManagement` của `pom.xml` gốc, service chỉ khai báo `groupId` +
  `artifactId` (tự ghi `<exclusions>` sẽ thay thế, không cộng thêm).
- Version khai báo ở `pom.xml` gốc (`dependencyManagement`, BOM Spring Boot / Spring Cloud / gRPC). Biến
  `spring-boot.version` phải bằng version của `spring-boot-starter-parent`.
- Starter gRPC **không** nằm trong `common-lib`: starter server mở cổng ở mọi service có nó, nên chỉ service có API
  gRPC mới khai báo.

---

## 3. Bản đồ class

Mỗi class một dòng. Cột Test là class test cùng package trong `src/test/java`; "(không cần)" là class không có logic theo quy tắc test
trong `CLAUDE.md`; "-" là còn thiếu test.

### fd-grpc-api (`com.fooddelivery.grpc`)

| Class / file | Vai trò | Test |
|---|---|---|
| `GrpcMetadataKeys` | Tên metadata dùng chung: trailer `error-code` (mã lỗi nghiệp vụ), header `x-caller-service`, `x-request-id` | (hằng số) |
| `GrpcSupportAutoConfiguration` | Auto-configuration: service có gRPC server được `BusinessExceptionGrpcHandler` + `GrpcLoggingInterceptor` + `RequestIdServerInterceptor`, service có gRPC client được `CallerServiceClientInterceptor` + `DefaultDeadlineClientInterceptor` + `RequestIdClientInterceptor`; nhường bean cùng loại của service | `GrpcSupportAutoConfigurationTest` |
| `GrpcServerDefaultsEnvironmentPostProcessor` | (server) Mặc định an toàn lúc startup: `spring.grpc.server.address=127.0.0.1`, `reflection.enabled=false`; mọi nguồn cấu hình khác thắng | `GrpcServerDefaultsEnvironmentPostProcessorTest` |
| `GrpcStatuses` | Bảng duy nhất HTTP status <-> gRPC code, đọc hai chiều bởi server và client | `GrpcStatusesTest` |
| `BusinessExceptionGrpcHandler` | (server) `BusinessException` -> status gRPC + trailer `error-code`; lỗi khác -> `INTERNAL` không lộ chi tiết | `BusinessExceptionGrpcHandlerTest` |
| `GrpcLoggingInterceptor` | (server) Log mỗi lời gọi gRPC: method, caller, request ID, status, thời gian | `GrpcLoggingInterceptorTest` |
| `RequestIdServerInterceptor` | (server) Request ID từ `x-request-id` (thiếu/sai thì tạo mới) vào `ThreadContext` trong từng callback, trả lại giá trị cũ của thread | `RequestIdServerInterceptorTest` |
| `GrpcClientErrors` | (client) `StatusRuntimeException` -> `BusinessException`: giữ mã lỗi của service nhận; không tới được -> 503 `<TARGET>_UNAVAILABLE`; còn lại -> 502 `<TARGET>_CALL_FAILED` | `GrpcClientErrorsTest` |
| `CallerServiceClientInterceptor` | (client) Gắn `x-caller-service` = `spring.application.name` vào mọi lời gọi gRPC | `CallerServiceClientInterceptorTest` |
| `RequestIdClientInterceptor` | (client) Gửi request ID của `ThreadContext` trong `x-request-id` | `RequestIdClientInterceptorTest` |
| `DefaultDeadlineClientInterceptor` | (client) Lời gọi chưa có deadline thì gắn `fooddelivery.grpc.client.default-deadline` (mặc định 2s) | `DefaultDeadlineClientInterceptorTest` |
| `GrpcClientProperties` | (client) Bind `fooddelivery.grpc.client.*`, kiểm tra lúc startup | `GrpcClientPropertiesTest` |

Phần hỗ trợ nằm ở package `com.fooddelivery.grpc.support`, đăng ký trong
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (bean) và
`META-INF/spring.factories` (`GrpcServerDefaultsEnvironmentPostProcessor`). Dependency Spring gRPC,
`common-web-lib`, Log4j2 là `optional`: service dùng gRPC đã có chúng qua starter gRPC và `common-lib`.

Test của phần hỗ trợ gọi gRPC thật (in-process) qua `src/test/proto/test/v1/echo_service.proto`
(`fooddelivery.test.v1.EchoService`): proto chỉ dùng cho test, không nằm trong jar, nên test không phụ thuộc hợp
đồng của service nào. `src/main/proto` rỗng là hợp lệ (`failOnMissingSources=false`).

### fd-gateway-service (`com.fooddelivery.gateway`)

| Class | Package | Vai trò | Test |
|---|---|---|---|
| `GatewayApplication` | (gốc) | Entry point | (không cần) |
| `GatewayController` | `controller` | `GET /gateway/health` | (không cần) |
| `ClockConfig` | `config` | Bean `Clock`, để test dùng thời gian cố định | (không cần) |
| `JwtAuthGatewayFilterFactory` | `filter` | Filter `JwtAuth`: đọc cookie `access_token`, xoá `X-User-Id`/`X-User-Role` client gửi, gắn lại từ token; sai -> 401/503 | `JwtAuthGatewayFilterFactoryTest` |
| `RequestIdGlobalFilter` | `filter` | Global filter chạy đầu tiên: `X-Request-Id` (của client nếu hợp lệ, không thì tạo mới) gửi xuống service và trả về client | `RequestIdGlobalFilterTest` |
| `RateLimitGatewayFilterFactory` | `filter` | Filter `RateLimit`: giới hạn request theo IP client + path; quá -> 429 `TOO_MANY_REQUESTS` + `Retry-After` | `RateLimitGatewayFilterFactoryTest` |
| `ErrorResponses` | `filter` | Ghi lỗi dạng `ApiResponse` cho các filter của gateway | (kiểm qua test của hai filter) |
| `FixedWindowRateLimiter` | `ratelimit` | Đếm request theo key trong cửa sổ cố định, lưu RAM, tự dọn key hết hạn | `FixedWindowRateLimiterTest` |
| `RateLimitDecision` | `ratelimit` | Cho qua / từ chối kèm thời gian chờ | (record) |
| `RateLimitProperties` | `ratelimit` | Bind `gateway.rate-limit.*`, kiểm tra giá trị lúc startup | `RateLimitPropertiesTest` |
| `AccessTokenVerifier` | `auth` | Kiểm tra JWT RS256 (format, `kid`, chữ ký, `iss`, `exp`...), cùng thứ tự với auth-service | `AccessTokenVerifierTest` |
| `TokenVerification` | `auth` | Kết quả kiểm tra: hợp lệ (userId, role) / hết hạn / sai / không kiểm tra được | (kiểm qua `AccessTokenVerifierTest`) |
| `PublicKeySource` | `auth` | Interface lấy public key theo `kid` | (interface) |
| `JwksKeyProvider` | `auth` | Tải JWKS của auth bằng `WebClient`, cache, tải lại có giới hạn tần suất | `JwksKeyProviderTest` |
| `JwksUnavailableException` | `auth` | Chưa từng lấy được key -> 503 `AUTH_UNAVAILABLE` | (kiểm qua `JwksKeyProviderTest`) |
| `GatewayAuthProperties` | `auth` | Bind `gateway.auth.*`, kiểm tra giá trị lúc startup | `GatewayAuthPropertiesTest` |

### fd-auth-service (`com.fooddelivery.auth`)

| Class | Package | Vai trò | Test |
|---|---|---|---|
| `AuthApplication` | (gốc) | Entry point | (không cần) |
| `AuthController` | `controller` | `/auth/register`, `/login`, `/me`, `/refresh`, `/logout`; đặt/xoá cookie | `AuthControllerTest` |
| `JwksController` | `controller` | `GET /auth/.well-known/jwks.json` (path exclude khỏi API flow) | `JwksControllerTest` |
| `AuthValidator` | `validation` | Validator của register, login: bắt buộc, độ dài, định dạng | `AuthValidatorTest` |
| `AuthService` | `service.auth` | Đăng ký (trùng username/email/SĐT), đăng nhập, user hiện tại | `AuthServiceLoginTest` (login, me), `AuthServiceRegisterTest` |
| `LoginResult` | `service.auth` | User + token của một lần đăng nhập | (record) |
| `LoginThrottleService` | `service.auth` | Khoá tạm theo tài khoản / identifier sau nhiều lần đăng nhập sai -> 429 `TOO_MANY_LOGIN_ATTEMPTS` | `LoginThrottleServiceTest` |
| `DataCleanupService` | `service.cleanup` | Job định kỳ: xoá phiên đã chết quá `retention` và dòng `login_failures` hết tác dụng, theo lô | `DataCleanupServiceTest` |
| `SessionService` | `service.session` | Phiên = chuỗi refresh token: tạo, xoay vòng, phát hiện dùng lại, đăng xuất | `SessionServiceTest` |
| `IssuedTokens` | `service.session` | Access + refresh token để controller đặt cookie | (record) |
| `TokenService` | `service.token` | Ký / kiểm tra access token RS256, sinh và hash refresh token; không DB | `TokenServiceTest` |
| `AccessTokenVerification` | `service.token` | Kết quả kiểm tra access token | (kiểm qua `TokenServiceTest`) |
| `AuthCookies` | `web` | Dựng `Set-Cookie` cho `access_token`, `refresh_token` | `AuthCookiesTest` |
| `OriginCheckFilter` | `web` | Chống CSRF: request thay đổi dữ liệu từ origin lạ -> 403 `ORIGIN_NOT_ALLOWED` | `OriginCheckFilterTest` |
| `WebFilterConfig` | `config.web` | Đăng ký filter với thứ tự rõ ràng | (không cần) |
| `JwtKeyConfig` | `config.security` | Dựng cặp khoá RSA từ `auth.jwt.*`, kiểm tra lúc startup | `JwtKeyConfigTest` |
| `PasswordConfig` | `config.security` | Bean BCrypt `PasswordEncoder` | (không cần) |
| `AuthProperties` | `config.properties` | Bind `auth.*`, kiểm tra giá trị lúc startup | `AuthPropertiesTest` |
| `LoginThrottleProperties` | `config.properties` | Bind `auth.login.*` (số lần sai, cửa sổ, thời gian khoá) | `LoginThrottlePropertiesTest` |
| `CleanupProperties` | `config.properties` | Bind `auth.cleanup.*` (thời gian giữ lại, kích thước lô) | `CleanupPropertiesTest` |
| `ClockConfig` | `config.common` | Bean `Clock` | (không cần) |
| `SchedulingConfig` | `config.common` | Bật scheduler cho `@Scheduled` | (không cần) |
| `User` | `common.model` | Entity bảng `users`; `role` là `UserRole` (common-dto-lib) | (entity) |
| `RefreshToken` | `common.model` | Entity bảng `refresh_tokens` (chỉ lưu hash) | (entity) |
| `LoginFailure` | `common.model` | Entity bảng `login_failures` (khoá là SHA-256, không lưu identifier) | (entity) |
| `IdentifierNormalizer` | `common.util` | Chuẩn hoá username/email/SĐT về dạng lưu DB | `IdentifierNormalizerTest` |
| `LogMasker` | `common.util` | Che bớt email/SĐT trước khi ghi log | `LogMaskerTest` |
| `UserRepository` | `repository` | JPA `users` | (Spring Data) |
| `RefreshTokenRepository` | `repository` | JPA `refresh_tokens`, tra theo hash | (Spring Data) |
| `LoginFailureRepository` | `repository` | JPA `login_failures`: tạo dòng nếu chưa có, khoá dòng khi cập nhật | (Spring Data) |
| `UserDto` | `dto` | User trả về client (không có hash mật khẩu) | (record) |
| `LoginRequest` | `dto` | Body `POST /auth/login` | (record) |
| `RegisterRequest` | `dto` | Body `POST /auth/register`; `normalized()` dùng `IdentifierNormalizer` | `RegisterRequestTest` |

Resource: `api-routes.xml` (khai báo 8 API), `schemas/login.json`, `schemas/register.json`,
`db/migration/V1__create_users_and_refresh_tokens.sql`, `V2__add_unique_phone_number_to_users.sql`,
`V3__create_login_failures.sql`, `V4__constrain_user_role.sql`, `application.properties` (mô tả từng property
`auth.*`).

### fd-order-service (`com.fooddelivery.order`)

| Class | Package | Vai trò | Test |
|---|---|---|---|
| `OrderApplication` | (gốc) | Entry point | (không cần) |
| `OrderController` | `controller` | `GET /order/health` | (không cần) |

Resource: `api-routes.xml` (1 API), `application.properties`.

### fd-restaurant-service (`com.fooddelivery.restaurant`)

| Class | Package | Vai trò | Test |
|---|---|---|---|
| `RestaurantApplication` | (gốc) | Entry point | (không cần) |
| `RestaurantController` | `controller` | `GET /restaurant/health`, `POST /restaurant/restaurants`, `GET /restaurant/restaurants/me` (chủ quán là người gọi, từ `X-User-Id`) | `RestaurantControllerTest` |
| `RestaurantValidator` | `validation` | Validator của tạo quán: bắt buộc, độ dài, SĐT di động Việt Nam | `RestaurantValidatorTest` |
| `RestaurantService` | `service` | Tạo quán `PENDING`, mỗi chủ quán một quán -> 409 `RESTAURANT_ALREADY_EXISTS`; quán của người gọi, chưa có -> 404 `RESTAURANT_NOT_FOUND` | `RestaurantServiceTest` |
| `Restaurant` | `common.model` | Entity bảng `restaurants`, gồm cả cột duyệt (`review_note`, `submitted_at`, `reviewed_at`, `reviewed_by`) | (entity) |
| `RestaurantStatus` | `common.model` | `PENDING` (chờ duyệt), `ACTIVE`, `REJECTED` (bị từ chối, có lý do), `SUSPENDED` | (enum) |
| `RestaurantRepository` | `repository` | JPA `restaurants` | (Spring Data) |
| `CreateRestaurantRequest` | `dto` | Body `POST /restaurant/restaurants`; `normalized()` trim, SĐT về `+84` | `CreateRestaurantRequestTest` |
| `RestaurantDto` | `dto` | Nhà hàng trả về client | (record) |

Resource: `api-routes.xml` (3 API), `schemas/create-restaurant.json`, `db/migration/V1__create_restaurants.sql`,
`V2__add_restaurant_review.sql`,
`application.properties`.

### fd-delivery-service, fd-chat-service

Chỉ có entry point và health check, chưa dùng `common-lib` hay API flow:

| Module | Class |
|---|---|
| `fd-delivery-service` | `DeliveryApplication`, `DeliveryController` (`GET /delivery/health`) |
| `fd-chat-service` | `ChatApplication`, `ChatController` (`GET /chat/health`) |

---

## 4. Luồng chạy

### Request từ client

```
Client ──> gateway :8090
            ├─ POST /auth/login, /auth/register  RateLimit: quá N request/IP/path -> 429 ──> auth :8081
            ├─ /auth/**   ───────────────────────────────────────────────> auth :8081
            └─ /order/**, /restaurant/**
                          JwtAuth: cookie access_token -> AccessTokenVerifier (key từ JwksKeyProvider)
                          sai/thiếu -> 401 UNAUTHENTICATED, hết hạn -> 401 TOKEN_EXPIRED, chưa có key -> 503
                          hợp lệ -> xoá X-User-Id/X-User-Role của client, gắn từ token ──> order :8083 / restaurant :8082
```

Mọi request qua gateway có **request ID**: `RequestIdGlobalFilter` (chạy trước mọi filter) gắn `X-Request-Id` gửi
xuống service và trả về client; `RequestIdFilter` (common-web-lib) đặt nó vào `ThreadContext` của service, log
pattern in `[requestId]`; gọi gRPC thì `RequestIdClientInterceptor` -> `RequestIdServerInterceptor` mang nó sang
service nhận. Tìm một ID là thấy mọi dòng log của request ở mọi service. Hợp đồng (tên header, định dạng) là
`RequestIds` của common-dto-lib.

Trong mỗi service Spring MVC (auth, order, restaurant), request đi qua luồng của `common-api-flow-lib`:
`api-routes.xml` (404) -> `<roles>` của route so với `X-User-Role` (thiếu `X-User-Id` -> 401 `UNAUTHENTICATED`, sai
role -> 403 `ACCESS_DENIED`) -> JSON Schema (400) -> validator (400 + field lỗi) -> controller -> service (lỗi nghiệp
vụ, cần DB, quyền trên dữ liệu như "quán này có phải của người gọi") -> `ApiResponse`. Auth có thêm
`OriginCheckFilter` trước luồng này.

### Phân quyền

- **Gateway** chỉ xác thực (token hợp lệ chưa) và gắn `X-User-Id` / `X-User-Role`; không biết API nào cần role nào.
- **Role theo API** khai báo bằng `<roles>` trong `api-routes.xml` của service, tên trong `UserRole`
  (`CUSTOMER`, `RESTAURANT_OWNER`, `DRIVER`, `ADMIN`); route không có `<roles>` thì ai cũng gọi được.
- **Quyền trên dữ liệu** (sở hữu) kiểm tra trong service bằng `X-User-Id` + DB; ID chủ sở hữu luôn lấy từ header,
  không bao giờ từ body.
- Mỗi tài khoản một role. Đăng ký công khai luôn ra `CUSTOMER`; role khác hiện cấp tay bằng SQL (`postman/README.md`),
  sau này qua API admin. Đổi role thì người dùng phải đăng nhập lại để token có role mới.

### Đăng nhập và phiên (auth)

```
POST /auth/login   AuthValidator -> AuthService.login: LoginThrottleService (bị khoá -> 429) -> BCrypt (sai -> đếm, 401)
                   -> SessionService.start -> TokenService
                   -> AuthController đặt cookie access_token (JWT RS256, ngắn) + refresh_token (opaque, dài)
POST /auth/refresh SessionService: refresh token cũ hết hiệu lực, cấp cặp mới; token đã xoay bị dùng lại
                   -> 409 REFRESH_CONFLICT (tab khác vừa refresh) hoặc 401 REFRESH_TOKEN_REUSED (thu hồi cả phiên)
POST /auth/logout  SessionService.end, xoá cookie
```

Gateway và service khác kiểm tra access token **tự mình** bằng public key từ `GET /auth/.well-known/jwks.json`, không
gọi auth mỗi request.

### Gọi giữa service

Chưa có lời gọi nào. Khi có, luồng là (phần hỗ trợ của `fd-grpc-api` tự gắn vào hai đầu):

```
service gọi:   ... -> XxxGrpcClient (stub, deadline) ── gRPC, CallerServiceClientInterceptor ──>
service nhận:  GrpcLoggingInterceptor -> XxxGrpcService -> service nghiệp vụ
               BusinessException -> BusinessExceptionGrpcHandler -> status gRPC + trailer error-code
```

Lỗi của service nhận giữ nguyên mã: status + trailer `error-code` -> `BusinessException` cùng mã ở service gọi.

---

## 5. Database

- Mỗi service sở hữu database riêng (`FD_auth_db`, `FD_order_db`, `FD_restaurant_db`); service không đọc database
  của service khác. Database tạo tay một lần (`CREATE DATABASE`), bảng do Flyway tạo.
- Cấu trúc chỉ đổi qua migration Flyway trong `src/main/resources/db/migration` của service đó; Hibernate
  `ddl-auto=validate`. Quy tắc chi tiết trong `CLAUDE.md`.
- `FD_auth_db`: bảng `users`, `refresh_tokens` (V1; V2 thêm unique `phone_number`), `login_failures` (V3).
  `DataCleanupService` xoá định kỳ phiên đã chết quá 7 ngày và dòng `login_failures` hết tác dụng; phiên còn sống
  không bao giờ bị đụng tới (token đã bị thay của nó cần để phát hiện token bị đánh cắp). `users.role` bắt buộc,
  `CHECK` theo tên `UserRole` (V4).
- `FD_restaurant_db`: bảng `restaurants` (V1), `owner_user_id` unique (mỗi chủ quán một quán, không khoá ngoại vì
  user nằm ở database khác), `status` `CHECK` theo `RestaurantStatus`. V2 chuẩn bị cho việc admin duyệt quán: thêm
  `REJECTED` (bắt buộc có `review_note`), `submitted_at`, `reviewed_at` / `reviewed_by`, và bảng lịch sử
  `restaurant_reviews` (mỗi bước duyệt một dòng, không sửa, không xoá).
- **Đã có trong database, chưa có code dùng** (làm khi có API duyệt quán, xem `docs/restaurant-role-checklist.md`):
  cột duyệt chưa được API nào ghi; bảng `restaurant_reviews` chưa có entity, ID của nó cần một `EntityType` mới ở
  `common-id-lib`.

---

## 6. Điểm mở rộng và cấu hình

- `application.properties` của mỗi service mô tả từng property. File này không có trên git (`.gitignore`); git chỉ có
  `application.properties.example` cùng thư mục, giá trị bí mật để trống (`CHANGE_ME`, `PASTE_...`).
  `GrpcConventionsTest` đọc file mẫu. Nhóm chính: `auth.*` (`AuthProperties`: JWT, cookie,
  thời hạn token, origin cho phép), `auth.login.*` (`LoginThrottleProperties`), `auth.cleanup.*`
  (`CleanupProperties`, lịch chạy `auth.cleanup.cron`), `gateway.auth.*`
  (`GatewayAuthProperties`), `gateway.rate-limit.*` (`RateLimitProperties`), `api-flow.*` (thư viện).
- Route của gateway: `spring.cloud.gateway.server.webflux.routes[*]` trong `application.properties` của gateway; route
  cần đăng nhập thêm `filters[0]=JwtAuth`, route cần chống spam thêm `RateLimit`.
- Thêm API gRPC: làm đúng theo [`docs/grpc-conventions.md`](docs/grpc-conventions.md) (mẫu code từng file,
  thứ tự các bước). `GrpcConventionsTest` (common-lib) fail nếu proto hay class gRPC sai quy ước. Xử lý lỗi, log,
  header caller, deadline mặc định và cấu hình an toàn của server có sẵn nhờ `fd-grpc-api`, service không viết lại.

---

## 7. Hợp đồng không được phá

- **Header danh tính**: service phía sau gateway tin `X-User-Id` / `X-User-Role` do gateway gắn; gateway luôn xoá bản
  client gửi. Port nội bộ (8081-8085, và port gRPC khi có) không được mở ra ngoài: chúng chỉ nghe trên `127.0.0.1`
  (`server.address`, `spring.grpc.server.address`), chỉ gateway nghe trên mọi địa chỉ.
- **Role**: tên trong `UserRole` (common-dto-lib) nằm trong `users.role`, claim `role`, header `X-User-Role` và thẻ
  `<roles>`; không đổi tên hay xoá role đã có. Thêm role: hằng số mới + migration nới `ck_users_role`.
- **Access token**: RS256, `kid` khớp JWKS, claim `sub` (user ID), `role`, `iss`, `iat`, `exp`, `jti`. Đổi ở `TokenService` thì phải
  đổi `AccessTokenVerifier` của gateway.
- **Cookie**: tên `access_token`, `refresh_token` và thuộc tính (`HttpOnly`, `SameSite`, `Secure`, `Path`) trong
  `AuthCookies`; frontend dựa vào chúng.
- **gRPC**: `.proto` đã commit chỉ thêm field mới, không đổi số/kiểu field cũ, field bỏ đi thì `reserved`; package có
  version (`fooddelivery.<service>.v1`). Lỗi nghiệp vụ trả trailer `error-code` (`GrpcMetadataKeys`).
- **Mã lỗi** gửi client (`USER_ALREADY_EXISTS`, `TOKEN_EXPIRED`, `AUTH_UNAVAILABLE`...): client và service khác rẽ nhánh
  theo chúng.
- **Migration đã commit** không sửa, không xoá.

---

## 8. Việc thường gặp: sửa ở đâu

| Việc | Sửa ở | Lưu ý |
|---|---|---|
| Thêm API vào một service | `api-routes.xml`, `schemas/*.json`, `validation/*Validator`, controller, service | Theo "Luồng xử lý một API" trong `CLAUDE.md`; API cần đăng nhập thì thêm `<roles>`; thêm file cURL `postman/<module>/<METHOD>_<path>.txt` (`/` thành `_`) |
| Đổi role được gọi một API | `<roles>` của route trong `api-routes.xml` | Quyền trên dữ liệu (sở hữu) vẫn kiểm tra trong service |
| Cho client gọi API qua gateway | `routes[*]` trong `application.properties` của gateway | Cần đăng nhập thì thêm `JwtAuth`; dễ bị spam (login, đăng ký, gửi OTP...) thì thêm `RateLimit` |
| Đổi bảng / thêm cột | Migration mới `V<n>__...sql` + entity cùng commit | Không sửa migration cũ |
| Gọi service khác | Client `XxxGrpcClient` trong `client/` của service gọi, bọc stub gRPC; không dùng HTTP | Ánh xạ lỗi thành `BusinessException`, có deadline |
| Thêm API gRPC | Theo `docs/grpc-conventions.md`: `.proto` trong `fd-grpc-api/src/main/proto/<service>/v1/` + `XxxGrpcService` (server) + `GrpcClientConfig` và `XxxGrpcClient` (client) | Starter `spring-boot-starter-grpc-server` / `-client` chỉ `groupId` + `artifactId` (version, exclusion Logback lấy từ `pom.xml` gốc); `GrpcConventionsTest` kiểm tra quy ước |
| Đổi xử lý lỗi / log / header chung của gRPC | `fd-grpc-api`, package `com.fooddelivery.grpc.support` | Áp dụng cho mọi service có gRPC |
| Đổi nội dung access token | `TokenService` (auth) **và** `AccessTokenVerifier` (gateway) | Hai bên cùng thứ tự kiểm tra |
| Thêm service mới | Module `fd-xxx-service` + `<module>` trong `pom.xml` gốc + route ở gateway | Dùng `common-lib`; cập nhật mục 2, 3 của file này và bảng port trong README |

---

## 9. Test và kiểm thử

- Unit test mỗi service: `./mvnw test -pl <module>`; cả repo: `./mvnw clean install`. Quy tắc và mức độ test trong
  `CLAUDE.md`. Không dùng tài nguyên thật: Mockito, `HttpServer` cục bộ, gRPC in-process, `Clock` cố định.
- Test dùng chung trong gateway: `AccessTokenVerifierTestSupport` (tạo khoá và token mẫu).
- Kiểm thử ghép nối: mỗi API HTTP một file cURL trong `postman/<module>/` (cách dùng: `postman/README.md`).
- Checklist nghiệm thu theo từng phần: `docs/auth-login-checklist.md`, `docs/gateway-grpc-checklist.md` (nhật ký giai
  đoạn trước, gRPC `GetUser` trong đó đã bị xoá), `docs/grpc-hardening-checklist.md`,
  `docs/restaurant-role-checklist.md`.
- `ArchitectureDocTest` (common-lib): mọi module và mọi class trong `src/main/java` phải được nhắc trong file này.
- Test gRPC của service dùng `InProcessGrpcServer` (test jar của fd-grpc-api, `<type>test-jar</type>`): server
  in-process có đúng các interceptor của service đang chạy; mẫu trong `docs/grpc-conventions.md`.
- `GrpcConventionsTest` (common-lib, quy tắc trong `GrpcConventions`): mọi `.proto` và class gRPC theo
  `docs/grpc-conventions.md`. Fail thì sửa code cho đúng quy ước, không sửa test.
