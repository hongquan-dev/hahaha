# Checklist: API Gateway, kiểm tra token và gọi auth-service qua HTTP + gRPC

Phạm vi: `fd-gateway-service`, `fd-auth-service`, `fd-order-service`, module proto mới và `food-delivery-libs`
(nâng Spring). Mỗi bước có mục **Kiểm tra** (cách nghiệm thu) và mục **Trao đổi** (những điều sẽ giải thích sau khi
làm xong). Chỉ tick khi kiểm tra đạt. Các mã `[Dx]` tham chiếu bảng quyết định ở bước 0.

## Bức tranh tổng thể

```
                       ┌──────────────── fd-gateway-service :8080 ────────────────┐
Browser / Postman ───> │ /auth/**  -> không kiểm tra token -> auth :8081           │
                       │ /order/** -> JwtAuthFilter (verify JWT bằng JWKS)        │
                       │             sai/hết hạn -> 401, đúng -> thêm X-User-Id,   │
                       │             X-User-Role -> order :8083                    │
                       └──────────────────────────────────────────────────────────┘
                                                        │
                       fd-order-service :8083           ▼
                         GET /order/test/auth-http ──HTTP──> auth :8081  GET /internal/users/{userId}
                         GET /order/test/auth-grpc ──gRPC──> auth :9081  UserService.GetUser
```

- Client chỉ biết tới cổng **8080**. Auth (8081, 9081) và order (8083) coi như mạng nội bộ.
- Gateway **không gọi auth cho mỗi request**: nó tải public key (JWKS) của auth một lần, cache lại và tự verify chữ ký.
- Hai API test của order lấy **cùng một user** (người đang đăng nhập), một bằng HTTP và một bằng gRPC, nên kết quả
  phải giống nhau. Đây là cách nghiệm thu gRPC dễ nhất.

---

## 0. Chốt quyết định

Đây là đề xuất của tôi. Bạn tick những mục đồng ý, mục nào muốn đổi thì ghi chú ngay bên dưới.

- [ ] **D1** Nâng lên **Spring Boot 4.1** để dùng starter gRPC chính thức (`spring-boot-starter-grpc-server`/`-client`).
      Nếu Spring Cloud Gateway chưa có bản hỗ trợ 4.1 thì lùi về Boot 4.0 + Spring gRPC 1.0.x (xem P0)
- [ ] **D2** Gateway tự verify JWT bằng JWKS (`GET /auth/.well-known/jwks.json`), không gọi auth ở mỗi request
- [ ] **D3** Gateway đọc token từ cookie `access_token`; nếu không có cookie thì đọc header `Authorization: Bearer ...`
      để test bằng Postman/curl cho tiện
- [ ] **D4** Lỗi token ở gateway dùng đúng mã của auth: hết hạn → 401 `TOKEN_EXPIRED`, thiếu/sai → 401
      `UNAUTHENTICATED`, body theo format `ApiResponse`. Nhờ vậy logic refresh ở FE chạy được mà không phải sửa
- [ ] **D5** Gateway chuyển danh tính xuống service sau bằng header `X-User-Id`, `X-User-Role`, và **luôn xoá** hai
      header này nếu client tự gửi lên (chống giả mạo)
- [ ] **D6** "Cổng HTTP của auth để test" được hiểu là API nội bộ `GET /internal/users/{userId}` trên port 8081.
      Gateway không route `/internal/**`, nên từ ngoài không gọi được
- [ ] **D7** gRPC của auth chạy trên port **9081**, có một RPC `UserService.GetUser(user_id)`
- [ ] **D8** File `.proto` đặt trong module mới **`fd-grpc-api`** của repo này (trong parent pom). Auth và order cùng
      phụ thuộc module này
- [ ] **D9** Order service dùng `common-lib` (api-flow, `ResponseHandler`, `BusinessException`) như auth.
      `common-lib` có sẵn JPA + Flyway nên order cần database: tạo **`FD_order_db`** rỗng, chưa có migration.
      Phương án khác: tạm tắt DataSource auto-config ở order
- [ ] **D10** Gọi sang auth luôn có deadline/timeout **2 giây**. Auth không phản hồi → 503 `AUTH_UNAVAILABLE`;
      user không tồn tại → 404 `USER_NOT_FOUND`
- [ ] **D11** Giai đoạn này chưa làm bảo mật giữa các service (mTLS, service token): chỉ dựa vào việc port nội bộ
      không mở ra ngoài. Ghi lại để làm ở giai đoạn sau
- [ ] **D12** `GET /order/health` vẫn đi qua gateway nhưng không cần token (whitelist trong `JwtAuthFilter`)

