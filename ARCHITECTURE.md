# Kiến trúc food-delivery-backend

Bản đồ cấu trúc của backend: **được xây thế nào, sửa ở đâu**. Cách chạy và thử (port, Postman, grpcurl) nằm trong
[README.md](README.md) và README của từng service; quy tắc làm việc trong [CLAUDE.md](CLAUDE.md). File này không chép
lại.

Đọc file này trước khi thêm, xoá, đổi tên, di chuyển class/package/module, hay đổi một hợp đồng ở mục 7. Mọi thay đổi
như vậy phải cập nhật file này trong cùng commit. `ArchitectureDocTest` (common-lib) fail nếu một class hoặc module
trong `src/main` không được nhắc tới ở đây.

---

## 1. Tổng quan

Backend microservice của ứng dụng giao đồ ăn: Maven multi-module, Java 21, Spring Boot 4.1.2, Spring Cloud 2025.1
(Gateway WebFlux), Spring gRPC, PostgreSQL + Flyway, Log4j2. Build bằng Maven Wrapper `./mvnw` (Maven 3.9.16).

Thư viện dùng chung nằm ở repo riêng `food-delivery-libs` (format `ApiResponse`, luồng `api-routes.xml` -> JSON
Schema -> validator, ID 14 chữ số; xem `ARCHITECTURE.md` của repo đó), cài vào `~/.m2` trước khi build backend.

Trạng thái hiện tại: gateway, auth-service và order-service đã có chức năng (đăng ký, đăng nhập, phiên, kiểm tra token
ở gateway, gọi chéo HTTP + gRPC). Restaurant, delivery, chat mới chỉ có health check.

---

## 2. Module và phụ thuộc

```
food-delivery-libs (~/.m2)
        │
        ▼
   common-lib ─────────────┬──────────────► fd-auth-service   ◄── fd-grpc-api (server)
   (bundle dependency)     └──────────────► fd-order-service  ◄── fd-grpc-api (client)

   common-dto-lib ─────────────────────────► fd-gateway-service (WebFlux, không dùng common-lib)

   spring-boot-starter-webmvc ─────────────► fd-restaurant-service, fd-delivery-service, fd-chat-service
```

| Module | Port | Vai trò | Database |
|---|---:|---|---|
| `common-lib` | - | Không có code Java: gom dependency chung cho service Spring MVC (4 lib của `food-delivery-libs`, JPA, PostgreSQL, Flyway, Log4j2, `spring-security-crypto`, `nimbus-jose-jwt`) và `log4j2-spring.xml` chung | - |
| `fd-grpc-api` | - | Thư viện (jar), không chạy như service. Hợp đồng gRPC giữa service: file `.proto` (sinh code bằng `protobuf-maven-plugin`), `GrpcMetadataKeys`; và phần hỗ trợ gRPC dùng chung, tự đăng ký vào mọi service có gRPC server/client (`GrpcSupportAutoConfiguration`) | - |
| `fd-gateway-service` | 8080 | Cổng duy nhất cho client: route `/auth/**`, `/order/**`; kiểm tra access token (`JwtAuth`) trước order | - |
| `fd-auth-service` | 8081, gRPC 9081 | Tài khoản, đăng nhập, phiên (refresh token), ký access token RS256, JWKS; cung cấp user cho service khác qua HTTP `/internal/**` và gRPC | `FD_auth_db` |
| `fd-order-service` | 8083 | Đơn hàng (chưa có nghiệp vụ); hiện có hai API test gọi auth qua HTTP và gRPC | `FD_order_db` (chưa có bảng) |
| `fd-restaurant-service` | 8082 | Nhà hàng, món ăn (chưa có nghiệp vụ) | - |
| `fd-delivery-service` | 8084 | Giao hàng (chưa có nghiệp vụ) | - |
| `fd-chat-service` | 8085 | Chat (chưa có nghiệp vụ) | - |

Quy tắc phụ thuộc (không được phá):

- Service **không** phụ thuộc service khác; gọi nhau qua HTTP `/internal/**` hoặc gRPC, hợp đồng gRPC chỉ nằm trong
  `fd-grpc-api`.
- Service Spring MVC kéo thư viện qua `common-lib`, không khai báo từng lib. Gateway là WebFlux nên chỉ dùng
  `common-dto-lib` (cùng format response), không dùng `common-lib`.
