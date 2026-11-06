# food-delivery-backend

Multi-module Maven project gồm các microservice của ứng dụng giao đồ ăn: `fd-gateway-service` (8080), `fd-auth-service` (8081),
`fd-restaurant-service` (8082), `fd-order-service` (8083), `fd-delivery-service` (8084), `fd-chat-service` (8085).
`common-lib` gom các thư viện dùng chung của `food-delivery-libs` (`common-api-flow-lib`, `common-id-lib`, `common-web-lib`, `common-dto-lib`).

## Luồng xử lý một API

Mọi API của service đi qua luồng của `common-api-flow-lib`, mỗi lớp chỉ làm đúng phần của nó:

1. **`api-routes.xml`**: API phải được khai báo (method + path + controller), nếu không bị chặn với 404 `API_NOT_FOUND`.
2. **JSON Schema** (`schemas/*.json`): cấu trúc body: đủ field, đúng kiểu, không có field lạ -> 400 `INVALID_REQUEST_BODY`.
3. **Validator** (`validation/*Validator`): giá trị từng field: bắt buộc, không rỗng, đúng định dạng. **Không truy vấn DB.**
   Lỗi ném `BusinessException` kèm danh sách field lỗi để client biết field nào sai.
4. **Controller**: lấy dữ liệu từ `ApiRequest`, gọi service, trả `ResponseHandler.generateResponse(...)`.
5. **Service**: logic nghiệp vụ và mọi kiểm tra cần DB (trùng, tồn tại, trạng thái), ném `BusinessException` khi vi phạm.

## Quy ước comment trong source code

Chuẩn tham chiếu là `common-api-flow-lib` (vd. `ApiRegistry`, `ApiFlowInterceptor`, `ApiRequest`); trong dự án này xem
`fd-auth-service` (`AuthValidator`, `AuthService`, `RegisterRequest`).
Áp dụng cho **mọi module** khi viết code mới hoặc sửa code cũ. Khi sửa logic, phải cập nhật comment liên quan để không bị lệch với code.

### Nguyên tắc chung

- Comment viết bằng **tiếng Anh**, ngắn gọn, thì hiện tại, giọng mô tả ("Creates a CUSTOMER account...", "Thrown when...").
- Comment giải thích **tại sao / ràng buộc / hành vi không hiển nhiên**, không lặp lại điều code đã nói rõ.
- Luôn nói rõ ngữ nghĩa của `null`/rỗng khi có: "`null` if absent", "null if it is valid", "Null fields stay null".
- Phân biệt rõ thời điểm chạy: **at startup** (load/validate config) và **at request time**.
- Dùng `e.g.` để đưa ví dụ cụ thể, dùng `->` để mô tả ánh xạ: `"0912.345.678" -> "+84912345678"`.
- Dòng tối đa ~120 ký tự; xuống dòng tự nhiên, không căn lề kiểu hộp.
- KHÔNG dùng: `@author`, ngày tháng, changelog, banner phân đoạn (`// ===== ... =====`), code bị comment-out, comment cuối dòng (end-of-line), comment kiểu "// getter", "// constructor", "// Getters and Setters".

### 1. Class / interface / enum / record — Javadoc `/** */`

Mọi type public đều có Javadoc (kể cả `*Application` và controller health check):

- **Câu/đoạn đầu**: nó là gì và vai trò trong luồng xử lý.
- **Đoạn `<p>` tiếp theo** (nếu cần): ai tạo/gọi nó, khi nào (startup / request time), cách hoạt động chính, đảm bảo (thread-safety, read-only, null policy), quan hệ với các class khác.
- Danh sách dùng `<ul><li>`, ví dụ cấu hình dùng `<pre>`.
- Record: dùng `@param` cho từng component (không comment cuối dòng sau component).

```java
/**
 * Validators of the APIs in {@code AuthController}, called by the API flow at request time, after the JSON Schema and
 * before the controller.
 *
 * <p>Only checks the input itself (required fields, format) and never queries the database; business rules such as
 * duplicate accounts belong to {@code AuthService}.
 */
```

```java
/**
 * Body of {@code POST /auth/register}, read with {@code request.getBody(RegisterRequest.class)}.
 *
 * @param username    3-30 characters of {@code a-z}, {@code 0-9}, {@code .} and {@code _}, case-insensitive
 * @param password    8-64 characters with an uppercase letter, a lowercase letter, a digit and a special character;
 *                    never trimmed
 */
```

```java
/**
 * Account of a person using the app, stored in the {@code users} table (created and updated by Hibernate,
 * {@code ddl-auto=update}).
 *
 * <p>The ID has no generated value: the caller assigns it with {@code IdUtils.generate(EntityType.USER)}.
 */
```

Exception class: nói rõ **khi nào bị throw** và **hệ quả** (vd. "so the application refuses to start").

### 2. Method

Chọn hình thức theo mức độ quan trọng:

| Loại method | Hình thức |
|---|---|
| Public API chính (service, validator, controller endpoint), có tham số/kết quả cần giải thích | Javadoc với `@param` / `@return` (căn thẳng tên param) |
| Public method có hành vi đặc biệt (exception, null, side effect) | Javadoc, câu đầu tóm tắt + đoạn `<p>` cho chi tiết |
| Method ngắn, private/package-private helper, override, `@Bean` | Một/vài dòng `//` ngay trên method |
| Getter/setter/constructor hiển nhiên | Không comment |
| Getter có ngữ nghĩa không hiển nhiên (format, null, đơn vị) | Một dòng `//` ngay trên, kèm ví dụ |

