# Checklist: Đăng nhập bằng cookie (access + refresh token)

Phạm vi giai đoạn 1: `fd-auth-service` và FE React TS. Mỗi bước có mục **Kiểm tra**; chỉ tick khi kiểm tra đạt.
Các mã `[Dx]` tham chiếu tới bảng quyết định ở bước 0.

---

## 0. Chốt quyết định

- [x] **D1** Đăng nhập bằng một field `identifier` (username / email / SĐT), service tự nhận dạng
- [x] **D2** Access token: JWT RS256, có `kid`, sau này công bố qua `/auth/.well-known/jwks.json`
- [x] **D3** Access token sống 15 phút
- [x] **D4** Refresh token: chuỗi ngẫu nhiên 32 byte (Base64URL), DB chỉ lưu SHA-256, rotation mỗi lần refresh
- [x] **D5** Refresh có 2 mốc hết hạn:
  - Idle 7 ngày: mỗi token mới sau rotation sống 7 ngày, nên người dùng thường xuyên không phải login lại
  - Tuyệt đối 30 ngày tính từ lúc login (theo family): quá mốc này luôn phải login lại
- [x] **D6** Không làm "Ghi nhớ đăng nhập": D5 đã giữ đăng nhập lâu cho người dùng thường xuyên
- [x] **D7** Chống CSRF: SameSite + kiểm tra header `Origin` cho POST/PUT/PATCH/DELETE:
  - Có `Origin` nhưng không nằm trong whitelist → 403
  - Không có `Origin` → cho qua: trình duyệt luôn gửi `Origin` với các method này, nên request không có `Origin`
    không phải từ trình duyệt và không thể là CSRF (Postman/curl vẫn test được)
- [x] **D8** Thêm `ApiRequest.getCookie(name)` vào `common-api-flow-lib`
- [x] **D9** Giới hạn đăng nhập sai: để giai đoạn 2
- [x] **D10** FE thử refresh một lần cho mọi 401 (trừ `/auth/login`, `/auth/refresh`, `/auth/logout`)
- [x] **D11** Nhiều tab: `navigator.locks` ở FE + grace window 10 giây ở BE (409 `REFRESH_CONFLICT`)
- [x] **D12** `logout-all`: để giai đoạn 2

---

## Phần A: Backend

### A1. Thư viện dùng chung

- [x] Thêm `getCookie(String name)` vào `ApiRequest`: trả `RequestValue` (không bao giờ `null`, `asString()` = `null`
      nếu không có cookie), lấy cookie đầu tiên nếu trùng tên
- [x] Cập nhật Javadoc của `ApiRequest` và `RequestValue` (thêm "cookie" vào danh sách nguồn giá trị), cùng README của lib
      nếu có liệt kê các getter
- [x] Route **không có** `<schema>` đã được hỗ trợ (`ApiRegistry`: schema optional, interceptor bỏ qua bước kiểm tra body)
- [x] Route **không có** `<validator>` đã được hỗ trợ (`ApiRequestArgumentResolver` tự tạo `ApiRequest` cho controller)
- [x] `mvn install` lại `food-delivery-libs`, auth-service build được

**Kiểm tra:** auth-service khởi động bình thường, `POST /auth/register` vẫn chạy như cũ.
- [x] Đạt: khởi động OK; register trả 201, đăng ký trùng trả 409 `USER_ALREADY_EXISTS` kèm lỗi từng field

### A2. Dependency, khoá RSA, cấu hình