- Mọi starter kéo Logback (vd. gRPC) phải exclude `spring-boot-starter-logging`: dự án dùng Log4j2.
- Version khai báo ở `pom.xml` gốc (`dependencyManagement`, BOM Spring Boot / Spring Cloud / gRPC).

---

## 3. Bản đồ class

Mỗi class một dòng. Cột Test là class test cùng package trong `src/test/java`; "(không cần)" là class không có logic theo quy tắc test
trong `CLAUDE.md`; "-" là còn thiếu test.

### fd-grpc-api (`com.fooddelivery.grpc`)

| Class / file | Vai trò | Test |
|---|---|---|
| `GrpcMetadataKeys` | Tên metadata dùng chung: trailer `error-code` (mã lỗi nghiệp vụ), header `x-caller-service` | (hằng số) |
| `auth/v1/user_service.proto` | `fooddelivery.auth.v1.UserService/GetUser` | (sinh code) |
| `GrpcSupportAutoConfiguration` | Auto-configuration: service có gRPC server được `BusinessExceptionGrpcHandler` + `GrpcLoggingInterceptor`, service có gRPC client được `CallerServiceClientInterceptor`; nhường bean cùng loại của service | `GrpcSupportAutoConfigurationTest` |
| `BusinessExceptionGrpcHandler` | (server) `BusinessException` -> status gRPC + trailer `error-code`; lỗi khác -> `INTERNAL` không lộ chi tiết | `BusinessExceptionGrpcHandlerTest` |
| `GrpcLoggingInterceptor` | (server) Log mỗi lời gọi gRPC: method, caller, status, thời gian | `GrpcLoggingInterceptorTest` |
| `CallerServiceClientInterceptor` | (client) Gắn `x-caller-service` = `spring.application.name` vào mọi lời gọi gRPC | `CallerServiceClientInterceptorTest` |

Phần hỗ trợ nằm ở package `com.fooddelivery.grpc.support`, đăng ký trong
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Dependency Spring gRPC,
`common-web-lib`, Log4j2 là `optional`: service dùng gRPC đã có chúng qua starter gRPC và `common-lib`.

### fd-gateway-service (`com.fooddelivery.gateway`)