---

## P0. Nâng Spring Boot 3.3.2 → 4.1

Làm riêng, commit riêng, **trước** mọi thứ khác. Đây là bước rủi ro nhất vì kéo theo Spring Framework 7, Hibernate 7,
Jackson 3 và Spring Cloud bản mới.

- [x] Kiểm tra ma trận tương thích: **Spring Boot 4.1.2 + Spring Cloud 2025.1.4** (Gateway 5.0.4). Bản 2025.1.4 dựng
      trên Boot 4.0 nhưng hỗ trợ chính thức Boot 4.1.2, nên không cần phương án lùi của D1. Starter gRPC
      `spring-boot-starter-grpc-server` 4.1.2 đã có trên Maven Central
- [x] `food-delivery-libs` (repo riêng ở `/data/food-delivery-libs`):
  - [x] `spring-boot.version` 4.1.2
  - [x] Jackson 2 → 3: dependency `tools.jackson.core` / `tools.jackson.dataformat`, import `tools.jackson.*`,
        `JsonProcessingException` → `JacksonException` (unchecked), `fields()`/`fieldNames()` → `properties()`/
        `propertyNames()`, `asText()`/`isTextual()` → `asString()`/`isString()`. Bỏ `jackson-datatype-jsr310`
        (Jackson 3 có sẵn `java.time`). Annotation (`@JsonInclude`...) vẫn ở `com.fasterxml.jackson.annotation`
  - [x] `JacksonAutoConfiguration` chuyển package (sang module `spring-boot-jackson`): `ApiFlowAutoConfiguration` và
        `CommonWebAutoConfiguration` tham chiếu bằng tên (`afterName`)
  - [x] `mvn install` lại; README ghi Boot 4.1.2 + Jackson 3
- [x] Parent `pom.xml`: `spring-boot-starter-parent` 4.1.2, `spring-cloud.version` 2025.1.4
- [x] `common-lib/pom.xml`: `spring-boot-starter-web` → `-webmvc`; `jackson-dataformat-xml` sang `tools.jackson`;
      `flyway-core` → `spring-boot-starter-flyway` (Boot 4 tách auto-config Flyway ra starter riêng: chỉ có
      `flyway-core` thì migration **không bao giờ chạy**). `nimbus-jose-jwt` 10.10 giữ nguyên
- [x] restaurant / delivery / chat / order: `spring-boot-starter-web` → `-webmvc`
- [x] Auth: code chính không phải sửa gì; chỉ `OriginCheckFilterTest` đổi import `JsonMapper`. Bỏ property
      `hibernate.dialect` (Hibernate 7 tự nhận dạng và cảnh báo nếu khai báo)
- [x] Gateway: starter `spring-cloud-starter-gateway-server-webflux`, prefix
      `spring.cloud.gateway.server.webflux.*` (prefix cũ bị bỏ qua, route sẽ không chạy)

**Kiểm tra:**
- `mvn clean install` ở root: BUILD SUCCESS, toàn bộ test pass
- Khởi động auth: Flyway + `ddl-auto=validate` không lỗi; register / login / me / refresh / logout chạy như cũ
  (dùng Postman collection có sẵn)
- Khởi động gateway và order: `GET /gateway/health`, `GET /order/health` trả 200

Kết quả tôi đã chạy:
- libs: 7/7 test pass; project: 51/51 test pass (bằng đúng số trước khi nâng)
- Cây dependency: auth và gateway chỉ có Log4j2 (không có Logback) và chỉ có Jackson 3 (3.1.7); Hibernate 7.4,
  Flyway 12.4
- Auth khởi động: Flyway "Successfully validated 1 migration", `validate` pass, không còn cảnh báo dialect
- Qua gateway: register 201, trùng 409 `USER_ALREADY_EXISTS` kèm lỗi field, field lạ 400 `INVALID_REQUEST_BODY`,
  sai định dạng 400 kèm lỗi field, login 200 kèm 2 cookie (đủ `Path`, `HttpOnly`, `Secure`, `SameSite`), me 200,
  ping 200, refresh 200, `Origin` lạ 403 `ORIGIN_NOT_ALLOWED`, logout 200, me sau logout 401, API lạ 404
  `API_NOT_FOUND`. JSON response giữ nguyên format (ngày ISO-8601)
- `GET /gateway/health`, `GET /order/health` 200
- Cổng 8080 trên máy đang bị project `dms-api` chiếm, nên lúc test gateway chạy ở 18080
  (`-Dspring-boot.run.arguments=--server.port=18080`). Cấu hình vẫn để 8080
