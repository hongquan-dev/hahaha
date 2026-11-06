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

- [ ] Thêm `getCookie(String name)` vào `ApiRequest`: trả `RequestValue` (không bao giờ `null`, `asString()` = `null`
      nếu không có cookie), lấy cookie đầu tiên nếu trùng tên
- [ ] Cập nhật Javadoc của `ApiRequest` và `RequestValue` (thêm "cookie" vào danh sách nguồn giá trị), cùng README của lib
      nếu có liệt kê các getter
- [x] Route **không có** `<schema>` đã được hỗ trợ (`ApiRegistry`: schema optional, interceptor bỏ qua bước kiểm tra body)
- [x] Route **không có** `<validator>` đã được hỗ trợ (`ApiRequestArgumentResolver` tự tạo `ApiRequest` cho controller)
- [ ] `mvn install` lại `food-delivery-libs`, auth-service build được

**Kiểm tra:** auth-service khởi động bình thường, `POST /auth/register` vẫn chạy như cũ.

### A2. Dependency, khoá RSA, cấu hình

- [ ] Thêm `com.nimbusds:nimbus-jose-jwt` vào `fd-auth-service/pom.xml`, kèm comment lý do
- [ ] Sinh cặp khoá RSA 2048 cho dev (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`)
- [ ] Đặt khoá ngoài repo hoặc thêm thư mục khoá vào `.gitignore`; **không commit private key**
- [ ] Thêm property, mỗi nhóm có comment và giá trị mặc định:
  - [ ] `auth.jwt.private-key-location`, `auth.jwt.public-key-location`, `auth.jwt.key-id`, `auth.jwt.issuer`
  - [ ] `auth.jwt.access-ttl=15m`, `auth.refresh.idle-ttl=7d`, `auth.refresh.absolute-ttl=30d`,
        `auth.refresh.reuse-grace=10s`
  - [ ] `auth.cookie.secure=true`
  - [ ] `auth.allowed-origins=http://localhost:5173`
- [ ] Tạo class `AuthProperties` (`@ConfigurationProperties`), báo lỗi **at startup** nếu thiếu khoá hoặc khoá sai

**Kiểm tra:** thiếu hoặc sai file khoá thì app từ chối khởi động với thông báo rõ ràng; đủ khoá thì chạy bình thường.

### A3. Persistence cho refresh token

- [ ] Kiểm tra `common-id-lib` có `EntityType` cho refresh token chưa; thêm nếu thiếu
- [ ] Entity `RefreshToken` (bảng `refresh_tokens`): `id`, `user_id`, `token_hash` (unique), `family_id`,
      `expires_at` (idle, now + 7 ngày), `family_expires_at` (tuyệt đối, lúc login + 30 ngày), `revoked_at`, `replaced_at`, `user_agent`, `ip`, `created_at`
- [ ] Index trên `token_hash` (unique) và `family_id`
- [ ] `RefreshTokenRepository`: `findByTokenHash`, revoke theo `family_id` (một câu `UPDATE`)

**Kiểm tra:** app khởi động, Hibernate tạo bảng `refresh_tokens` đúng cột và index.

### A4. TokenService

- [ ] `issueAccessToken(User)`: JWT RS256, header `kid`, claim `iss`, `sub`, `role`, `iat`, `exp`, `jti`
- [ ] `verifyAccessToken(String)`: phân biệt hợp lệ / hết hạn / sai (chữ ký, `iss`, định dạng)
- [ ] `generateRefreshToken()`: 32 byte từ `SecureRandom`, Base64URL không padding
- [ ] `hash(String)`: SHA-256, dạng hex hoặc Base64
- [ ] Unit test: ký rồi verify OK; token hết hạn; token sửa 1 ký tự; token ký bằng khoá khác

**Kiểm tra:** unit test pass.

### A5. Cookie và kiểm tra Origin

- [ ] `AuthCookies`: dựng `ResponseCookie`
  - [ ] `access_token`: `Path=/`, `HttpOnly`, `Secure` (theo cấu hình), `SameSite=Lax`, `Max-Age`=TTL access
  - [ ] `refresh_token`: `Path=/auth`, `HttpOnly`, `Secure`, `SameSite=Strict`, `Max-Age`= đến mốc **sớm hơn** trong
        `expires_at` và `family_expires_at`
  - [ ] Bản xoá của cả hai cookie (`Max-Age=0`, cùng `Path`)
- [ ] Kiểm tra `Origin` cho POST/PUT/PATCH/DELETE: có `Origin` nhưng không nằm trong whitelist thì 403
      `ORIGIN_NOT_ALLOWED`; không có `Origin` thì cho qua (D7)
- [ ] Chọn chỗ đặt phần kiểm tra Origin (interceptor hoặc filter) và không làm hỏng `register`

**Kiểm tra:** `curl -X POST /auth/register -H "Origin: http://evil.com"` → 403; đúng Origin hoặc không gửi Origin thì
vẫn 201.

### A6. API `POST /auth/login`

- [ ] `schemas/login.json`: `identifier` (string), `password` (string), không cho field lạ
- [ ] DTO `LoginRequest` (record, Javadoc `@param`)
- [ ] `AuthValidator.validateLogin`: bắt buộc, không rỗng, giới hạn độ dài, không truy vấn DB
- [ ] `AuthService.login`:
  - [ ] Nhận dạng và chuẩn hoá `identifier` (dùng lại phần chuẩn hoá của register)
  - [ ] Không có user thì vẫn chạy `BCrypt.matches` với một hash giả (chống dò user qua timing)
  - [ ] Sai → 401 `INVALID_CREDENTIALS`
  - [ ] Đúng mật khẩu nhưng `active=false` → 403 `ACCOUNT_DISABLED`
  - [ ] Tạo family mới, lưu hash của refresh token, ký access token
  - [ ] Log đăng nhập, **không log mật khẩu hay token**
- [ ] `AuthController.login`: trả 200 + `UserDto` + 2 `Set-Cookie` (qua `ResponseHandler.generateResponse(status, headers, body)`)
- [ ] Thêm route vào `api-routes.xml`, có comment

**Kiểm tra (curl / Postman):**
- [ ] Đăng nhập bằng username, email, SĐT (`0912...` và `+84912...`) đều trả 200 và có 2 cookie đúng thuộc tính
- [ ] Sai mật khẩu và user không tồn tại trả **cùng** 401 `INVALID_CREDENTIALS`
- [ ] Thiếu field → 400; thừa field → 400 `INVALID_REQUEST_BODY`
- [ ] User bị khoá → 403 `ACCOUNT_DISABLED`
- [ ] Bảng `refresh_tokens` có 1 dòng, cột `token_hash` khác giá trị cookie

### A7. API `GET /auth/me`

- [ ] Đọc cookie `access_token` → `verifyAccessToken`
- [ ] Thiếu hoặc sai → 401 `UNAUTHENTICATED`; hết hạn → 401 `TOKEN_EXPIRED`
- [ ] Lấy user từ DB; không có hoặc `active=false` → 401 `UNAUTHENTICATED`
- [ ] Trả 200 + `UserDto`
- [ ] Thêm route vào `api-routes.xml`

**Kiểm tra:**
- [ ] Có cookie hợp lệ → 200
- [ ] Không có cookie → 401 `UNAUTHENTICATED`
- [ ] Token bị sửa → 401 `UNAUTHENTICATED`
- [ ] Token hết hạn (tạm đặt `access-ttl=10s`) → 401 `TOKEN_EXPIRED`

### A8. API `POST /auth/refresh`

- [ ] Đọc cookie `refresh_token` → hash → `findByTokenHash`
- [ ] Không có cookie, không tìm thấy, quá `expires_at` hoặc quá `family_expires_at` → 401 `REFRESH_TOKEN_INVALID`
      + xoá cookie
- [ ] User bị khoá → revoke family, 401 `REFRESH_TOKEN_INVALID` + xoá cookie
- [ ] Token đã revoke:
  - [ ] `replaced_at` ≤ 10 giây trước → 409 `REFRESH_CONFLICT`, không revoke gì
  - [ ] Ngược lại → revoke **cả family**, 401 `REFRESH_TOKEN_REUSED` + xoá cookie
- [ ] Hợp lệ: trong **một transaction**, revoke token cũ (`revoked_at`, `replaced_at`) và tạo token mới cùng
      `family_id`, cùng `family_expires_at`, `expires_at` mới = now + 7 ngày
- [ ] Trả 200 + 2 cookie mới, `data = null`
- [ ] Khoá dòng khi đọc (`PESSIMISTIC_WRITE`) để hai request cùng token không cùng rotation thành công
- [ ] Thêm route vào `api-routes.xml`

**Kiểm tra:**
- [ ] Refresh → 200, cookie mới khác cookie cũ, DB có token cũ bị revoke và token mới cùng family
- [ ] Dùng lại token cũ trong vòng 10 giây → 409 `REFRESH_CONFLICT`
- [ ] Dùng lại token cũ sau 10 giây → 401 `REFRESH_TOKEN_REUSED`, mọi token trong family bị revoke
- [ ] Không có cookie → 401 `REFRESH_TOKEN_INVALID`
- [ ] Refresh cookie mới có `Max-Age` ≈ 7 ngày; khi family còn dưới 7 ngày thì `Max-Age` = thời gian còn lại của family
- [ ] Sửa tay `family_expires_at` về quá khứ → refresh trả 401 `REFRESH_TOKEN_INVALID`

### A9. API `POST /auth/logout`

- [ ] Đọc cookie `refresh_token`; nếu tìm thấy thì revoke cả family
- [ ] Luôn trả 200 + 2 cookie xoá (kể cả khi không có hoặc sai token)
- [ ] Thêm route vào `api-routes.xml`

**Kiểm tra:**
- [ ] Logout → 200, cookie bị xoá, family bị revoke trong DB
- [ ] Gọi `/auth/refresh` bằng token cũ sau khi logout → 401
- [ ] Logout khi không có cookie → vẫn 200

### A10. Rà soát backend

- [ ] Mọi class và method mới có comment theo `CLAUDE.md`
- [ ] Không chỗ nào log mật khẩu, access token hay refresh token
- [ ] Thêm Postman collection cho 4 API mới vào `postman/`
- [ ] Toàn bộ kiểm tra A6–A9 chạy lại một lượt đều pass

---

## Phần B: Frontend (React TS)

### B1. Hạ tầng gọi API

- [ ] `vite.config.ts`: proxy `/auth` → `http://localhost:8081`
- [ ] `api.ts`: axios instance với `withCredentials: true`, `baseURL` lấy từ env
- [ ] Kiểu `ApiResponse<T>` khớp BE (`success`, `status`, `code`, `message`, `data`, `errors`, ...)
- [ ] Kiểu `UserDto`

**Kiểm tra:** gọi `POST /auth/login` từ FE thấy cookie trong DevTools → Application → Cookies.

### B2. AuthProvider

- [ ] State `{ status: 'checking' | 'authenticated' | 'anonymous', user: UserDto | null }`
- [ ] Khi mount: gọi `GET /auth/me` → `authenticated` hoặc `anonymous`
- [ ] Hàm `login(identifier, password)`: thành công thì set user từ body
- [ ] Hàm `logout()`
- [ ] Không lưu token hay user vào `localStorage`

**Kiểm tra:** F5 sau khi login vẫn ở trạng thái đăng nhập; xoá cookie bằng tay rồi F5 thì về `anonymous`.

### B3. Trang đăng nhập

- [ ] Form `identifier` + `password`, nút bị khoá khi đang gửi
- [ ] 400: hiện lỗi dưới đúng field theo `errors[].field`
- [ ] 401 `INVALID_CREDENTIALS`: "Sai tài khoản hoặc mật khẩu"
- [ ] 403 `ACCOUNT_DISABLED`: "Tài khoản đã bị khoá"
- [ ] Thành công: chuyển về trang người dùng định vào trước đó, hoặc trang chủ

**Kiểm tra:** thử đủ 4 trường hợp trên.

### B4. Điều hướng

- [ ] Khi `status = 'checking'`: hiện splash, không redirect
- [ ] `ProtectedRoute`: `anonymous` → `/login` (nhớ trang đang định vào)
- [ ] `GuestRoute`: `authenticated` vào `/login` → trang chủ

**Kiểm tra:** vào trang cần đăng nhập khi chưa login thì bị chuyển; login xong quay lại đúng trang.

### B5. Interceptor refresh (một tab)

- [ ] Bắt 401, bỏ qua `/auth/login`, `/auth/refresh`, `/auth/logout`
- [ ] Mỗi request chỉ retry một lần (cờ `_retry`)
- [ ] Chỉ một promise refresh tại một thời điểm; các request 401 khác chờ chung
- [ ] Refresh thất bại (`REFRESH_TOKEN_INVALID` / `REFRESH_TOKEN_REUSED`) → `logout` cục bộ → `/login`;
      `REUSED` hiện thêm thông báo "Phiên đăng nhập đã kết thúc vì lý do bảo mật"

**Kiểm tra (tạm đặt `access-ttl=10s` ở BE):**
- [ ] Chờ quá 10 giây, gọi `/auth/me` → Network có 401 → refresh → retry 200, người dùng không thấy gì
- [ ] Bắn 5 request cùng lúc khi access hết hạn → chỉ có **1** request `/auth/refresh`
- [ ] Xoá cookie `refresh_token` bằng tay → lần gọi sau bị đưa về `/login`

### B6. Nhiều tab

- [ ] Bọc refresh trong `navigator.locks.request('auth-refresh', ...)`
- [ ] Sau khi lấy được lock: retry request gốc trước; chỉ refresh nếu vẫn 401
- [ ] Gặp 409 `REFRESH_CONFLICT` → chỉ retry request gốc, không logout

**Kiểm tra:** mở 3 tab, chờ access hết hạn, thao tác đồng thời → không tab nào bị đá ra; DB không có family bị
revoke do reuse.

### B7. Đăng xuất

- [ ] Gọi `POST /auth/logout`, kể cả lỗi mạng vẫn xoá state cục bộ
- [ ] `BroadcastChannel('auth')` gửi `logout`; các tab khác nghe và chuyển về `/login`
- [ ] Sau khi login ở một tab, các tab khác cũng biết (gửi `login`, tab khác gọi lại `/auth/me`)

**Kiểm tra:** logout ở tab A thì tab B về `/login` ngay; cookie bị xoá trong DevTools.

### B8. Kiểm thử đầu-cuối

- [ ] Login → F5 → vẫn đăng nhập
- [ ] Login → đóng trình duyệt → mở lại trong 7 ngày → vẫn đăng nhập (cookie refresh không phải session cookie)
- [ ] Access hết hạn khi đang dùng → tự refresh, không gián đoạn
- [ ] Logout → nút Back của trình duyệt không vào lại được trang cần đăng nhập
- [ ] Không thấy token ở `localStorage`, `sessionStorage` hay `document.cookie` (do HttpOnly)
- [ ] Trả `access-ttl` về 15 phút sau khi test xong

---

## Giai đoạn 2 (chưa làm)

- [ ] Giới hạn đăng nhập sai / khoá tạm (D9)
- [ ] `POST /auth/logout-all` và danh sách thiết bị đang đăng nhập (D12)
- [ ] Ghi nhớ đăng nhập (D6)
- [ ] Job dọn `refresh_tokens` hết hạn
- [ ] Endpoint JWKS `/auth/.well-known/jwks.json` + verify JWT tại gateway
- [ ] CORS tại gateway khi FE không còn dùng Vite proxy