| Class | Package | Vai trò | Test |
|---|---|---|---|
| `GatewayApplication` | (gốc) | Entry point | (không cần) |
| `GatewayController` | `controller` | `GET /gateway/health` | (không cần) |
| `ClockConfig` | `config` | Bean `Clock`, để test dùng thời gian cố định | (không cần) |
| `JwtAuthGatewayFilterFactory` | `filter` | Filter `JwtAuth`: đọc cookie `access_token`, xoá `X-User-Id`/`X-User-Role` client gửi, gắn lại từ token; sai -> 401/503 | `JwtAuthGatewayFilterFactoryTest` |
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
| `InternalUserController` | `controller` | `GET /internal/users/{userId}` cho service khác | `InternalUserControllerTest` |
| `JwksController` | `controller` | `GET /auth/.well-known/jwks.json` (path exclude khỏi API flow) | `JwksControllerTest` |
| `TestController` | `controller` | `GET /auth/test/ping` | `TestControllerTest` |
| `AuthValidator` | `validation` | Validator của register, login: bắt buộc, độ dài, định dạng | `AuthValidatorTest` |
| `InternalUserValidator` | `validation` | Validator của `/internal/users/{userId}`: ID đúng định dạng user | `InternalUserValidatorTest` |
| `AuthService` | `service.auth` | Đăng ký (trùng username/email/SĐT), đăng nhập, user hiện tại | `AuthServiceLoginTest` (login, me), `AuthServiceRegisterTest` |
| `LoginResult` | `service.auth` | User + token của một lần đăng nhập | (record) |
| `SessionService` | `service.session` | Phiên = chuỗi refresh token: tạo, xoay vòng, phát hiện dùng lại, đăng xuất | `SessionServiceTest` |
| `IssuedTokens` | `service.session` | Access + refresh token để controller đặt cookie | (record) |
| `TokenService` | `service.token` | Ký / kiểm tra access token RS256, sinh và hash refresh token; không DB | `TokenServiceTest` |
| `AccessTokenVerification` | `service.token` | Kết quả kiểm tra access token | (kiểm qua `TokenServiceTest`) |
| `UserQueryService` | `service.user` | Đọc user cho service khác; dùng chung cho HTTP và gRPC | `UserQueryServiceTest` |
| `PingService` | `service.test` | Logic của `/auth/test/ping` | `PingServiceTest` |
| `UserGrpcService` | `grpc` | gRPC `UserService/GetUser`, cùng logic với `InternalUserController` | `UserGrpcServiceTest` |
| `AuthCookies` | `web` | Dựng `Set-Cookie` cho `access_token`, `refresh_token` | `AuthCookiesTest` |
| `OriginCheckFilter` | `web` | Chống CSRF: request thay đổi dữ liệu từ origin lạ -> 403 `ORIGIN_NOT_ALLOWED` | `OriginCheckFilterTest` |
| `WebFilterConfig` | `config.web` | Đăng ký filter với thứ tự rõ ràng | (không cần) |
| `JwtKeyConfig` | `config.security` | Dựng cặp khoá RSA từ `auth.jwt.*`, kiểm tra lúc startup | `JwtKeyConfigTest` |
| `PasswordConfig` | `config.security` | Bean BCrypt `PasswordEncoder` | (không cần) |
| `AuthProperties` | `config.properties` | Bind `auth.*`, kiểm tra giá trị lúc startup | `AuthPropertiesTest` |
| `ClockConfig` | `config.common` | Bean `Clock` | (không cần) |
| `User` | `common.model` | Entity bảng `users` | (entity) |
| `RefreshToken` | `common.model` | Entity bảng `refresh_tokens` (chỉ lưu hash) | (entity) |
| `IdentifierNormalizer` | `common.util` | Chuẩn hoá username/email/SĐT về dạng lưu DB | `IdentifierNormalizerTest` |
| `UserRepository` | `repository` | JPA `users` | (Spring Data) |
| `RefreshTokenRepository` | `repository` | JPA `refresh_tokens`, tra theo hash | (Spring Data) |
| `UserDto` | `dto` | User trả về client (không có hash mật khẩu) | (record) |
| `InternalUserDto` | `dto` | User trả cho service khác, chung cho HTTP và gRPC | (record) |
| `LoginRequest` | `dto` | Body `POST /auth/login` | (record) |
| `RegisterRequest` | `dto` | Body `POST /auth/register`; `normalized()` dùng `IdentifierNormalizer` | `RegisterRequestTest` |
| `PingResponse` | `dto` | Data của `/auth/test/ping` | (record) |

Resource: `api-routes.xml` (khai báo 8 API), `schemas/login.json`, `schemas/register.json`,
`db/migration/V1__create_users_and_refresh_tokens.sql`, `application.properties` (mô tả từng property `auth.*`).

### fd-order-service (`com.fooddelivery.order`)

| Class | Package | Vai trò | Test |
|---|---|---|---|
| `OrderApplication` | (gốc) | Entry point | (không cần) |
| `OrderController` | `controller` | `GET /order/health` | (không cần) |
| `AuthTestController` | `controller` | `GET /order/test/auth-http`, `/order/test/auth-grpc` | `AuthTestControllerTest` |
| `AuthTestValidator` | `validation` | Kiểm tra `X-User-Id` do gateway gắn (thiếu/sai -> 401) | `AuthTestValidatorTest` |
| `AuthCallTestService` | `service` | Đo thời gian, gọi auth qua client HTTP hoặc gRPC | `AuthCallTestServiceTest` |
| `AuthHttpClient` | `client` | `RestClient` -> `GET :8081/internal/users/{id}`; lỗi -> `BusinessException` | `AuthHttpClientTest` |
| `AuthGrpcClient` | `client` | Stub gRPC -> `UserService/GetUser` với deadline; status -> `BusinessException` | `AuthGrpcClientTest` |
| `AuthUser` | `client` | User nhận từ auth, chung cho hai client | (record) |
| `AuthClientProperties` | `config` | Bind `services.auth.*` (URL HTTP, timeout), kiểm tra lúc startup | `AuthClientPropertiesTest` |
| `GrpcClientConfig` | `config` | `@ImportGrpcClients`: bean stub trên kênh `auth` | (không cần) |
| `AuthCallResult` | `dto` | Data của API test: transport, thời gian, user | (record) |