- Test đã tạo user `p0test_97341` trong `FD_auth_db`
- [ ] Đạt:

**Trao đổi:** những gì đã vỡ và vì sao (Jackson 3, Hibernate 7...), những gì nhóm cần biết khi viết code mới trên Boot 4.

---

## P1. Gateway: điều hướng các API auth

- [x] Route `/auth/**` → auth-service, dùng prefix property mới từ P0
- [x] Gateway trả nguyên `Set-Cookie`, `Origin`, status và body. Kiểm tra cookie `access_token` (`Path=/`) và
      `refresh_token` (`Path=/auth`) vẫn đúng khi đi qua 8080
- [x] Không route `/internal/**`, gọi vào 8080 thì nhận 404
- [x] Địa chỉ service đặt trong property `services.auth.http-url=http://localhost:8081`, route dùng
      `${services.auth.http-url}`
- [x] Comment cho `application.properties` của gateway theo `CLAUDE.md`
- [x] FE (`Food Delivery/hehehe`): `VITE_PROXY_TARGET` (`.env.development`, `.env.example`) và giá trị mặc định trong
      `vite.config.ts` đổi sang `8080`; cập nhật `README.md`, `CLAUDE.md`, `docs/handoff/fe-scaffold.md`
- [x] Phát sinh thêm: **IP client**. Gateway mới mặc định không gửi `X-Forwarded-For`, nên auth sẽ lưu IP của gateway
      cho mọi phiên. Đã xử lý:
  - Gateway: `trusted-proxies=(?!)`: bỏ mọi header `X-Forwarded-*` client tự gửi, tự ghi `X-Forwarded-For` bằng IP
    nó thấy
  - Auth: `server.forward-headers-strategy=native`: Tomcat lấy IP từ `X-Forwarded-For`, chỉ khi request đến từ địa
    chỉ nội bộ
  - Cập nhật comment `ApiRequest.getRemoteAddr()` (libs, chưa commit) và README của auth

**Kiểm tra** (tất cả gọi vào **8080**):
- `POST /auth/register` → 201; `POST /auth/login` → 200 kèm 2 cookie
- `GET /auth/me` với cookie → 200; `POST /auth/refresh` → 200 kèm cookie mới; `POST /auth/logout` → 200
- `POST /auth/login` với `Origin: http://evil.com` → 403 `ORIGIN_NOT_ALLOWED` (chứng minh header đi qua gateway)
- `GET http://localhost:8080/internal/users/1` → 404

Kết quả tôi đã chạy (gateway ở 18080 vì 8080 đang bị `dms-api` chiếm):
- Qua gateway: login 200 kèm 2 cookie đúng thuộc tính, me 200, refresh 200, logout 200; `Origin` lạ 403
  `ORIGIN_NOT_ALLOWED`, `Origin: http://localhost:5173` 200
- `/internal/users/1` → 404. Body là format mặc định của gateway (`timestamp`, `path`, `status`, `error`), chưa
  phải `ApiResponse`; sẽ thống nhất ở P3 (D4)
- IP lưu trong `refresh_tokens`: gọi gateway qua IP LAN → `10.2.22.53` (đúng IP client); qua gateway kèm
  `X-Forwarded-For: 6.6.6.6` giả → `127.0.0.1` (header giả bị bỏ); gọi **thẳng** 8081 từ LAN kèm header giả →
  `6.6.6.6` (giả được vì 10.x là địa chỉ nội bộ, đúng giới hạn đã ghi: không được mở port 8081 ra ngoài)
- Qua Vite (`/api/**` → gateway → auth): login 200, cookie `refresh_token` được đổi sang `Path=/api/auth`, me 200,
  ping 200, refresh 200, logout 200. Tắt riêng gateway thì `/api/auth/me` qua Vite lỗi 500 trong khi auth vẫn chạy,
  chứng tỏ FE thật sự đi qua gateway
- FE: typecheck, lint, prettier, build đều sạch; backend 51/51 test pass
- [ ] Đạt:

**Trao đổi:** gateway làm gì với request/response (predicate, filter, proxy), vì sao cookie vẫn chạy khi đi qua gateway.

---

## P2. Auth: công bố public key (JWKS)

- [x] `GET /auth/.well-known/jwks.json` trả `{"keys":[...]}` chỉ gồm **public key** (`RSAKey.toPublicJWK()`), có
      `kid`, `alg=RS256`, `use=sig`. Path này đã nằm trong `api-flow.exclude-paths`
      (`JwksController`; body là JWK Set chuẩn, không bọc `ApiResponse`, vì thư viện JWT đọc đúng định dạng này;
      `Content-Type: application/jwk-set+json` theo RFC 7517)