Method của service/validator ném `BusinessException` phải ghi rõ status + mã lỗi và khi nào bị ném:

```java
/**
 * Creates a {@code CUSTOMER} account with the password hashed by BCrypt.
 *
 * <p>Throws {@code BusinessException} 409 {@code USER_ALREADY_EXISTS} if the username, email or phone number is
 * already registered, with one field error per duplicate field.
 *
 * @param request body that passed {@code AuthValidator.validateRegister}, not normalized yet
 * @return the created user
 */
public UserDto register(RegisterRequest request) {
```

Controller endpoint: method + path, status trả về và luồng đi qua các lớp:

```java
/**
 * {@code POST /auth/register}: creates an account and returns it with 201.
 *
 * <p>Flow: {@code schemas/register.json} (structure) -> {@code AuthValidator.validateRegister} (required fields,
 * format) -> this method -> {@link AuthService#register} (duplicates, save).
 */
```

```java
// Copy with username and email trimmed and lowercased, and the phone number as +84xxxxxxxxx, e.g.
// " Quan@Gmail.com " -> "quan@gmail.com", "0912.345.678" -> "+84912345678". Null fields stay null
public RegisterRequest normalized() {
```

```java
// Reason the password is invalid, null if it is valid. A special character is anything that is not a letter, a
// digit or whitespace, e.g. ! @ # $ % _ -
private static String checkPassword(String password) {
```

Khi visibility hẹp hơn bình thường có chủ đích, giải thích lý do:

```java
// Package-private: only ApiRouteVerifier sets it, once, during startup
void setResolvedValidator(ResolvedValidator resolvedValidator) { ... }
```

### 3. Field / constant

Dùng `//` một dòng ngay trên field khi ý nghĩa không hiển nhiên: format, giá trị ví dụ, ý nghĩa của `null`, đơn vị, ai dùng nó, lý do của con số.
Không comment field hiển nhiên (logger, dependency được inject, field trùng tên getter rõ nghĩa, cột entity không có ràng buộc gì đặc biệt).

```java
// BCrypt hash, e.g. "$2a$10$...", never the raw password
@Column(name = "password", nullable = false)
private String password;

// Vietnamese mobile prefixes 03, 05, 07, 08, 09, after normalization to +84, e.g. "+84912345678"
private static final Pattern PHONE = Pattern.compile("^\\+84[35789]\\d{8}$");

// BCrypt ignores everything after byte 72, so a longer password would be silently truncated. A character can take
// up to 4 bytes in UTF-8, hence this check on top of MAX_PASSWORD_LENGTH
private static final int MAX_PASSWORD_BYTES = 72;
```

### 4. Comment trong thân method

- Đặt **trên dòng riêng**, ngay trước khối code nó giải thích; không đặt cuối dòng.
- Chỉ dùng cho quyết định không hiển nhiên, trường hợp biên, hoặc lý do bỏ qua/cho phép điều gì.

```java
// Flushed now so a unique constraint violation is thrown here, not at commit outside the try
user = userRepository.saveAndFlush(user);

// checkNotRegistered already looked for duplicates. This only happens when two requests with the same
// username or email both pass that check before either is saved
throw new BusinessException(ApiStatus.CONFLICT, "USER_ALREADY_EXISTS", "Account already exists");

// The password is never logged
log.info("[VALIDATE] [{}] {} | username={}, email={}, phoneNumber={}", ...);
```

### 5. File cấu hình (`api-routes.xml`, `application.properties`, `pom.xml`, `log4j2-spring.xml`)

- Cùng quy tắc ngôn ngữ và định dạng: tiếng Anh, viết hoa chữ đầu, không chấm câu ở cuối dòng cuối.
- `api-routes.xml`: mỗi `<route>` có một comment `<!-- -->` ngay trên, nói API làm gì và schema/validator/service kiểm tra phần nào.
- `application.properties`: comment `#` trên dòng riêng, trước nhóm property; nói rõ giá trị mặc định khi có.
- Dòng cấu hình bị comment-out chỉ giữ khi nó là một tuỳ chọn được ghi chú rõ (vd. `#api-flow.use-default-excludes=false`).

### 6. Định dạng

- Comment `//`: viết hoa chữ đầu, **không** chấm câu ở cuối dòng cuối. Nếu nhiều câu thì các câu giữa có dấu chấm. Comment dài thì tách thành nhiều dòng `//` liên tiếp.
- Javadoc: câu hoàn chỉnh, có dấu chấm cuối câu; đoạn mới bắt đầu bằng `<p>` trên dòng riêng sau một dòng ` *` trống.
- Trong Javadoc:
  - `{@code ...}` cho literal, tên property, XML tag (`{@code <validator>}`), mã lỗi, giá trị (`{@code null}`, `{@code true}`), path.
  - `{@link ...}` cho class/member mà file hiện tại có thể tham chiếu.
  - `{@code ClassName}` (không phải `{@link}`) cho class ở package/module khác mà không muốn tạo import chỉ vì comment.
  - HTTP status kèm mã lỗi: `409 {@code USER_ALREADY_EXISTS}`.
- Trong comment `//`: viết tên class/giá trị dạng text thường, chuỗi đặt trong `"..."`, không dùng tag Javadoc.