Resource: `api-routes.xml` (3 API), `application.properties`.

### fd-restaurant-service, fd-delivery-service, fd-chat-service

Chỉ có entry point và health check, chưa dùng `common-lib` hay API flow:

| Module | Class |
|---|---|
| `fd-restaurant-service` | `RestaurantApplication`, `RestaurantController` (`GET /restaurant/health`) |
| `fd-delivery-service` | `DeliveryApplication`, `DeliveryController` (`GET /delivery/health`) |
| `fd-chat-service` | `ChatApplication`, `ChatController` (`GET /chat/health`) |

---

## 4. Luồng chạy

### Request từ client

```
Client ──> gateway :8080
            ├─ /auth/**   ───────────────────────────────────────────────> auth :8081
            └─ /order/**  JwtAuth: cookie access_token -> AccessTokenVerifier (key từ JwksKeyProvider)
                          sai/thiếu -> 401 UNAUTHENTICATED, hết hạn -> 401 TOKEN_EXPIRED, chưa có key -> 503
                          hợp lệ -> xoá X-User-Id/X-User-Role của client, gắn từ token ──> order :8083
```

Trong mỗi service Spring MVC (auth, order), request đi qua luồng của `common-api-flow-lib`: `api-routes.xml` (404) ->
JSON Schema (400) -> validator (400 + field lỗi) -> controller -> service (lỗi nghiệp vụ, cần DB) -> `ApiResponse`.
Auth có thêm `OriginCheckFilter` trước luồng này.

### Đăng nhập và phiên (auth)

```
POST /auth/login   AuthValidator -> AuthService.login (BCrypt) -> SessionService.start -> TokenService
                   -> AuthController đặt cookie access_token (JWT RS256, ngắn) + refresh_token (opaque, dài)
POST /auth/refresh SessionService: refresh token cũ hết hiệu lực, cấp cặp mới; token đã xoay bị dùng lại
                   -> 409 REFRESH_CONFLICT (tab khác vừa refresh) hoặc 401 REFRESH_TOKEN_REUSED (thu hồi cả phiên)
POST /auth/logout  SessionService.end, xoá cookie
```

Gateway và service khác kiểm tra access token **tự mình** bằng public key từ `GET /auth/.well-known/jwks.json`, không
gọi auth mỗi request.

### Gọi giữa service (order -> auth)

```
AuthTestController -> AuthCallTestService ─┬─ AuthHttpClient  ── HTTP ─> auth /internal/users/{id} -> InternalUserController
                                           └─ AuthGrpcClient  ── gRPC ─> auth :9081 UserGrpcService
                                                                             └─ cả hai -> UserQueryService
```

Lỗi của auth giữ nguyên mã: HTTP 404 / gRPC `NOT_FOUND` + trailer `error-code` -> `BusinessException` cùng mã ở order;
không kết nối được / quá timeout -> 503 `AUTH_UNAVAILABLE`; lỗi khác -> 502 `AUTH_CALL_FAILED`.

---

## 5. Database

- Mỗi service sở hữu database riêng (`FD_auth_db`, `FD_order_db`); service không đọc database của service khác.
- Cấu trúc chỉ đổi qua migration Flyway trong `src/main/resources/db/migration` của service đó; Hibernate
  `ddl-auto=validate`. Quy tắc chi tiết trong `CLAUDE.md`.
- `FD_auth_db`: bảng `users`, `refresh_tokens` (V1).

---

## 6. Điểm mở rộng và cấu hình

- `application.properties` của mỗi service mô tả từng property. Nhóm chính: `auth.*` (`AuthProperties`: JWT, cookie,
  thời hạn token, origin cho phép), `gateway.auth.*` (`GatewayAuthProperties`), `services.auth.*`
  (`AuthClientProperties`), `spring.grpc.client.channel.auth.target`, `api-flow.*` (thư viện).
- Route của gateway: `spring.cloud.gateway.server.webflux.routes[*]` trong `application.properties` của gateway; route
  cần đăng nhập thêm `filters[0]=JwtAuth`.