- [x] Header `Cache-Control: public, max-age=300` (Spring ghi thành `max-age=300, public`)
- [x] Unit test (`JwksControllerTest`, 3 test): JWKS không chứa các thành phần của private key (`d`, `p`, `q`, `dp`,
      `dq`, `qi`); header; token ký bằng private key kiểm tra được bằng key đọc lại từ JWKS
- [x] README của auth (danh sách API, mục 4.1, cấu trúc source, bảng unit test) và Postman (request
      "Auth - JWKS" có test script)

**Kiểm tra:**
- `curl http://localhost:8080/auth/.well-known/jwks.json` → 200, đúng `kid` = `auth.jwt.key-id`, không có field `d`
- Dán JWKS và một access token vào jwt.io → chữ ký hợp lệ

Kết quả tôi đã chạy (gateway ở 18080):
- Unit test auth: 54/54 pass (51 cũ + 3 mới)
- Qua gateway: 200, `Content-Type: application/jwk-set+json`, `Cache-Control: max-age=300, public`; key có `kty`,
  `e`, `use=sig`, `kid=fd-auth-dev-1` (trùng `auth.jwt.key-id`), `alg=RS256`, `n`; không có tham số private.
  `POST` vào cùng path → 405
- Thay jwt.io bằng một đoạn Python tự kiểm RS256 (không dùng chung thư viện với auth): access token thật lấy từ
  login → chữ ký **hợp lệ** với key trong JWKS; cùng token đó sửa `role` thành `ADMIN` → **không hợp lệ**
- [ ] Đạt:

**Trao đổi:** JWKS là gì, vì sao công bố public key là an toàn, `kid` giúp đổi khoá mà không làm hỏng token cũ ra sao.

---

## P3. Gateway: kiểm tra token rồi mới cho vào order

- [x] Dependency: `nimbus-jose-jwt` để verify, `common-dto-lib` để dùng `ApiResponse`/`ApiStatus` (không dùng
      `common-lib` vì gateway chạy WebFlux, không phải Servlet). Version của cả hai chuyển lên parent pom
      (`nimbus-jose-jwt.version`, `food-delivery-libs.version`) để auth và gateway luôn dùng cùng bản
- [x] `JwksKeyProvider`: tải JWKS từ auth bằng `WebClient` (không chặn thread), cache 5 phút; gặp `kid` lạ thì tải
      lại (tối đa 1 lần mỗi 30 giây). Tải lazy ở request đầu tiên, nên gateway vẫn khởi động được khi auth chưa chạy.
      Thêm: các request đồng thời dùng chung một lần tải; tải lỗi thì giữ key đã tải lần trước
- [x] Filter `JwtAuth` (`JwtAuthGatewayFilterFactory`, gắn vào route `/order/**` bằng `filters[0]=JwtAuth`):
  - [x] Lấy token theo D3 (cookie `access_token` trước, rồi `Authorization: Bearer`)
  - [x] Kiểm tra theo đúng thứ tự của `TokenService.verifyAccessToken`: định dạng → `alg` = RS256 → `kid` → chữ ký →
        `iss` = `fd-auth-service` → `sub` → `exp` (lệch giờ cho phép 30 giây như auth) (`AccessTokenVerifier`)
  - [x] Sai → 401 theo D4. Không lấy được JWKS (auth chết) → 503 `AUTH_UNAVAILABLE`
  - [x] Đúng → xoá `X-User-Id`/`X-User-Role` client gửi lên, gắn giá trị từ claim `sub`/`role` (D5). Header client
        gửi lên bị xoá ở **mọi** request của route, kể cả đường dẫn public
  - [x] Whitelist `GET /order/health` (D12), qua property `gateway.auth.public-paths`
  - [x] Log: method, path, userId, kết quả. **Không log token**; lý do token sai chỉ ghi ở mức DEBUG, không trả cho
        client
- [x] Route `/order/**` → `${services.order.http-url}` (`http://localhost:8083`) kèm `JwtAuth`
- [x] Unit test filter: token đúng, hết hạn, sai chữ ký, sai issuer, `alg=none`, không có token, client tự gửi `X-User-Id`
      (gateway có 26 test: `AccessTokenVerifierTest` 12, `JwtAuthGatewayFilterFactoryTest` 8, `JwksKeyProviderTest` 6
      chạy với một HTTP server thật thay cho auth)

