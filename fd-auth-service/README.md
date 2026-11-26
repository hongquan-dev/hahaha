# fd-auth-service

Service quản lý **tài khoản** và **phiên đăng nhập** của ứng dụng giao đồ ăn. Chạy ở port `8081`.

Đã làm được:

- **Đăng ký** tài khoản `CUSTOMER`.
- **Đăng nhập** bằng username, email hoặc số điện thoại.
- **Access token (JWT RS256) và refresh token chỉ nằm trong cookie `HttpOnly`**, không bao giờ xuất hiện trong body
  hay trong JavaScript của frontend.
- **Refresh token rotation**: mỗi lần làm mới phiên thì đổi token mới; phát hiện token cũ bị dùng lại (bị đánh cắp)
  thì thu hồi cả phiên.
- **Đăng xuất** thu hồi phiên thật ở server.
- **Chống CSRF** bằng `SameSite` + kiểm tra header `Origin`.

Mục lục:

1. [Chạy service](#1-chạy-service)
2. [Danh sách API](#2-danh-sách-api)
3. [Luồng xử lý một request](#3-luồng-xử-lý-một-request)
4. [Mô hình token và cookie](#4-mô-hình-token-và-cookie)
5. [Nghiệp vụ chi tiết từng API](#5-nghiệp-vụ-chi-tiết-từng-api)
6. [Bảo mật](#6-bảo-mật)
7. [Cơ sở dữ liệu](#7-cơ-sở-dữ-liệu)
8. [Cấu hình](#8-cấu-hình-applicationproperties)
9. [Cấu trúc source code](#9-cấu-trúc-source-code)
10. [Thay đổi ở thư viện dùng chung](#10-thay-đổi-ở-thư-viện-dùng-chung-food-delivery-libs)
11. [Tích hợp phía frontend](#11-tích-hợp-phía-frontend)
12. [Kiểm thử](#12-kiểm-thử)
13. [Bảng mã lỗi](#13-bảng-mã-lỗi)
14. [Giới hạn hiện tại và việc tiếp theo](#14-giới-hạn-hiện-tại-và-việc-tiếp-theo)

---

## 1. Chạy service

**Yêu cầu:**

- Java 21, Maven.
- PostgreSQL ở `localhost:5432` có database `FD_auth_db` (tài khoản trong `application.properties`). Bảng do
  **Flyway** tạo lúc khởi động, xem [mục 7](#7-cơ-sở-dữ-liệu).
- Thư viện dùng chung đã được cài vào `~/.m2`:

```bash
# Repo riêng chứa thư viện dùng chung (common-api-flow-lib, common-id-lib, common-web-lib, ...)
cd <đường-dẫn>/food-delivery-libs && mvn install

# Từ thư mục gốc của repo này (food-delivery-backend), bằng Maven Wrapper (Maven 3.9.16)
./mvnw install -pl common-lib,fd-auth-service -am -DskipTests
```

**Chạy:**

```bash
./mvnw spring-boot:run -pl fd-auth-service
```

Service mở cổng HTTP `8081` (Tomcat), chỉ trên `127.0.0.1` (`server.address`): client đi qua gateway `8090`.

**Chạy test** (không cần DB, không khởi động Spring):

```bash
./mvnw test -pl fd-auth-service
```

**Đổi cấu hình khi chạy** mà không sửa file, ví dụ đặt access token sống 10 giây để test hết hạn:

```bash
AUTH_JWT_ACCESSTTL=10s ./mvnw spring-boot:run -pl fd-auth-service
```

> Cấu hình sai (thiếu khoá, khoá hỏng, TTL không hợp lệ, origin sai định dạng...) thì service **từ chối khởi động** và
> báo đúng tên property bị sai. Xem [mục 8](#8-cấu-hình-applicationproperties).

---

## 2. Danh sách API

| Method | Path | Body | Thành công | Mô tả |
|---|---|---|---|---|
| `POST` | `/auth/register` | `username`, `password`, `email`, `phoneNumber` | 201 + user | Tạo tài khoản `CUSTOMER`, **không** đăng nhập luôn |
| `POST` | `/auth/login` | `identifier`, `password` | 200 + user + 2 cookie | Đăng nhập, tạo phiên mới |
| `GET` | `/auth/me` | (không) | 200 + user | User của cookie `access_token` |
| `POST` | `/auth/refresh` | (không) | 200 + 2 cookie mới | Làm mới phiên (rotation) |
| `POST` | `/auth/logout` | (không) | 200 + xoá 2 cookie | Kết thúc phiên; luôn 200 |
| `GET` | `/auth/.well-known/jwks.json` | (không) | 200 + JWK Set | Public key để kiểm tra access token (xem 4.1) |

Mọi response (trừ JWKS, xem 4.1) đều theo định dạng chung `ApiResponse` của `common-web-lib`:

```json
{
  "success": true,
  "status": 200,
  "code": "SUCCESS",
  "message": "Success",
  "data": {
    "id": 17939522301001,
    "username": "quan.nguyen",
    "email": "quan@gmail.com",
    "phoneNumber": "+84912345678",
    "role": "CUSTOMER",
    "active": true,
    "createdAt": "2026-11-06T15:03:51.123"
  },
  "timestamp": "2026-11-06T08:03:51.181Z"
}
```

Lỗi có `success: false`, `code` là mã lỗi nghiệp vụ, và `errors` (nếu có) liệt kê lỗi theo từng field:

```json
{
  "success": false,
  "status": 400,
  "code": "INVALID_LOGIN_INFO",
  "message": "Login info is invalid",
  "data": null,
  "errors": [
    { "field": "identifier", "message": "Username, email or phone number is required" },
    { "field": "password", "message": "Password is required" }
  ],
  "path": "/auth/login"
}
```

cURL từng API (copy, dán vào thanh URL của Postman): [`postman/fd-auth-service/`](../postman/fd-auth-service/).
Chạy theo thứ tự Register → Login → Me → Refresh → Me → Logout → Me; Postman tự lưu và gửi lại cookie.

---

## 3. Luồng xử lý một request

Mọi request đi qua các lớp dưới đây, mỗi lớp chỉ làm đúng phần của nó (quy ước chung của dự án, xem `CLAUDE.md`):

```
Request
  │
  ├─ 1. OriginCheckFilter          POST/PUT/PATCH/DELETE có Origin lạ        → 403 ORIGIN_NOT_ALLOWED
  ├─ 2. RequestCachingFilter (lib) đọc và cache body, giới hạn 2MB
  ├─ 3. api-routes.xml             API chưa khai báo                          → 404 API_NOT_FOUND
  ├─ 4. JSON Schema                thiếu field / sai kiểu / field lạ          → 400 INVALID_REQUEST_BODY
  ├─ 5. AuthValidator              giá trị từng field (không truy vấn DB)     → 400 INVALID_*_INFO + errors
  ├─ 6. AuthController             lấy dữ liệu từ ApiRequest, gọi service, gắn cookie
  └─ 7. Service                    nghiệp vụ, mọi kiểm tra cần DB             → BusinessException
```

| API | Schema | Validator | Service |
|---|---|---|---|
| `POST /auth/register` | `schemas/register.json` | `validateRegister` | `AuthService.register` |
| `POST /auth/login` | `schemas/login.json` | `validateLogin` | `AuthService.login` → `SessionService.start` |
| `GET /auth/me` | (không) | (không) | `AuthService.currentUser` |
| `POST /auth/refresh` | (không) | (không) | `SessionService.refresh` |
| `POST /auth/logout` | (không) | (không) | `SessionService.end` |

`BusinessException` ném ra từ service được `GlobalExceptionHandler` (`common-web-lib`) chuyển thành response lỗi.
Riêng `/auth/refresh` bắt lỗi 401 ngay trong controller để gắn thêm header xoá cookie.

---

## 4. Mô hình token và cookie

### 4.1. Hai loại token

| | Access token | Refresh token |
|---|---|---|
| Dạng | JWT ký **RS256** | Chuỗi ngẫu nhiên 32 byte (Base64URL, 43 ký tự) |
| Thời hạn | 15 phút | Idle 7 ngày, tuyệt đối 30 ngày (xem 4.3) |
| Lưu ở server | Không lưu (stateless) | Bảng `refresh_tokens`, **chỉ lưu SHA-256** |
| Dùng để | Chứng minh "tôi là user X" với mọi API | Xin access token mới khi access hết hạn |
| Thu hồi được? | Không; hết hạn sau tối đa 15 phút | Có, ngay lập tức |

**Payload của access token**: không có dữ liệu cá nhân, vì ai cầm token cũng giải mã đọc được payload.

```json
// header
{ "alg": "RS256", "typ": "JWT", "kid": "fd-auth-dev-1" }
// payload
{ "iss": "fd-auth-service", "sub": "17939522301001", "role": "CUSTOMER",
  "iat": 1793952231, "exp": 1793953131, "jti": "96f6a74d-584a-4a78-9637-54259563516b" }
```

| Claim | Ý nghĩa |
|---|---|
| `sub` | ID user |
| `role` | Quyền, dùng để phân quyền sau này |
| `iss` | Ai phát hành; bên kiểm tra từ chối `iss` khác |
| `iat` / `exp` | Thời điểm tạo / hết hạn |
| `jti` | Mã riêng của từng token, để lần theo trong log |
| `kid` (header) | Token được ký bằng khoá nào, để chọn đúng public key khi xoay khoá |

**Vì sao RS256 (cặp khoá) chứ không HS256 (một secret):**

- **Private key** chỉ auth-service giữ, dùng để **ký**: chỉ auth-service tạo được token.
- **Public key** công khai, dùng để **kiểm tra chữ ký**. Gateway và các service khác lấy public key qua JWKS
  (`GET /auth/.well-known/jwks.json`) để tự kiểm tra token mà không cần gọi auth-service và không thể tự tạo token.

**JWKS** (`JwksController`): trả đúng định dạng chuẩn JWK Set (RFC 7517), **không** bọc trong `ApiResponse`, vì thư
viện JWT đọc đúng định dạng này. Path nằm trong `api-flow.exclude-paths` nên không có route trong `api-routes.xml`.

```json
{"keys":[{"kty":"RSA","kid":"fd-auth-dev-1","use":"sig","alg":"RS256","n":"zO98l3bu...","e":"AQAB"}]}
```

- Chỉ có tham số public (`n`, `e`), không bao giờ có tham số private (`d`, `p`, `q`, `dp`, `dq`, `qi`).
- Bên kiểm tra chọn key có `kid` trùng với `kid` trong header của token.
- `Content-Type: application/jwk-set+json`, `Cache-Control: max-age=300, public`: bên kiểm tra được cache 5 phút.
  Khi xoay khoá, phải công bố khoá mới ít nhất 5 phút trước khi bắt đầu ký bằng nó.

### 4.2. Hai cookie

```
Set-Cookie: access_token=eyJ...;  Path=/;     Max-Age=900;    HttpOnly; Secure; SameSite=Lax
Set-Cookie: refresh_token=Snf5...; Path=/auth; Max-Age=604800; HttpOnly; Secure; SameSite=Strict
```

| Thuộc tính | Tác dụng |
|---|---|
| `HttpOnly` | JavaScript không đọc được: dù trang bị XSS, mã độc cũng không lấy được token |
| `Secure` | Chỉ gửi qua HTTPS (trình duyệt coi `http://localhost` là an toàn nên dev vẫn chạy) |
| `SameSite=Lax` (access) | Không gửi khi trang web khác gọi POST/fetch tới API |
| `SameSite=Strict` (refresh) | Không bao giờ gửi khi request xuất phát từ trang khác |
| `Path=/auth` (refresh) | Refresh token sống lâu nên chỉ gửi tới `/auth/...`, các service khác không bao giờ thấy nó |
| `Max-Age` (refresh) | Tới mốc **sớm hơn** giữa idle expiry và family expiry, để cookie không sống lâu hơn token trong DB |
| Không có `Domain` | Cookie chỉ thuộc đúng host đã set |

Mọi cookie được dựng ở một chỗ duy nhất là `web/AuthCookies`. Xoá cookie phải dùng **đúng `Path` cũ**, nếu không
trình duyệt coi là cookie khác và cookie cũ vẫn còn.

### 4.3. Phiên đăng nhập = một "family" refresh token

```
Login      → token T1 (family F = id của T1)
Refresh    → T1 bị thu hồi (replaced_at = now), tạo T2 cùng family F
Refresh    → T2 bị thu hồi, tạo T3 cùng family F
Logout     → thu hồi mọi token của family F
```

- **Mỗi lần login là một family riêng.** Đăng xuất trên máy này không ảnh hưởng máy khác.
- **Hai mốc hết hạn:**
  - **Idle 7 ngày** (`expires_at`): mỗi token mới sau refresh lại có 7 ngày, nên người dùng thường xuyên không phải
    đăng nhập lại; phiên bỏ không quá 7 ngày thì tự hết hạn.
  - **Tuyệt đối 30 ngày** (`family_expires_at`): tính từ lúc login, không bao giờ được kéo dài; quá mốc này luôn phải
    đăng nhập lại.

---

## 5. Nghiệp vụ chi tiết từng API

### 5.1. `POST /auth/register`

1. **Schema**: đủ 4 field kiểu string, không có field lạ.
2. **Chuẩn hoá** (`RegisterRequest.normalized()` → `IdentifierNormalizer`):
   - `username`, `email`: cắt khoảng trắng, chữ thường. `" Quan@Gmail.com "` → `"quan@gmail.com"`. Khoảng trắng gồm cả
     loại Unicode như U+00A0 (dính vào khi copy-paste) mà `trim()` / `strip()` của Java giữ lại.
   - `phoneNumber`: bỏ dấu cách, `.`, `-`, `()`, đổi `0xxx` / `84xxx` thành `+84xxx`. `"0912.345.678"` → `"+84912345678"`.
   - `password`: **không bao giờ** bị cắt hay sửa.
3. **Validator** (`validateRegister`), gom mọi lỗi rồi trả một lần → 400 `INVALID_REGISTER_INFO`:
   - `username`: 3–30 ký tự `a-z 0-9 . _`, **bắt đầu bằng chữ cái** (để không trùng dạng với SĐT khi đăng nhập).
   - `password`: 8–64 ký tự, tối đa 72 byte UTF-8 (BCrypt bỏ qua phần sau byte 72), có chữ hoa, chữ thường, chữ số và
     ký tự đặc biệt.
   - `email`: đúng định dạng, tối đa 254 ký tự; dấu chấm chỉ nằm giữa các ký tự (không `.quan@`, `quan..nguyen@`), tên
     miền không có `-` ở đầu/cuối nhãn.
   - `phoneNumber`: số di động Việt Nam đầu `03 05 07 08 09`.
4. **Service** (`AuthService.register`):
   - Trùng username / email / SĐT → 409 `USER_ALREADY_EXISTS`, `errors` liệt kê từng field bị trùng.
   - Băm mật khẩu bằng BCrypt (cost 10) **trước** khi chạm vào DB, và hàm không mở transaction riêng: BCrypt (~100 ms)
     không giữ kết nối nào của pool (login cũng vậy, nhờ `spring.jpa.open-in-view=false`). Role luôn là `CUSTOMER`, ID
     sinh bằng `IdUtils.generate(EntityType.USER)`.
   - Hai request cùng lúc vượt qua bước kiểm tra trùng thì ràng buộc unique của DB chặn lại, vẫn trả 409 kèm lỗi đúng
     field (lấy từ tên ràng buộc, vd. `uk_users_username` → `username`). Lỗi DB khác (NOT NULL, độ dài cột...) không bị
     đổi thành 409 mà để lộ ra thành 500 và được ghi log.
5. Trả 201 + user. **Không** set cookie: client gọi login sau đó.

### 5.2. `POST /auth/login`

```json
{ "identifier": "0912.345.678", "password": "Abc@12345" }
```

1. **Schema**: đúng 2 field string `identifier`, `password`.
2. **Validator** (`validateLogin`), chỉ kiểm tra tối thiểu → 400 `INVALID_LOGIN_INFO`:
   - `identifier`: bắt buộc, tối đa 254 ký tự.
   - `password`: bắt buộc, tối đa 72 byte.
   - **Không** áp luật độ mạnh mật khẩu của register: nếu sau này luật chặt hơn, tài khoản cũ vẫn đăng nhập được.
3. **Nhận dạng `identifier`** (`AuthService.findByIdentifier`):

   | Người dùng nhập | Hiểu là | Tìm theo |
   |---|---|---|
   | Có `@` | Email | `email` đã chuẩn hoá |
   | Chuẩn hoá ra đúng dạng SĐT `+84...` | SĐT | `phone_number`; **không thấy thì tìm tiếp theo username**, cho tài khoản tạo trước khi username bắt buộc bắt đầu bằng chữ cái (ví dụ `0912345678`) |
   | Còn lại | Username | `username` chữ thường |

4. **Khoá tạm sau nhiều lần sai** (`LoginThrottleService`), kiểm tra **trước** mật khẩu:
   - Sai 10 lần trong 15 phút (`auth.login.*`) → khoá 5 phút: mọi lần đăng nhập, **kể cả đúng mật khẩu**, nhận 429
     `TOO_MANY_LOGIN_ATTEMPTS`.
   - Đếm theo **tài khoản** khi identifier khớp một tài khoản (gõ username, email hay SĐT đều chung một bộ đếm), ngược
     lại theo **identifier đã chuẩn hoá**. Hai trường hợp bị khoá y hệt nhau, nên khoá không lộ tài khoản nào tồn tại.
   - Bảng `login_failures` chỉ lưu SHA-256 của khoá đếm, không bao giờ lưu identifier (có thể là mật khẩu gõ nhầm ô).
   - Đúng mật khẩu thì xoá bộ đếm. Khoá ngắn có chủ đích: ai cũng có thể cố tình gõ sai để khoá tài khoản người khác.
     Lớp chặn theo IP nằm ở gateway (filter `RateLimit`).
5. **Kiểm tra mật khẩu**:
   - **Không có user hoặc sai mật khẩu → cùng 401 `INVALID_CREDENTIALS`**, cùng câu thông báo, không có `errors`:
     không lộ tài khoản nào tồn tại. Lần sai được đếm cho bước 4.
   - **Khi không có user vẫn chạy BCrypt với một hash giả** (tạo lúc khởi động, cùng cost), để thời gian phản hồi
     bằng với trường hợp sai mật khẩu (~70–100 ms). Không làm vậy thì kẻ dò có thể đoán tài khoản tồn tại qua tốc độ.
   - Đúng mật khẩu nhưng `active=false` → 403 `ACCOUNT_DISABLED`. Chỉ báo **sau** khi mật khẩu đúng, nên kẻ dò không
     biết được gì.
6. **Tạo phiên** (`SessionService.start`): lưu một dòng `refresh_tokens` (family mới, `family_id = id`, idle 7 ngày,
   tuyệt đối 30 ngày, `user_agent`, `ip`), ký access token.
7. Trả 200 + user trong body + 2 header `Set-Cookie`. **Token không bao giờ có trong body.**

### 5.3. `GET /auth/me`

Frontend không đọc được cookie `HttpOnly`, nên đây là cách duy nhất để biết "mình có đang đăng nhập không".

1. Đọc cookie `access_token` → `TokenService.verifyAccessToken` kiểm tra theo thứ tự:
   1. Đúng định dạng JWT.
   2. `alg` phải là RS256. Không tin `alg` do token tự khai, nên chặn được `alg: none` và token HS256 ký bằng public key.
   3. `kid` đúng khoá hiện tại.
   4. Chữ ký khớp public key: phát hiện token giả hoặc bị sửa (ví dụ đổi `CUSTOMER` thành `ADMIN`).
   5. `iss` = `fd-auth-service`, `sub` là số.
   6. `exp` còn hạn, cho lệch đồng hồ **30 giây** (clock skew).
   - Hạn được kiểm tra **cuối cùng**, nên chỉ token thật mới có thể bị báo "hết hạn"; token giả đã quá hạn vẫn là "không hợp lệ".
2. Kết quả:
   - Hết hạn → 401 `TOKEN_EXPIRED` (frontend nên gọi refresh).
   - Thiếu, sai, user không còn hoặc bị khoá → 401 `UNAUTHENTICATED`.
   - Hợp lệ → đọc user **từ DB** (để dữ liệu luôn mới) → 200.

### 5.4. `POST /auth/refresh`

Không có body; dùng cookie `refresh_token` (chỉ gửi tới `/auth/...`).

```
băm cookie (SHA-256) → tìm dòng refresh_tokens, KHOÁ DÒNG (SELECT ... FOR UPDATE)
│
├─ không có cookie / không tìm thấy                         → 401 REFRESH_TOKEN_INVALID + xoá cookie
├─ token đã bị thu hồi:
│    ├─ thu hồi do logout (replaced_at = null)              → 401 REFRESH_TOKEN_INVALID + xoá cookie
│    ├─ bị thay ≤ 10 giây trước (2 tab refresh cùng lúc)    → 409 REFRESH_CONFLICT, KHÔNG xoá cookie
│    └─ bị thay > 10 giây trước (token bị đánh cắp)         → thu hồi CẢ family, 401 REFRESH_TOKEN_REUSED + xoá cookie
├─ quá expires_at hoặc quá family_expires_at                → 401 REFRESH_TOKEN_INVALID + xoá cookie
├─ user không còn / bị khoá                                 → thu hồi family, 401 REFRESH_TOKEN_INVALID + xoá cookie
└─ hợp lệ: thu hồi token cũ (revoked_at = replaced_at = now),
           tạo token mới cùng family (idle = now + 7 ngày, giữ nguyên family_expires_at),
           ký access token mới                              → 200 + 2 cookie mới
```

| Chi tiết | Lý do |
|---|---|
| **Khoá dòng** khi đọc | 2 request cùng token: request thứ hai chờ request đầu rotation xong, rồi thấy token đã bị thay → 409, thay vì cả hai cùng rotation thành công. Đã kiểm tra: 5 request song song → 1 × 200, 4 × 409 |
| **Grace window 10 giây** (409) | Nhiều tab cùng hết hạn và cùng refresh là chuyện bình thường, không phải tấn công. Lúc đó trình duyệt đã có cookie mới từ tab kia, nên **không được xoá cookie** |
| **Reuse sau 10 giây → thu hồi cả family** | Token đã bị thay mà vẫn có người dùng, nghĩa là có một bản sao bị lộ. Không biết ai là chủ thật, nên kết thúc cả phiên |
| `@Transactional(noRollbackFor = BusinessException.class)` | Việc thu hồi family xảy ra **trước** khi ném 401; nếu rollback thì việc thu hồi bị huỷ |

### 5.5. `POST /auth/logout`

- Có cookie `refresh_token` hợp lệ → thu hồi **cả family** (một câu `UPDATE`).
- **Luôn trả 200** kèm 2 header xoá cookie (`Max-Age=0`), kể cả khi không có cookie hoặc cookie sai: frontend không
  bao giờ bị kẹt ở trạng thái nửa đăng xuất.
- Access token đã phát vẫn có hiệu lực tối đa 15 phút về lý thuyết, nhưng cookie đã bị xoá nên trình duyệt không gửi nữa.

---

## 6. Bảo mật

| Mối đe doạ | Cách chống |
|---|---|
| XSS lấy cắp token | Token chỉ nằm trong cookie `HttpOnly`; không có trong body, `localStorage` hay JS |
| CSRF (trang khác gửi request kèm cookie) | `SameSite` + `OriginCheckFilter` (xem dưới) |
| Giả mạo / sửa access token | Chữ ký RS256; chỉ auth-service có private key; thuật toán cố định RS256 |
| Lộ refresh token | Rotation + phát hiện reuse → thu hồi cả phiên; DB chỉ lưu SHA-256 nên lộ DB cũng không dùng được token |
| Dò tài khoản tồn tại | Một mã lỗi chung `INVALID_CREDENTIALS` + thời gian phản hồi như nhau (BCrypt với hash giả); khoá tạm áp dụng y hệt cho tài khoản có và không có |
| Dò mật khẩu | Gateway giới hạn 30 request/phút mỗi IP cho login và register (429 `TOO_MANY_REQUESTS`); auth khoá tạm 5 phút sau 10 lần sai trong 15 phút (429 `TOO_MANY_LOGIN_ATTEMPTS`) |
| Lộ bảng `users` | Mật khẩu băm BCrypt cost 10 |
| Mật khẩu bị BCrypt cắt ngầm | Giới hạn 72 byte UTF-8 ở cả register và login |
| Lộ thông tin qua log | Không bao giờ log mật khẩu, access token hay refresh token (đã kiểm tra trên log của cả lượt test); email và SĐT chỉ log dạng che bớt (`LogMasker`), vd. `q***@gmail.com`, `+8491****678`; identifier của lần đăng nhập chỉ giữ 2 ký tự đầu (`qu***`), vì người dùng có thể gõ nhầm mật khẩu vào ô tên đăng nhập |
| Cấu hình sai lúc deploy | Kiểm tra lúc khởi động: thiếu hoặc hỏng thì không khởi động |

**`OriginCheckFilter`** (`web/OriginCheckFilter`, chạy trước mọi filter khác, áp dụng mọi path):

```
GET / HEAD / OPTIONS                       → cho qua
POST / PUT / PATCH / DELETE:
    không có header Origin                 → cho qua (không phải trình duyệt: Postman, curl, server khác)
    Origin nằm trong auth.allowed-origins  → cho qua (so khớp không phân biệt hoa thường)
    Origin khác, kể cả "null"              → 403 ORIGIN_NOT_ALLOWED + log warn
```

Trình duyệt **luôn** gửi `Origin` với các method này, nên request không có `Origin` không thể là CSRF.

---

## 7. Cơ sở dữ liệu

### Bảng `users` (entity `User`)

| Cột | Ghi chú |
|---|---|
| `id` | 14 chữ số từ `IdUtils` (`EntityType.USER`) |
| `username` | Unique, chữ thường |
| `password` | BCrypt hash |
| `email` | Unique, chữ thường |
| `phone_number` | Unique, dạng `+84...` (ràng buộc thêm ở `V2`) |
| `role` | `CUSTOMER` khi đăng ký |
| `active` | `false` = bị khoá |
| `created_at`, `updated_at` | Tự đặt bởi JPA callback |

### Bảng `refresh_tokens` (entity `RefreshToken`)

| Cột | Ghi chú |
|---|---|
| `id` | `IdUtils.generate(EntityType.REFRESH_TOKEN)` |
| `user_id` | ID user; cột số thường, không phải quan hệ JPA (refresh không cần tải `User`) |
| `token_hash` | SHA-256 hex (64 ký tự) của token; **unique** |
| `family_id` | = `id` của token đầu tiên của phiên (tạo lúc login) |
| `expires_at` | Hạn idle: lúc tạo + 7 ngày |
| `family_expires_at` | Hạn tuyệt đối của phiên: lúc login + 30 ngày, chép nguyên qua mọi lần refresh |
| `revoked_at` | `null` = còn dùng được |
| `replaced_at` | Thời điểm bị thay khi rotation; `null` nếu thu hồi do logout |
| `user_agent` | Header `User-Agent`, cắt ở 512 ký tự |
| `ip` | IP client (kết nối trực tiếp) |
| `created_at` | Tự đặt |

- Index: unique `token_hash`; `family_id` (thu hồi cả phiên); `user_id` (cho `logout-all` sau này).
- **Dọn dẹp** (`DataCleanupService`, mặc định mỗi giờ): xoá **cả phiên** khi phiên đã chết quá 7 ngày. Mỗi token hết
  dùng được tại `LEAST(family_expires_at, COALESCE(revoked_at, expires_at))`; phiên chết khi token cuối cùng hết dùng
  được. Phiên còn sống luôn có một token dùng được, nên các token đã bị thay của nó (cần để phát hiện token bị đánh
  cắp) không bao giờ bị xoá. Cùng job xoá dòng `login_failures` đã hết khoá và hết cửa sổ đếm.
- Thời gian dùng `Instant` (`timestamp with time zone`), không phụ thuộc múi giờ của server hay DB.
- **Vì sao SHA-256 mà không phải BCrypt như mật khẩu:** token là 256 bit ngẫu nhiên nên không thể dò; và SHA-256 luôn
  cho cùng kết quả nên tìm được bằng index (BCrypt có salt ngẫu nhiên, không tìm được).

### Database migration (Flyway)

Schema do Flyway quản lý, **không** để Hibernate tự sửa:

- File migration nằm ở `src/main/resources/db/migration`, đặt tên `V<số>__<mô_tả>.sql`, ví dụ
  `V1__create_users_and_refresh_tokens.sql`. Lúc khởi động Flyway chạy các file chưa chạy theo thứ tự version và ghi
  lại vào bảng `flyway_schema_history`.
- Hibernate chạy với `ddl-auto=validate`: chỉ kiểm tra bảng/cột khớp entity, lệch thì service **từ chối khởi động**.
- Hiện có: `V1` tạo `users` và `refresh_tokens`; `V2` thêm ràng buộc unique cho `users.phone_number`; `V3` tạo
  `login_failures` (khoá đếm là SHA-256 64 ký tự, `failure_count`, `window_started_at`, `locked_until`).
- **Đổi schema** (thêm cột, thêm bảng, index...): tạo file mới với version kế tiếp (`V4__add_xxx.sql`), sửa entity cho
  khớp. **Không sửa file đã chạy**: Flyway so checksum và từ chối khởi động nếu file cũ bị đổi.

**DB cũ do Hibernate `ddl-auto=update` tạo** (có bảng nhưng chưa có `flyway_schema_history`): Flyway báo
`Found non-empty schema(s) ... but no schema history table` và dừng. Chọn một trong hai:

```bash
# Cách 1 (khuyên dùng ở dev): xoá DB, tạo lại; Flyway tạo bảng từ V1 khi chạy service
psql -U postgres -c 'DROP DATABASE "FD_auth_db"' -c 'CREATE DATABASE "FD_auth_db"'

# Cách 2: giữ dữ liệu, đánh dấu DB hiện tại là đã ở V1 (Flyway sẽ không chạy V1 nữa)
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=1"
```

> Cách 2 giữ nguyên các khác biệt của DB cũ so với V1 (tên constraint do Hibernate tự sinh, bảng thừa...).

---

## 8. Cấu hình (`application.properties`)

Mọi property `auth.*` được bind vào `config/properties/AuthProperties` lúc khởi động, có comment chi tiết ngay trong file.

| Property | Mặc định | Ý nghĩa |
|---|---|---|
| `auth.jwt.private-key` | (bắt buộc) | Private key RSA (PKCS#8, phần Base64 của PEM) để **ký** access token |
| `auth.jwt.public-key` | (bắt buộc) | Public key RSA (X.509) để **kiểm tra** chữ ký |
| `auth.jwt.key-id` | (bắt buộc) | `kid` ghi vào header token; đổi khi đổi cặp khoá |
| `auth.jwt.issuer` | (bắt buộc) | `iss` của token, hiện là `fd-auth-service` |
| `auth.jwt.access-ttl` | `15m` | Hạn access token và cookie của nó |
| `auth.refresh.idle-ttl` | `7d` | Hạn của mỗi refresh token |
| `auth.refresh.absolute-ttl` | `30d` | Hạn tối đa của một phiên; phải ≥ `idle-ttl` |
| `auth.refresh.reuse-grace` | `10s` | Grace window cho 2 tab refresh cùng lúc (409) |
| `auth.cookie.secure` | `true` | Thuộc tính `Secure` của cookie |
| `auth.login.max-failures` | `10` | Số lần đăng nhập sai trong `failure-window` thì khoá tạm (`LoginThrottleProperties`) |
| `auth.login.failure-window` | `15m` | Khoảng thời gian đếm số lần sai, tính từ lần sai đầu |
| `auth.login.lock-duration` | `5m` | Thời gian khoá; trong lúc khoá, đúng mật khẩu cũng nhận 429 |
| `auth.cleanup.cron` | `0 0 * * * *` | Lịch chạy job dọn (cron 6 trường, có giây; mặc định mỗi giờ); `-` để tắt |
| `auth.cleanup.retention` | `7d` | Giữ phiên đã chết bao lâu trước khi xoá (`CleanupProperties`) |
| `auth.cleanup.batch-size` | `500` | Số phiên / dòng xoá mỗi câu lệnh |
| `server.address` | mọi địa chỉ (đặt `127.0.0.1`) | Chỉ máy này gọi được HTTP; client đi qua gateway. Deploy: `SERVER_ADDRESS=0.0.0.0`, không publish port |
| `auth.allowed-origins` | (bắt buộc) | Whitelist `Origin`, cách nhau dấu phẩy, dạng `scheme://host[:port]`, ví dụ `http://localhost:5173` |

**Kiểm tra lúc khởi động** (sai thì không khởi động, thông báo nêu đúng tên property):

- Thiếu giá trị bắt buộc; TTL ≤ 0; `idle-ttl` > `absolute-ttl`.
- Origin có `/` cuối, có path, là `*` hay không phải `http`/`https`.
- Khoá không parse được, ngắn hơn 2048 bit, hoặc private key và public key **không cùng một cặp**
  (`config/security/JwtKeyConfig`).

> **Khoá RSA đặt trong `application.properties`**, file này không có trên git: mỗi máy tạo từ
> `application.properties.example` và tự sinh khoá (xem mục "Tạo file cấu hình trước khi chạy" trong README gốc).
> Khi deploy thật: đọc khoá từ secret / biến môi trường, đổi `auth.jwt.key-id`.

**Sinh cặp khoá RSA** (2048 bit, private key PKCS#8, public key X.509), rồi dán phần Base64 giữa hai dòng
`BEGIN`/`END` vào `auth.jwt.private-key` / `auth.jwt.public-key` (dòng `BEGIN`/`END` và xuống dòng được bỏ qua):

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
```

---

## 9. Cấu trúc source code

```
src/main/java/com/fooddelivery/auth/
├── AuthApplication.java          entry point, @ConfigurationPropertiesScan
├── common/
│   ├── model/                    entity JPA
│   │   ├── User.java
│   │   └── RefreshToken.java
│   └── util/
│       ├── IdentifierNormalizer  chuẩn hoá username/email/SĐT, dùng chung cho register và login
│       └── LogMasker             che bớt email/SĐT trước khi ghi log
├── config/
│   ├── common/ClockConfig        bean Clock (UTC); test thay bằng đồng hồ cố định
│   ├── properties/AuthProperties bind + kiểm tra auth.*
│   ├── security/JwtKeyConfig     parse cặp khoá RSA thành bean RSAKey (nimbus)
│   ├── security/PasswordConfig   BCryptPasswordEncoder
│   └── web/WebFilterConfig       đăng ký OriginCheckFilter (order HIGHEST_PRECEDENCE + 5)
├── controller/
│   ├── AuthController            5 API; gắn/xoá cookie
│   └── JwksController            GET /auth/.well-known/jwks.json (public key, ngoài API flow)
├── dto/                          RegisterRequest, LoginRequest (body), UserDto (response)
├── repository/                   UserRepository, RefreshTokenRepository (findByTokenHashForUpdate, revokeFamily)
├── service/
│   ├── auth/                     tài khoản
│   │   ├── AuthService           register, login, currentUser
│   │   └── LoginResult           user + tokens trả cho controller
│   ├── session/                  phiên đăng nhập
│   │   ├── SessionService        start, refresh (rotation), end
│   │   └── IssuedTokens          access + refresh token + 2 mốc hết hạn
│   └── token/                    token
│       ├── TokenService          ký/kiểm tra JWT, sinh/băm refresh token
│       └── AccessTokenVerification  VALID / EXPIRED / INVALID
├── validation/
│   └── AuthValidator             validateRegister, validateLogin
└── web/
    ├── AuthCookies               dựng/xoá 2 cookie, tên cookie là hằng số
    └── OriginCheckFilter         chống CSRF

src/main/resources/
├── api-routes.xml                khai báo 5 route (5 API auth)
├── application.properties
└── schemas/                      register.json, login.json
```

Phụ thuộc giữa các service một chiều: `AuthService` → `SessionService` → `TokenService`.

---

## 10. Thay đổi ở thư viện dùng chung (`food-delivery-libs`)

| Thư viện | Thay đổi | Dùng ở |
|---|---|---|
| `common-api-flow-lib` | `ApiRequest.getCookie(name)`: đọc cookie, trả `RequestValue` (không bao giờ `null`) | `/me`, `/refresh`, `/logout` |
| `common-api-flow-lib` | `ApiRequest.getRemoteAddr()`: IP client kết nối trực tiếp (không đọc `X-Forwarded-For` vì client tự giả được) | Lưu `ip` của phiên |
| `common-id-lib` | `EntityType.REFRESH_TOKEN(6)` | ID của `refresh_tokens` |

README của từng thư viện đã được cập nhật tương ứng. Sửa lib xong phải `mvn install` lại lib thì service mới thấy.

---

## 11. Tích hợp phía frontend

Frontend mẫu (React + TS + Vite): `Food Delivery/hehehe`. Các điểm bắt buộc khi gọi auth-service:

1. **Gửi kèm cookie:** axios `withCredentials: true` (fetch: `credentials: 'include'`).
2. **Không lưu token** ở đâu cả; trạng thái đăng nhập lấy từ `GET /auth/me` khi mở app, và từ body của login.
3. **Dev dùng Vite proxy** `/api/**` → gateway `http://localhost:8090` (bỏ tiền tố `/api`) → auth-service: cùng
   origin nên không cần CORS, và `Origin` là `http://localhost:5173` (đã nằm trong `auth.allowed-origins`; gateway
   chuyển nguyên header này).
4. **Interceptor refresh:** mọi 401 (trừ `/auth/login`, `/auth/refresh`, `/auth/logout`) thì refresh **một lần** rồi
   gửi lại request.
   - Mỗi tab chỉ có một lần refresh tại một thời điểm; các request 401 cùng lúc chờ chung.
   - Giữa các tab: `navigator.locks` + mốc `localStorage['auth:lastRefreshAt']` (chỉ là thời điểm, không phải token).
     Tab nào đã thấy tab khác refresh xong thì chỉ gửi lại request.
   - 409 `REFRESH_CONFLICT` → chỉ gửi lại request, **không** đăng xuất.
   - 401 khi refresh → đăng xuất ở phía client; `REFRESH_TOKEN_REUSED` thì báo "phiên kết thúc vì lý do bảo mật".
5. **Nhiều tab:** `BroadcastChannel('auth')` báo `login` / `logout` để các tab khác cập nhật theo.

---

## 12. Kiểm thử

### Unit test: 314 test, `./mvnw test -pl fd-auth-service`

| Test | Số test | Nội dung chính |
|---|---:|---|
| `TokenServiceTest` | 15 | Ký → kiểm tra OK; claim đúng; còn trong / quá clock skew; sửa payload; ký bằng khoá khác; token giả đã quá hạn vẫn `INVALID`; `alg: none`; HS256 ký bằng public key; sai `iss`, `kid`; chuỗi rác/rỗng/`null`; refresh token 43 ký tự, 1000 lần không trùng; vector SHA-256 chuẩn |
| `SessionServiceTest` | 10 | `family_id = id`; cắt `user_agent`; rotation giữ family và hạn tuyệt đối; token thiếu/lạ; reuse trong grace → 409 không thu hồi; reuse sau grace → thu hồi family; token bị logout → `INVALID` (không phải `REUSED`); hết hạn idle/tuyệt đối; user bị khoá; logout |
| `AuthServiceLoginTest` | 14 | Nhận dạng email/SĐT/username; bỏ khoảng trắng Unicode quanh identifier; SĐT không thấy thì tìm theo username; không có user vẫn chạy BCrypt với hash giả; sai mật khẩu; khoá chỉ báo khi đúng mật khẩu; bị khoá tạm → 429 trước khi so mật khẩu; lần sai đếm theo tài khoản hoặc identifier; đúng mật khẩu xoá bộ đếm; `currentUser` |
| `LoginThrottleServiceTest` | 11 | Dưới ngưỡng không khoá; đủ ngưỡng → 429; hết thời gian khoá thì mở; đếm lại sau khoá và sau cửa sổ; `reset`; khoá đếm chung cho mọi identifier của một tài khoản, chuẩn hoá identifier lạ, là SHA-256 không chứa identifier |
| `LoginThrottlePropertiesTest` | 10 | Giá trị mặc định; ghi đè; số lần sai < 1, thời gian ≤ 0 hoặc thiếu → không khởi động |
| `DataCleanupServiceTest` | 4 | Xoá phiên với mốc `now - retention`, `login_failures` với mốc `now` và `now - failure-window`; lặp lô tới khi hết, cộng dồn số dòng |
| `CleanupPropertiesTest` | 7 | Giá trị mặc định; ghi đè; `retention` ≤ 0 hoặc thiếu, `batch-size` < 1 → không khởi động |
| `AuthCookiesTest` | 7 | Đủ thuộc tính cookie; `Max-Age` theo mốc sớm hơn; bản xoá cùng tên và `Path`; tắt `Secure` |
| `OriginCheckFilterTest` | 7 | GET; không có `Origin`; `Origin` hợp lệ, viết hoa; POST/PUT/PATCH/DELETE từ origin lạ; khác port; `Origin: null` |
| `IdentifierNormalizerTest` | 9 | Các dạng SĐT; đầu số hợp lệ; bỏ khoảng trắng mọi loại (U+00A0, U+202F...) ở hai đầu; `null` |
| `LogMaskerTest` | 19 | Email giữ ký tự đầu + tên miền; SĐT giữ đầu số + 3 số cuối; identifier đăng nhập: SĐT che như SĐT, còn lại chỉ giữ 2 ký tự đầu, mật khẩu gõ nhầm ô không lọt vào log; giá trị quá ngắn che hết; `null` |
| `JwksControllerTest` | 3 | Header `Content-Type`, `Cache-Control`; chỉ có tham số public kèm `kid`, `use`, `alg`; token ký bằng private key kiểm tra được bằng key đọc lại từ JWKS |

### Kiểm thử thủ công đã chạy (curl + PostgreSQL)

- Đăng nhập bằng 6 kiểu identifier; sai mật khẩu và user không tồn tại cùng 401, cùng thời gian phản hồi; tài khoản bị khoá.
- `/me` hợp lệ / thiếu / sửa token / hết hạn (`access-ttl=5s`).
- Refresh, reuse trong và sau grace window, `Max-Age` theo hạn family, family hết hạn, user bị khoá.
- 5 refresh song song cùng một token → 1 × 200, 4 × 409.
- Logout, refresh sau logout, logout không có cookie, logout với `Origin` lạ.
- Khởi động với 8 kiểu cấu hình sai → đều từ chối khởi động với thông báo đúng property.

### Kiểm thử đầu-cuối trên Chrome headless (với frontend): 17/17 pass

Chuyển hướng khi chưa đăng nhập; lỗi theo field; đăng nhập rồi quay lại đúng trang; cookie `HttpOnly` và JS không thấy
token; F5 và mở lại trình duyệt vẫn đăng nhập; tự refresh khi access hết hạn; nhiều 401 cùng lúc chỉ 1 refresh;
3 tab hết hạn cùng lúc không tab nào bị đăng xuất; đăng xuất/đăng nhập đồng bộ giữa các tab; nút Back sau đăng xuất;
dùng lại refresh token cũ → thông báo "lý do bảo mật"; mất cookie refresh → thông báo "hết hạn".

---

## 13. Bảng mã lỗi

| HTTP | `code` | API | Khi nào |
|---:|---|---|---|
| 400 | `INVALID_REQUEST_BODY` | register, login | Body sai cấu trúc (schema) |
| 400 | `INVALID_REGISTER_INFO` | register | Giá trị field sai, kèm `errors` |
| 400 | `INVALID_LOGIN_INFO` | login | Thiếu giá trị hoặc quá dài, kèm `errors` |
| 401 | `INVALID_CREDENTIALS` | login | Không có tài khoản hoặc sai mật khẩu |
| 401 | `TOKEN_EXPIRED` | me | Access token thật nhưng hết hạn |
| 401 | `UNAUTHENTICATED` | me | Thiếu/sai access token; user không còn hoặc bị khoá |
| 401 | `REFRESH_TOKEN_INVALID` | refresh | Thiếu/lạ/hết hạn/đã logout; user bị khoá. Kèm xoá cookie |
| 401 | `REFRESH_TOKEN_REUSED` | refresh | Token đã bị thay > 10 giây trước được dùng lại; cả phiên bị thu hồi. Kèm xoá cookie |
| 403 | `ACCOUNT_DISABLED` | login | Đúng mật khẩu nhưng tài khoản bị khoá |
| 403 | `ORIGIN_NOT_ALLOWED` | mọi POST/PUT/PATCH/DELETE | `Origin` không nằm trong whitelist |
| 404 | `API_NOT_FOUND` | (mọi path) | API chưa khai báo trong `api-routes.xml` |
| 409 | `USER_ALREADY_EXISTS` | register | Trùng username/email/SĐT, kèm `errors` |
| 409 | `REFRESH_CONFLICT` | refresh | Tab khác vừa refresh ≤ 10 giây trước; cookie **không** bị xoá |
| 429 | `TOO_MANY_LOGIN_ATTEMPTS` | login | Tài khoản / identifier đang bị khoá tạm sau nhiều lần sai, kể cả khi đúng mật khẩu |
| 429 | `TOO_MANY_REQUESTS` | login, register (gateway) | Một IP gửi quá nhiều request tới cùng API; kèm `Retry-After` (giây) |

---

## 14. Giới hạn hiện tại và việc tiếp theo

**Giới hạn đã biết:**

- Khoá RSA nằm trong `application.properties` (chỉ chấp nhận cho dev).
- Access token không thu hồi được trước hạn (tối đa 15 phút sau logout, nếu ai đó đã chép được token).
- IP lưu là IP client do gateway ghi vào `X-Forwarded-For` (`server.forward-headers-strategy=native`). Header này
  chỉ được tin khi request đến từ địa chỉ nội bộ, nên ai gọi thẳng vào port 8081 từ mạng nội bộ vẫn giả được IP.
- Giới hạn theo IP của gateway đếm trong RAM: mỗi instance gateway đếm riêng, restart thì mất.
- Khoá tạm theo tài khoản cho phép người khác cố tình khoá tài khoản của bạn trong 5 phút.

**Giai đoạn 2:**

- [x] Giới hạn đăng nhập sai / khoá tạm (gateway `RateLimit` + `LoginThrottleService`)
- [ ] `POST /auth/logout-all` và danh sách thiết bị đang đăng nhập (đã có index `user_id`, `user_agent`, `ip`)
- [x] Job dọn `refresh_tokens` và `login_failures` (`DataCleanupService`, mỗi giờ, giữ phiên đã chết 7 ngày)
- [ ] Giới hạn số phiên mỗi user (làm cùng danh sách thiết bị)
- [x] Endpoint JWKS `/auth/.well-known/jwks.json`
- [x] Kiểm tra JWT ở gateway (`JwtAuth` của fd-gateway-service, dùng JWKS)
- [x] Xử lý `X-Forwarded-For` khi có gateway
- [ ] CORS ở gateway (chỉ cần khi frontend không đi qua proxy cùng origin)
- [ ] Đọc khoá RSA từ secret, quy trình xoay khoá (`kid` mới, JWKS công bố cả khoá cũ và mới)

Checklist chi tiết từng bước đã làm: [`docs/auth-login-checklist.md`](../docs/auth-login-checklist.md).