- [x] Thêm `com.nimbusds:nimbus-jose-jwt` 10.10 vào `fd-auth-service/pom.xml`, kèm comment lý do
- [x] Sinh cặp khoá RSA 2048 cho dev (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`)
- [x] Khoá dev đặt thẳng trong `application.properties` (đã chốt: chấp nhận lộ trong giai đoạn dev ban đầu).
      Trước khi deploy thật: đổi khoá mới, đọc từ secret/biến môi trường và đổi `auth.jwt.key-id`
- [x] Thêm property, mỗi property có comment và giá trị mặc định:
  - [x] `auth.jwt.private-key`, `auth.jwt.public-key`, `auth.jwt.key-id`, `auth.jwt.issuer`
  - [x] `auth.jwt.access-ttl=15m`, `auth.refresh.idle-ttl=7d`, `auth.refresh.absolute-ttl=30d`,
        `auth.refresh.reuse-grace=10s`
  - [x] `auth.cookie.secure=true`
  - [x] `auth.allowed-origins=http://localhost:5173`
- [x] `AuthProperties` (`@ConfigurationProperties`): kiểm tra giá trị bắt buộc, TTL dương, `idle-ttl` ≤ `absolute-ttl`,
      định dạng origin
- [x] `JwtKeyConfig`: parse khoá thành bean `RSAKey` (nimbus), kiểm tra ≥ 2048 bit và đúng một cặp

**Kiểm tra:** thiếu hoặc sai khoá thì app từ chối khởi động với thông báo rõ ràng; đủ khoá thì chạy bình thường.
- [x] Đạt: cấu hình đúng → khởi động OK; 8 trường hợp sai (khoá hỏng, thiếu khoá, khoá lệch cặp, khoá 1024 bit,
      origin có `/` cuối, origin `*`, `idle-ttl` > `absolute-ttl`, TTL = 0) → không khởi động, lỗi nêu đúng property

### A3. Persistence cho refresh token

- [x] Thêm `EntityType.REFRESH_TOKEN(6)` vào `common-id-lib` (cùng README), `mvn install` lại
- [x] Entity `RefreshToken` (bảng `refresh_tokens`): `id`, `user_id`, `token_hash` (unique), `family_id`,
      `expires_at` (idle, now + 7 ngày), `family_expires_at` (tuyệt đối, lúc login + 30 ngày), `revoked_at`, `replaced_at`, `user_agent`, `ip`, `created_at`
  - [x] `family_id` = `id` của token đầu tiên (tạo lúc login)
  - [x] `user_id` là cột số thường, không dùng `@ManyToOne`
  - [x] Các cột thời gian dùng `Instant` (`timestamp with time zone`)
- [x] Unique `token_hash`; index `family_id` và `user_id`
- [x] `RefreshTokenRepository`: `findByTokenHash`, `revokeFamily` (một câu `UPDATE`)

**Kiểm tra:** app khởi động, Hibernate tạo bảng `refresh_tokens` đúng cột và index.
- [x] Đạt: `\d refresh_tokens` đủ 11 cột, đúng kiểu và null; có PK, `uk_refresh_tokens_token_hash`,
      `idx_refresh_tokens_family_id`, `idx_refresh_tokens_user_id`

### A4. TokenService

- [x] `issueAccessToken(User)`: JWT RS256, header `typ`, `kid`, claim `iss`, `sub`, `role`, `iat`, `exp`, `jti`
- [x] `verifyAccessToken(String)`: trả `AccessTokenVerification` `VALID` / `EXPIRED` / `INVALID`, không ném exception
  - [x] Thứ tự: định dạng → `alg` = RS256 → `kid` → chữ ký → `iss` → `sub` → `exp` (chỉ token thật mới `EXPIRED`)
  - [x] Clock skew 30 giây khi kiểm tra `exp`
- [x] `generateRefreshToken()`: 32 byte từ `SecureRandom`, Base64URL không padding (43 ký tự)
- [x] `hash(String)`: SHA-256, 64 ký tự hex chữ thường
- [x] Nguồn thời gian là bean `Clock` (`ClockConfig`), test truyền `Clock.fixed`
- [x] Thêm `spring-boot-starter-test` (scope `test`, loại `spring-boot-starter-logging` vì dự án dùng Log4j2)
- [x] Unit test `TokenServiceTest` (15 test): ký rồi verify OK; claim đúng; còn trong / quá clock skew; sửa payload;
      ký bằng khoá khác; token giả đã quá hạn vẫn `INVALID`; `alg: none`; HS256 ký bằng public key; sai `iss`; sai `kid`;
      chuỗi rác / rỗng / `null`; refresh token 43 ký tự và không trùng; vector SHA-256 chuẩn

**Kiểm tra:** unit test pass.
- [x] Đạt: `mvn test` 15/15 pass; app vẫn khởi động bình thường với bean `TokenService`

### A5. Cookie và kiểm tra Origin

- [x] `AuthCookies` (`web/AuthCookies.java`): dựng `ResponseCookie`, tên cookie là hằng số dùng chung khi đọc
  - [x] `access_token`: `Path=/`, `HttpOnly`, `Secure` (theo cấu hình), `SameSite=Lax`, `Max-Age`=TTL access
  - [x] `refresh_token`: `Path=/auth`, `HttpOnly`, `Secure`, `SameSite=Strict`, `Max-Age`= đến mốc **sớm hơn** trong
        `expires_at` và `family_expires_at`
  - [x] Bản xoá của cả hai cookie (`Max-Age=0`, cùng `Path`)
  - [x] `headersOf(...)`: gom cookie thành `HttpHeaders` cho `ResponseHandler.generateResponse(status, headers, body)`
- [x] Kiểm tra `Origin` cho POST/PUT/PATCH/DELETE: có `Origin` nhưng không nằm trong whitelist thì 403
      `ORIGIN_NOT_ALLOWED`; không có `Origin` thì cho qua (D7); `Origin: null` bị chặn
- [x] Đặt ở servlet filter `OriginCheckFilter`, đăng ký trong `WebFilterConfig` với order `HIGHEST_PRECEDENCE + 5`
      (trước `RequestCachingFilter` của API flow), áp dụng mọi path; lỗi ghi bằng `ApiResponseWriter`
- [x] Unit test: `AuthCookiesTest` (7), `OriginCheckFilterTest` (7)

**Kiểm tra:** `curl -X POST /auth/register -H "Origin: http://evil.com"` → 403; đúng Origin hoặc không gửi Origin thì
vẫn 201.
- [x] Đạt: `Origin: http://evil.com` → 403 `ORIGIN_NOT_ALLOWED` (có log `[ORIGIN] Rejected`); `Origin:
      http://localhost:5173` → 201; không có `Origin` → 201; GET không bị filter chặn; `mvn test` 29/29 pass

### A6. API `POST /auth/login`

- [x] `schemas/login.json`: `identifier` (string), `password` (string), không cho field lạ
- [x] DTO `LoginRequest` (record, Javadoc `@param`)
- [x] `AuthValidator.validateLogin`: bắt buộc, không rỗng, giới hạn độ dài, không truy vấn DB
- [x] `AuthService.login`:
  - [x] Nhận dạng và chuẩn hoá `identifier` (dùng lại phần chuẩn hoá của register)
  - [x] Không có user thì vẫn chạy `BCrypt.matches` với một hash giả (chống dò user qua timing)
  - [x] Sai → 401 `INVALID_CREDENTIALS`
  - [x] Đúng mật khẩu nhưng `active=false` → 403 `ACCOUNT_DISABLED`
  - [x] Tạo family mới, lưu hash của refresh token, ký access token
  - [x] Log đăng nhập, **không log mật khẩu hay token**
- [x] `AuthController.login`: trả 200 + `UserDto` + 2 `Set-Cookie` (qua `ResponseHandler.generateResponse(status, headers, body)`)
- [x] Thêm route vào `api-routes.xml`, có comment
- [x] Đã làm theo 4 điểm chốt: `IdentifierNormalizer` dùng chung với register; SĐT không tìm thấy thì tìm tiếp theo
      username; IP qua `ApiRequest.getRemoteAddr()` (thêm vào lib); service trả `LoginResult` nội bộ
- [x] Phiên (refresh token) tách sang `SessionService`: `start` (login), `refresh`, `end` (logout)

**Kiểm tra (curl / Postman):**
- [x] Đăng nhập bằng username, email, SĐT (`0912...` và `+84912...`) đều trả 200 và có 2 cookie đúng thuộc tính
- [x] Sai mật khẩu và user không tồn tại trả **cùng** 401 `INVALID_CREDENTIALS`
- [x] Thiếu field → 400; thừa field → 400 `INVALID_REQUEST_BODY`
- [x] User bị khoá → 403 `ACCOUNT_DISABLED`
- [x] Bảng `refresh_tokens` có 1 dòng, cột `token_hash` khác giá trị cookie
- [x] Đạt (curl): 6 kiểu identifier (`a6u...`, ` A6U... `, email viết hoa, `09...`, `+849...`, `09xx.xxx.xxx`) → 200;
      `Set-Cookie` đúng `Path`, `Max-Age`, `HttpOnly`, `SameSite`; sai mật khẩu và user không tồn tại cùng 401
      `INVALID_CREDENTIALS`, thời gian ~70 ms như nhau; khoá + đúng mật khẩu → 403, khoá + sai mật khẩu → 401;
      `Origin` lạ → 403; DB: `family_id = id`, hash ≠ cookie, idle 7 ngày, tuyệt đối 30 ngày, có `user_agent`, `ip`;
      payload JWT chỉ có `iss`, `sub`, `role`, `iat`, `exp`, `jti`

### A7. API `GET /auth/me`

- [x] Đọc cookie `access_token` → `verifyAccessToken`
- [x] Thiếu hoặc sai → 401 `UNAUTHENTICATED`; hết hạn → 401 `TOKEN_EXPIRED`
- [x] Lấy user từ DB; không có hoặc `active=false` → 401 `UNAUTHENTICATED`
- [x] Trả 200 + `UserDto`
- [x] Thêm route vào `api-routes.xml`

**Kiểm tra:**
- [x] Có cookie hợp lệ → 200
- [x] Không có cookie → 401 `UNAUTHENTICATED`
- [x] Token bị sửa → 401 `UNAUTHENTICATED`
- [x] Token hết hạn (tạm đặt `access-ttl=10s`) → 401 `TOKEN_EXPIRED`
- [x] Đạt (curl, `access-ttl=5s`): cookie hợp lệ → 200; không cookie → 401 `UNAUTHENTICATED`; sửa 1 ký tự →
      401 `UNAUTHENTICATED`; sau 36 giây (5s + skew 30s) → 401 `TOKEN_EXPIRED`

### A8. API `POST /auth/refresh`

- [x] Đọc cookie `refresh_token` → hash → `findByTokenHash`
- [x] Không có cookie, không tìm thấy, quá `expires_at` hoặc quá `family_expires_at` → 401 `REFRESH_TOKEN_INVALID`
      + xoá cookie
- [x] User bị khoá → revoke family, 401 `REFRESH_TOKEN_INVALID` + xoá cookie
- [x] Token đã revoke:
  - [x] `replaced_at` ≤ 10 giây trước → 409 `REFRESH_CONFLICT`, không revoke gì
  - [x] Ngược lại → revoke **cả family**, 401 `REFRESH_TOKEN_REUSED` + xoá cookie
- [x] Hợp lệ: trong **một transaction**, revoke token cũ (`revoked_at`, `replaced_at`) và tạo token mới cùng
      `family_id`, cùng `family_expires_at`, `expires_at` mới = now + 7 ngày
- [x] Trả 200 + 2 cookie mới, `data = null`
- [x] Khoá dòng khi đọc (`PESSIMISTIC_WRITE`) để hai request cùng token không cùng rotation thành công
- [x] Thêm route vào `api-routes.xml`

**Kiểm tra:**
- [x] Refresh → 200, cookie mới khác cookie cũ, DB có token cũ bị revoke và token mới cùng family
- [x] Dùng lại token cũ trong vòng 10 giây → 409 `REFRESH_CONFLICT`
- [x] Dùng lại token cũ sau 10 giây → 401 `REFRESH_TOKEN_REUSED`, mọi token trong family bị revoke
- [x] Không có cookie → 401 `REFRESH_TOKEN_INVALID`
- [x] Refresh cookie mới có `Max-Age` ≈ 7 ngày; khi family còn dưới 7 ngày thì `Max-Age` = thời gian còn lại của family
- [x] Sửa tay `family_expires_at` về quá khứ → refresh trả 401 `REFRESH_TOKEN_INVALID`
- [x] Đạt (curl): refresh → 200, 2 cookie mới khác cookie cũ; dùng lại token cũ sau <10s → 409 (không xoá cookie),
      sau 11s → 401 `REFRESH_TOKEN_REUSED` + xoá cookie, cả family (2 token) bị revoke, token mới nhất cũng thành
      `INVALID`; `Max-Age` 604799 → còn 172799 khi family còn 2 ngày; family hết hạn → 401; user bị khoá → 401 và
      phiên bị revoke
- [x] Song song: 5 request refresh cùng lúc với cùng token → 1 × 200, 4 × 409 (khoá dòng hoạt động)

### A9. API `POST /auth/logout`

- [x] Đọc cookie `refresh_token`; nếu tìm thấy thì revoke cả family
- [x] Luôn trả 200 + 2 cookie xoá (kể cả khi không có hoặc sai token)
- [x] Thêm route vào `api-routes.xml`

**Kiểm tra:**
- [x] Logout → 200, cookie bị xoá, family bị revoke trong DB
- [x] Gọi `/auth/refresh` bằng token cũ sau khi logout → 401
- [x] Logout khi không có cookie → vẫn 200
- [x] Đạt (curl): logout → 200 + 2 `Set-Cookie` xoá, phiên bị revoke trong DB; refresh sau logout → 401
      `REFRESH_TOKEN_INVALID`; logout không có cookie → 200; logout với `Origin` lạ → 403

### A10. Rà soát backend

- [x] Mọi class và method mới có comment theo `CLAUDE.md`
- [x] Không chỗ nào log mật khẩu, access token hay refresh token
- [x] Thêm Postman collection cho 4 API mới vào `postman/`
- [x] Toàn bộ kiểm tra A6–A9 chạy lại một lượt đều pass
- [x] Unit test thêm: `IdentifierNormalizerTest` (3), `AuthServiceLoginTest` (9), `SessionServiceTest` (10)
- [x] Đạt: `mvn test` 51/51 pass; log của cả lượt test không chứa mật khẩu, access token hay refresh token

---

## Phần B: Frontend (React TS)

Project: `Food Delivery/hehehe` (Vite + React 18 + TS). Thêm `axios` và `react-router-dom` 6 (bản 7 cần Node ≥ 20,
máy đang dùng Node 18). `npm run build` và `npm run lint` sạch.

Kiểm thử đầu-cuối chạy trên Chrome headless thật (puppeteer-core, cài ở `/tmp/fd-e2e`, không thêm vào project), với
backend `access-ttl=5s` và cấu hình cookie thật (`Secure`): **17/17 pass**.

### B1. Hạ tầng gọi API

- [x] `vite.config.ts`: proxy `/auth` → `http://localhost:8081`, port 5173
- [x] `src/api/client.ts`: axios instance với `withCredentials: true`, `baseURL` lấy từ `VITE_API_BASE_URL` (khai báo
      kiểu trong `vite-env.d.ts`)
- [x] `src/api/types.ts`: `ApiResponse<T>`, `FieldErrorDetail`, `UserDto` khớp BE
- [x] `src/api/authApi.ts`: `login`, `me`, `refresh`, `logout`

**Kiểm tra:** gọi `POST /auth/login` từ FE thấy cookie trong DevTools → Application → Cookies.
- [x] Đạt (E2E): sau login có `access_token` (`Path=/`, `SameSite=Lax`) và `refresh_token` (`Path=/auth`,
      `SameSite=Strict`), cả hai `HttpOnly`

### B2. AuthProvider

- [x] State `{ status: 'checking' | 'authenticated' | 'anonymous', user: UserDto | null }` (`src/auth/AuthProvider.tsx`,
      `useAuth` trong `src/auth/AuthContext.ts`)
- [x] Khi mount: gọi `GET /auth/me` → `authenticated` hoặc `anonymous`
- [x] Hàm `login(identifier, password)`: thành công thì set user từ body
- [x] Hàm `logout()`
- [x] Không lưu token hay user vào `localStorage`

**Kiểm tra:** F5 sau khi login vẫn ở trạng thái đăng nhập; xoá cookie bằng tay rồi F5 thì về `anonymous`.
- [x] Đạt (E2E): F5 ở `/account` vẫn đăng nhập; xoá cookie `refresh_token` → lần gọi sau về `/login`

### B3. Trang đăng nhập

- [x] Form `identifier` + `password`, nút bị khoá khi đang gửi (`src/pages/LoginPage.tsx`)
- [x] 400: hiện lỗi dưới đúng field theo `errors[].field`
- [x] 401 `INVALID_CREDENTIALS`: "Sai tài khoản hoặc mật khẩu"
- [x] 403 `ACCOUNT_DISABLED`: "Tài khoản đã bị khoá"
- [x] Thành công: chuyển về trang người dùng định vào trước đó, hoặc trang chủ (do `GuestRoute` đảm nhận)
- [x] Thêm: lỗi mạng → "Không kết nối được máy chủ"; hiện `notice` khi phiên tự kết thúc

**Kiểm tra:** thử đủ 4 trường hợp trên.
- [x] Đạt (E2E): bỏ trống → 2 lỗi theo field; sai mật khẩu → đúng câu thông báo; đăng nhập bằng SĐT
      ` 09xx.xxx.xxx ` → quay về `/account`
- [ ] Chưa thử trên trình duyệt: `ACCOUNT_DISABLED` (backend đã kiểm tra ở A6, FE chỉ map mã lỗi)

### B4. Điều hướng

- [x] Khi `status = 'checking'`: hiện splash, không redirect (`src/routes/guards.tsx`, `src/components/Splash.tsx`)
- [x] `ProtectedRoute`: `anonymous` → `/login` (nhớ trang đang định vào qua `location.state.from`)
- [x] `GuestRoute`: `authenticated` vào `/login` → trang đã định vào, hoặc trang chủ
- [x] Trang: `/` (`HomePage`, có nút "Kiểm tra phiên"), `/account` (`AccountPage`), `/login`

**Kiểm tra:** vào trang cần đăng nhập khi chưa login thì bị chuyển; login xong quay lại đúng trang.
- [x] Đạt (E2E): `/account` khi chưa đăng nhập → `/login` → đăng nhập → quay lại `/account`

### B5. Interceptor refresh (một tab)

- [x] Bắt 401, bỏ qua `/auth/login`, `/auth/refresh`, `/auth/logout` (`src/auth/sessionRefresh.ts`)
- [x] Mỗi request chỉ retry một lần (cờ `_retried`)
- [x] Chỉ một promise refresh tại một thời điểm; các request 401 khác chờ chung
- [x] Refresh thất bại (`REFRESH_TOKEN_INVALID` / `REFRESH_TOKEN_REUSED`) → `logout` cục bộ → `/login`;
      `REUSED` hiện thêm thông báo "Phiên đăng nhập đã kết thúc vì lý do bảo mật"

**Kiểm tra (tạm đặt `access-ttl=10s` ở BE):**
- [x] Chờ quá 10 giây, gọi `/auth/me` → Network có 401 → refresh → retry 200, người dùng không thấy gì
- [x] Bắn 5 request cùng lúc khi access hết hạn → chỉ có **1** request `/auth/refresh`
- [x] Xoá cookie `refresh_token` bằng tay → lần gọi sau bị đưa về `/login`
- [x] Đạt (E2E, `access-ttl=5s`): Network đúng `GET /auth/me 401, POST /auth/refresh 200, GET /auth/me 200`;
      F5 khi đã hết hạn (StrictMode gọi `/me` 2 lần song song) → 2 × 401 nhưng chỉ 1 refresh; mất cookie refresh →
      `/login` + "Phiên đăng nhập đã hết hạn"; dùng lại refresh token cũ (sau 11 giây) → `/login` + "lý do bảo mật"

### B6. Nhiều tab

- [x] Bọc refresh trong `navigator.locks.request('auth-refresh', ...)` (trình duyệt không có Web Locks thì chạy thẳng,
      grace window 409 của BE lo phần còn lại)
- [x] Sau khi lấy được lock: không refresh nếu tab khác đã refresh sau lúc request bị 401 được gửi, chỉ retry
      request gốc. Cách làm: so thời điểm gửi với `localStorage['auth:lastRefreshAt']` (chỉ là mốc thời gian, không
      phải token) thay vì gọi thử request gốc trước, để không tốn thêm một request
- [x] Gặp 409 `REFRESH_CONFLICT` → chỉ retry request gốc, không logout

**Kiểm tra:** mở 3 tab, chờ access hết hạn, thao tác đồng thời → không tab nào bị đá ra; DB không có family bị
revoke do reuse.
- [x] Đạt (E2E): 3 tab hết hạn cùng lúc rồi F5 đồng thời → cả 3 vẫn đăng nhập, chỉ 1 refresh 200, không có refresh
      nào bị 401 (nên không có reuse)

### B7. Đăng xuất

- [x] Gọi `POST /auth/logout`, kể cả lỗi mạng vẫn xoá state cục bộ
- [x] `BroadcastChannel('auth')` gửi `logout`; các tab khác nghe và chuyển về `/login`
- [x] Sau khi login ở một tab, các tab khác cũng biết (gửi `login`, tab khác gọi lại `/auth/me`)

**Kiểm tra:** logout ở tab A thì tab B về `/login` ngay; cookie bị xoá trong DevTools.
- [x] Đạt (E2E): logout ở tab 1 → cả 3 tab về `/login`, không còn cookie token; login ở tab 2 → mọi tab rời `/login`

### B8. Kiểm thử đầu-cuối

- [x] Login → F5 → vẫn đăng nhập
- [x] Login → đóng trình duyệt → mở lại trong 7 ngày → vẫn đăng nhập (cookie refresh không phải session cookie)
- [x] Access hết hạn khi đang dùng → tự refresh, không gián đoạn
- [x] Logout → nút Back của trình duyệt không vào lại được trang cần đăng nhập
- [x] Không thấy token ở `localStorage`, `sessionStorage` hay `document.cookie` (do HttpOnly)
- [x] Trả `access-ttl` về 15 phút sau khi test xong (test dùng biến môi trường, `application.properties` vẫn `15m`)

---

## Giai đoạn 2 (chưa làm)

- [ ] Giới hạn đăng nhập sai / khoá tạm (D9)
- [ ] `POST /auth/logout-all` và danh sách thiết bị đang đăng nhập (D12)
- [ ] Ghi nhớ đăng nhập (D6)
- [ ] Job dọn `refresh_tokens` hết hạn
- [ ] Endpoint JWKS `/auth/.well-known/jwks.json` + verify JWT tại gateway
- [ ] CORS tại gateway khi FE không còn dùng Vite proxy