**Kiểm tra** (gọi `GET http://localhost:8080/order/test/auth-http`, hoặc một endpoint echo tạm của order nếu P5 chưa
xong):
- Không có cookie → 401 `UNAUTHENTICATED`, order **không** nhận được request (xem log order)
- Có cookie hợp lệ → request tới order, order thấy `X-User-Id` = id của user đã login
- Đặt `auth.jwt.access-ttl=10s`, đợi hơn 40 giây → 401 `TOKEN_EXPIRED`; `POST /auth/refresh` rồi gọi lại → 200
- Gửi kèm `X-User-Id: 999` → order vẫn nhận id thật, không phải 999
- Sửa 1 ký tự trong chữ ký của token → 401 `UNAUTHENTICATED`
- Tắt auth, khởi động lại gateway, gọi với token hợp lệ → 503 `AUTH_UNAVAILABLE`

Kết quả tôi đã chạy (gateway ở 18080; order chưa có `/order/test` nên đặt một echo server vào chỗ order, nó trả lại
header nhận được và đếm số request, không thêm code tạm vào order):
- Build toàn bộ: gateway 26/26, auth 54/54 test pass
- Không token → 401 `UNAUTHENTICATED`, order nhận 0 request. Body theo format `ApiResponse`, có `path`
- Cookie hợp lệ → order nhận `X-User-Id: 17947973411001`, `X-User-Role: CUSTOMER`; `Authorization: Bearer` cũng được
- Gửi kèm `X-User-Id: 999`, `X-User-Role: ADMIN` giả → order vẫn nhận id và role thật
- Sửa 1 ký tự ở giữa chữ ký, đổi `sub` sang user khác giữ nguyên chữ ký, token tự chế `alg=none` → cả ba 401
  `UNAUTHENTICATED`, order không nhận request nào. (Sửa ký tự **cuối** của chữ ký thì vẫn qua: 342 ký tự Base64URL chứa
  2052 bit nhưng chữ ký chỉ 2048 bit, 4 bit cuối là phần đệm bị bỏ khi giải mã, nên chữ ký giải ra y hệt. Không phải
  lỗi)
- `access-ttl=10s`: ngay sau login 200; sau 25s vẫn 200 (trong 30s lệch giờ); sau 42s gửi token cũ → 401
  `TOKEN_EXPIRED`; refresh 200, gọi lại 200. Lưu ý: nếu dùng cookie thì sau 10s trình duyệt/curl tự xoá cookie
  (`Max-Age` = thời hạn token) nên request không có token và nhận `UNAUTHENTICATED`; FE vẫn refresh vì nó refresh với
  mọi 401
- Tắt auth khi gateway đã có key → vẫn 200. Gateway khởi động lại khi auth vẫn tắt → 503 `AUTH_UNAVAILABLE`;
  `/order/health` vẫn 200
- `/order/health` không token → 200, header `X-User-Id: 999` giả bị xoá
- Order thật (8083) qua gateway: `/order/health` 200
- Log gateway không có dòng nào chứa token
- [ ] Đạt:

**Trao đổi:** vì sao gateway verify tại chỗ thay vì gọi auth; rủi ro của header `X-User-Id` và vì sao phải xoá header
client gửi lên; khi đổi khoá thì cache JWKS hoạt động thế nào.

---

## P4. Auth: API HTTP nội bộ để order gọi

- [x] Route `GET /internal/users/{userId}` trong `api-routes.xml` (không có body nên không có schema; có validator kiểm
      tra `userId` là số 14 chữ số). `InternalUserValidator.validateGetUser` kiểm tra thêm chữ số thứ 11 là mã loại
      `USER` (ID của order hay loại khác bị từ chối), vẫn không truy vấn DB. Sai → 400 `INVALID_USER_ID` kèm lỗi field
- [x] `InternalUserController` → `UserQueryService.getById(userId)` trả về `id`, `username`, `phoneNumber`, `role`,
      `active` (`InternalUserDto`). Bảng `users` không có `fullName` nên bỏ field này. Không trả email,
      **không bao giờ** trả password hash. User bị khoá vẫn trả về với `active=false`, để service gọi tự quyết định
- [x] Không có user → 404 `USER_NOT_FOUND`
- [x] `UserQueryService` được dùng chung cho cả HTTP (P4) và gRPC (P5): logic chỉ viết một lần
- [x] README của auth (danh sách API, bảng luồng, cấu trúc source, bảng test, bảng mã lỗi; sửa số route cũ bị lệch) và
      Postman: 3 request "Internal - ..." dùng biến `authInternalUrl` (8081, vì gateway không route `/internal/**`);
      request Login lưu `userId` cho request sau