- Thêm API gRPC: viết `.proto` trong `fd-grpc-api`, implement ở service sở hữu dữ liệu, tạo stub bằng
  `@ImportGrpcClients` ở service gọi. Xử lý lỗi, log và header caller có sẵn nhờ `GrpcSupportAutoConfiguration`,
  service không viết lại.

---

## 7. Hợp đồng không được phá

- **Header danh tính**: service phía sau gateway tin `X-User-Id` / `X-User-Role` do gateway gắn; gateway luôn xoá bản
  client gửi. Port nội bộ (8081-8085, 9081, `/internal/**`) không được mở ra ngoài.
- **Access token**: RS256, `kid` khớp JWKS, claim `sub` (user ID), `role`, `iss`, `iat`, `exp`, `jti`. Đổi ở `TokenService` thì phải
  đổi `AccessTokenVerifier` của gateway.
- **Cookie**: tên `access_token`, `refresh_token` và thuộc tính (`HttpOnly`, `SameSite`, `Secure`, `Path`) trong
  `AuthCookies`; frontend dựa vào chúng.
- **gRPC**: `.proto` chỉ thêm field mới, không đổi số/kiểu field cũ; package có version (`auth.v1`). Lỗi nghiệp vụ trả
  trailer `error-code` (`GrpcMetadataKeys`).
- **HTTP nội bộ và gRPC trả cùng dữ liệu** (`InternalUserDto`, `UserQueryService`).
- **Mã lỗi** gửi client (`USER_NOT_FOUND`, `TOKEN_EXPIRED`, `AUTH_UNAVAILABLE`...): client và service khác rẽ nhánh
  theo chúng.
- **Migration đã commit** không sửa, không xoá.

---

## 8. Việc thường gặp: sửa ở đâu

| Việc | Sửa ở | Lưu ý |
|---|---|---|
| Thêm API vào một service | `api-routes.xml`, `schemas/*.json`, `validation/*Validator`, controller, service | Theo "Luồng xử lý một API" trong `CLAUDE.md`; thêm vào Postman collection |
| Cho client gọi API qua gateway | `routes[*]` trong `application.properties` của gateway | Cần đăng nhập thì thêm `JwtAuth` |
| Đổi bảng / thêm cột | Migration mới `V<n>__...sql` + entity cùng commit | Không sửa migration cũ |
| Gọi service khác | Client trong `client/` của service gọi (HTTP `RestClient` hoặc stub gRPC) | Ánh xạ lỗi thành `BusinessException`, có timeout/deadline |
| Thêm API gRPC | `.proto` trong `fd-grpc-api/src/main/proto/<service>/v1/` + `*GrpcService` (server) + stub và `*GrpcClient` (client) | Service thêm dependency `fd-grpc-api` và starter `spring-boot-starter-grpc-server` / `-client` (exclude Logback); lỗi, log, caller header tự có |
| Đổi xử lý lỗi / log / header chung của gRPC | `fd-grpc-api`, package `com.fooddelivery.grpc.support` | Áp dụng cho mọi service có gRPC |
| Đổi nội dung access token | `TokenService` (auth) **và** `AccessTokenVerifier` (gateway) | Hai bên cùng thứ tự kiểm tra |
| Thêm service mới | Module `fd-xxx-service` + `<module>` trong `pom.xml` gốc + route ở gateway | Dùng `common-lib`; cập nhật mục 2, 3 của file này và bảng port trong README |

---

## 9. Test và kiểm thử

- Unit test mỗi service: `./mvnw test -pl <module>`; cả repo: `./mvnw clean install`. Quy tắc và mức độ test trong
  `CLAUDE.md`. Không dùng tài nguyên thật: Mockito, `HttpServer` cục bộ, gRPC in-process, `Clock` cố định.
- Test dùng chung trong gateway: `AccessTokenVerifierTestSupport` (tạo khoá và token mẫu).
- Kiểm thử ghép nối: Postman collection trong `postman/` (`fd-auth-service`, `fd-order-service`), chạy bằng newman.
- Checklist nghiệm thu theo từng phần: `docs/auth-login-checklist.md`, `docs/gateway-grpc-checklist.md`.
- `ArchitectureDocTest` (common-lib): mọi module và mọi class trong `src/main/java` phải được nhắc trong file này.