**Kiểm tra:**
- `curl http://localhost:8081/internal/users/<id thật>` → 200 với đúng dữ liệu, không có field `password`
- Id không tồn tại → 404 `USER_NOT_FOUND`; id dạng `abc` → 400 kèm lỗi field `userId`
- `curl http://localhost:8080/internal/users/<id>` → 404 (gateway không mở ra ngoài)

Kết quả tôi đã chạy (gateway ở 18080):
- Unit test auth: 60/60 pass (54 cũ + `InternalUserValidatorTest` 3 + `UserQueryServiceTest` 3)
- `GET :8081/internal/users/17947973411001` → 200, `data` = `id`, `username`, `phoneNumber`, `role`, `active`;
  không có `email`, `password`
- `17000000001000` (đúng định dạng user, không có trong DB) → 404 `USER_NOT_FOUND`
- `abc` → 400 `INVALID_USER_ID`, `errors: [{field: "userId"}]`; ID của order (`17947973414001`) → 400 như trên
- `POST` cùng path → 405 (Spring MVC trả trước API flow, giống mọi route khác)
- Qua gateway → 404, request không tới auth
- [ ] Đạt:

**Trao đổi:** khác nhau giữa API public và API nội bộ; vì sao chưa cần bảo mật thêm cho API này (D11) và sau này cần gì.

---

## P5. Module proto và gRPC server ở auth

- [x] Module `fd-grpc-api` (khai báo trong parent pom): `protobuf-maven-plugin` sinh code Java khi build. Cấu hình
      protoc/grpc-java đã có sẵn trong `spring-boot-starter-parent`, module chỉ khai báo plugin
- [x] **Phát sinh: Maven Wrapper.** Plugin này cần Maven ≥ 3.9.6, máy đang có 3.6.3. Đã thêm `mvnw`, `mvnw.cmd`,
      `.mvn/wrapper/maven-wrapper.properties` (Maven 3.9.16): từ nay build bằng `./mvnw`, mọi người cùng một bản Maven
- [x] `src/main/proto/auth/v1/user_service.proto`: `package fooddelivery.auth.v1`,
      `java_package = "com.fooddelivery.grpc.auth.v1"`, `service UserService { rpc GetUser(...) }`, message `GetUserRequest`
      (`user_id`) và `UserResponse` (cùng các field với P4). Mỗi message/field có comment. `user_id`/`id` là `int64`
      (ID là `long`); `phone_number` là `optional` để phân biệt "không có" với chuỗi rỗng
- [x] Auth: thêm `spring-boot-starter-grpc-server` + `fd-grpc-api`, `spring.grpc.server.port=9081`. Phải loại
      `spring-boot-starter-logging` khỏi starter gRPC (Logback đụng Log4j2, toàn bộ test lỗi khi khởi tạo logger)
- [x] `UserGrpcService extends UserServiceGrpc.UserServiceImplBase` → `UserQueryService` → map sang proto. Kiểm tra
      `user_id` bằng `InternalUserValidator.checkUserId`, cùng hàm với HTTP
- [x] Ánh xạ `BusinessException` → gRPC `Status` bằng `BusinessExceptionGrpcHandler` (hook `GrpcExceptionHandler`
      của Spring gRPC, không cần tự viết interceptor): 400 → `INVALID_ARGUMENT`, 404 → `NOT_FOUND`, ...,
      còn lại → `INTERNAL`. Mã lỗi (vd. `USER_NOT_FOUND`) đặt trong metadata `error-code`
- [x] Server interceptor log: method gRPC, status, thời gian xử lý (`GrpcLoggingInterceptor`, đặt **ngoài**
      interceptor xử lý exception để thấy cả lời gọi lỗi)
- [x] Bật gRPC server reflection (chỉ ở dev) để test bằng `grpcurl`/Postman (`grpc-services`,
      `spring.grpc.server.reflection.enabled=true`, comment ghi tắt ở production). Kèm theo: health service chuẩn
      `grpc.health.v1.Health`
- [x] README gốc (build bằng `./mvnw`, port 9081), README auth (lệnh chạy, cấu hình, cấu trúc source, bảng test,
      mục 15 gRPC kèm lệnh grpcurl)

**Kiểm tra:**
- Log auth khi khởi động có cả Tomcat 8081 lẫn gRPC 9081
- `grpcurl -plaintext localhost:9081 list` → thấy `fooddelivery.auth.v1.UserService`
- `grpcurl -plaintext -d '{"user_id":"<id>"}' localhost:9081 fooddelivery.auth.v1.UserService/GetUser` → đúng dữ
  liệu, giống hệt kết quả của P4
- Id không tồn tại → status `NOT_FOUND`, metadata `error-code: USER_NOT_FOUND`

Kết quả tôi đã chạy (grpcurl 1.9.4 tải vào `/tmp`, không cài vào máy):
- `./mvnw clean install`: mọi module BUILD SUCCESS; auth 65/65 (thêm `UserGrpcServiceTest` 5, chạy qua stub thật trên
  gRPC server in-process), gateway 26/26
- Log khởi động: "Tomcat started on port 8081", "Registered gRPC service: fooddelivery.auth.v1.UserService",
  "gRPC Server started, listening on ... 9081"
- `list` → `fooddelivery.auth.v1.UserService`, `grpc.health.v1.Health`, `grpc.reflection.v1.ServerReflection`
- `GetUser 17947973411001` → `id`, `username`, `phone_number`, `role`, `active` trùng khớp với
  `GET /internal/users/17947973411001` (grpcurl in `int64` thành chuỗi theo chuẩn JSON của protobuf)
- `17000000001000` → `NotFound`, `error-code: USER_NOT_FOUND`; ID của order → `InvalidArgument`,
  `error-code: INVALID_USER_ID`
- `Health/Check` → `SERVING`
- Log: mỗi lời gọi một dòng `[gRPC] ... | status=..., Nms`. Lần đầu lời gọi lỗi **không** được log vì interceptor
  log nằm trong interceptor exception của Spring; đã sửa bằng `@Order(GrpcExceptionHandlerInterceptor.ORDER - 1)`,
  chạy lại thì cả `NOT_FOUND`, `INVALID_ARGUMENT` đều có log
- [ ] Đạt:

**Trao đổi:** từ `.proto` sinh ra những class nào; vì sao số field (`= 1`, `= 2`) không bao giờ được đổi; một service
chạy song song 2 server như thế nào; `StreamObserver` và vì sao không dùng `return`.

---

## P6. Order: nền tảng + 2 API test

### P6.1 Nền tảng

- [x] `pom.xml`: thay `spring-boot-starter-webmvc` bằng `common-lib`, thêm `fd-grpc-api` và
      `spring-boot-starter-grpc-client` (loại Logback như ở auth). Order giờ log bằng Log4j2 như các service khác
- [x] Database theo D9: đã tạo `FD_order_db` rỗng (`CREATE DATABASE`), chưa có migration (Flyway chỉ cảnh báo "No
      migrations found"). `api-routes.xml` (khai báo cả `/order/health` có sẵn), `application.properties` có comment.
      `log4j2-spring.xml` lấy từ `common-lib`, không cần file riêng
- [x] Property: `services.auth.http-url=http://localhost:8081`, `services.auth.timeout=2s` (dùng chung cho HTTP và
      gRPC, `AuthClientProperties`), `spring.grpc.client.channel.auth.target=static://localhost:9081` (tên đúng là
      `target`, không phải `address`)
- [x] Stub gRPC tạo bằng `@ImportGrpcClients(target = "auth", ...)` trong `GrpcClientConfig`; kênh kết nối lười, nên
      order khởi động được khi auth chưa chạy

### P6.2 API test HTTP: `GET /order/test/auth-http`

- [x] Lấy `userId` từ header `X-User-Id` (gateway đã gắn). Không có header → 401 `UNAUTHENTICATED`
      (`AuthTestValidator.validateCaller`, cũng kiểm tra đúng 14 chữ số)
- [x] `AuthHttpClient` (Spring `RestClient` trên JDK `HttpClient`, timeout connect + read 2s) gọi
      `GET /internal/users/{userId}`, đọc `data` của `ApiResponse`
- [x] Ánh xạ lỗi theo D10: 4xx của auth giữ nguyên status và mã lỗi (vd. 404 `USER_NOT_FOUND`); không kết nối được /
      quá giờ → 503 `AUTH_UNAVAILABLE`; 5xx hoặc body không đọc được → **502** `AUTH_CALL_FAILED` (thay cho 500 trong
      kế hoạch: lỗi nằm ở service phía sau, 502 Bad Gateway đúng nghĩa hơn)

### P6.3 API test gRPC: `GET /order/test/auth-grpc`

- [x] `AuthGrpcClient` bọc `UserServiceBlockingStub`, mỗi lời gọi có `withDeadlineAfter(services.auth.timeout)`
- [x] Ánh xạ `StatusRuntimeException`: `NOT_FOUND` → 404, `INVALID_ARGUMENT` → 400, giữ mã lỗi từ trailer
      `error-code`; `UNAVAILABLE`/`DEADLINE_EXCEEDED` → 503 `AUTH_UNAVAILABLE`; còn lại → 502 `AUTH_CALL_FAILED`
      (cùng quy ước với HTTP)
- [x] Client interceptor: `CallerServiceClientInterceptor` gắn `x-caller-service: service-order` vào mọi lời gọi; auth
      ghi vào log (`caller=service-order`). Bỏ `x-user-id` (đã nằm trong request) và trace id (chưa có hạ tầng tracing,
      để giai đoạn sau). Tên metadata (`error-code`, `x-caller-service`) đặt chung trong `GrpcMetadataKeys` của
      `fd-grpc-api`, auth và order dùng cùng một hằng số

### P6.4 Response chung của 2 API

```json
{ "transport": "HTTP" | "gRPC", "elapsedMs": 12, "user": { "id": "...", "username": "...", "role": "CUSTOMER", ... } }
```

**Kiểm tra** (login qua 8080, rồi gọi qua **8080**):
- `GET /order/test/auth-http` và `GET /order/test/auth-grpc` → 200, cùng một `user`, chỉ khác `transport`/`elapsedMs`
- Log auth: một request HTTP `GET /internal/users/...` và một lời gọi gRPC `GetUser`
- Không login → cả hai trả 401 từ gateway, order không nhận được request
- Tắt auth (vẫn giữ token hợp lệ, JWKS gateway đã cache) → cả hai trả 503 `AUTH_UNAVAILABLE` trong khoảng 2 giây,
  không treo
- Gọi mỗi API khoảng 20 lần rồi so `elapsedMs` của HTTP với gRPC (chỉ để quan sát, không phải điều kiện đạt)

Kết quả tôi đã chạy (gateway ở 18080; auth 8081 + 9081, order 8083):
- `./mvnw install`: auth 65/65, order 15/15 (`AuthHttpClientTest` 6 với HTTP server thật, `AuthGrpcClientTest` 7 với
  gRPC server in-process, `AuthTestValidatorTest` 2), gateway 26/26
- Login qua gateway → `auth-http` và `auth-grpc` đều 200, cùng `user` `{id: 17947973411001, username: p0test_97341,
  phoneNumber: +84994797341, role: CUSTOMER, active: true}`, khác `transport`
- Log auth: `[gRPC] .../GetUser | caller=service-order, status=OK`
- Không login → 401 `UNAUTHENTICATED` từ gateway; log order không có dòng nào cho request này
- Gọi thẳng order (bỏ qua gateway) không có `X-User-Id` → 401 từ `AuthTestValidator`
- Gọi thẳng order với `X-User-Id` không tồn tại → cả hai transport 404 `USER_NOT_FOUND`, cùng message
- Tắt auth → cả hai 503 `AUTH_UNAVAILABLE` ngay (kết nối bị từ chối). Trường hợp auth treo không trả lời: unit test
  của cả hai client xác nhận dừng sau timeout và trả 503
- `elapsedMs`, 20 lần mỗi loại sau khi đã khởi động: HTTP min 4 / median 5 / p90 9 / max 11 ms; gRPC min 3 /
  median 4 / p90 6 / max 8 ms. Lần gọi gRPC đầu tiên 308 ms vì kênh mới kết nối (lười)
- [ ] Đạt:

**Trao đổi:** đi từng bước một lời gọi gRPC từ order sang auth (serialize, HTTP/2, deadline, status trailer); vì sao
bọc stub trong adapter; so sánh hai cách gọi về code, lỗi và hiệu năng.

---

## P7. Hoàn thiện

- [ ] Mọi class, method, file cấu hình mới có comment theo `CLAUDE.md`; `api-routes.xml` có comment cho từng route
- [ ] Không chỗ nào log token, password
- [ ] Postman: cập nhật collection auth sang 8080, thêm `postman/fd-order-service.postman_collection.json` (2 API test)
      và một request gRPC (hoặc ghi lệnh `grpcurl` vào README)
- [ ] README: bảng port (8080, 8081, 9081, 8083), thứ tự khởi động, cách tạo `FD_order_db`, cách test gRPC
- [ ] `mvn clean install` ở root: BUILD SUCCESS, toàn bộ test pass

**Kiểm tra:** người khác clone repo, đọc README, khởi động 3 service và chạy được toàn bộ Postman collection.
- [ ] Đạt:

**Trao đổi:** tổng kết kiến trúc, những gì còn thiếu so với production (D11 mTLS/service token, service discovery,
load balancing gRPC, tracing) và nên làm gì tiếp.
