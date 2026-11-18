# food-delivery-backend

Multi-module Maven project gồm các microservice của ứng dụng giao đồ ăn: `fd-gateway-service` (8090), `fd-auth-service` (8081),
`fd-restaurant-service` (8082), `fd-order-service` (8083), `fd-delivery-service` (8084), `fd-chat-service` (8085).
`common-lib` gom các thư viện dùng chung của `food-delivery-libs` (`common-api-flow-lib`, `common-id-lib`, `common-web-lib`, `common-dto-lib`).

## Tài liệu kiến trúc: đọc trước, cập nhật cùng code

[ARCHITECTURE.md](ARCHITECTURE.md) là bản đồ cấu trúc của repo: module, port và phụ thuộc, vai trò từng class, luồng
chạy (gateway, đăng nhập, gọi giữa service), database, điểm mở rộng, hợp đồng không được phá, việc thường gặp sửa ở
đâu. Cấu trúc bên trong thư viện xem `ARCHITECTURE.md` của `food-delivery-libs`.

- **Đọc trước** khi bắt đầu một việc chưa rõ nằm ở đâu, hoặc trước khi đổi cấu trúc. Không cần đọc lại cho một sửa
  nhỏ trong class đã biết.
- **Cập nhật trong cùng commit** khi: thêm / xoá / đổi tên / di chuyển class, package hay module; đổi vai trò của một
  class; thêm test cho class đang ghi "-"; đổi phụ thuộc giữa module, port, route gateway, bảng database; đổi luồng
  chạy, điểm mở rộng hay một hợp đồng ở mục 7.
- Viết như bản đồ: mỗi class một dòng, tên class dạng `` `TênClass` ``, không chép code hay chi tiết đã có trong
  Javadoc/README.
- `ArchitectureDocTest` (common-lib) fail nếu một module hoặc class trong `src/main/java` không có trong file; sửa bằng
  cách bổ sung tài liệu, không sửa test.
- [AGENTS.md](AGENTS.md) chỉ trỏ tới file này và `ARCHITECTURE.md` cho các agent khác; không chép nội dung vào đó.

## Luồng xử lý một API

Mọi API của service đi qua luồng của `common-api-flow-lib`, mỗi lớp chỉ làm đúng phần của nó:

1. **`api-routes.xml`**: API phải được khai báo (method + path + controller), nếu không bị chặn với 404 `API_NOT_FOUND`.
2. **JSON Schema** (`schemas/*.json`): cấu trúc body: đủ field, đúng kiểu, không có field lạ -> 400 `INVALID_REQUEST_BODY`.
3. **Validator** (`validation/*Validator`): giá trị từng field: bắt buộc, không rỗng, đúng định dạng. **Không truy vấn DB.**
   Lỗi ném `BusinessException` kèm danh sách field lỗi để client biết field nào sai.
4. **Controller**: lấy dữ liệu từ `ApiRequest`, gọi service, trả `ResponseHandler.generateResponse(...)`.
5. **Service**: logic nghiệp vụ và mọi kiểm tra cần DB (trùng, tồn tại, trạng thái), ném `BusinessException` khi vi phạm.

## cURL cho mọi API HTTP

Mỗi API HTTP của backend (kể cả health check và API nội bộ `/internal/**`) có một file cURL để thử nhanh bằng Postman.

- Mỗi service một thư mục `postman/<module>/`. Tên file là **method viết hoa + path như trong `api-routes.xml` /
  controller, `/` đổi thành `_`** (tên file không được chứa `/`), vd. `POST /auth/login` -> `POST_auth_login.txt`,
  `GET /internal/users/{userId}` -> `GET_internal_users_{userId}.txt`. Cách dùng và bảng giá trị giả:
  `postman/README.md`.
- File chỉ chứa **đúng một lệnh cURL**, không comment hay chữ nào khác: chọn hết, copy, dán vào thanh URL của Postman.
- URL theo đường client thật: API công khai qua gateway `http://localhost:8090`; API nội bộ và service chưa có route ở
  gateway gọi thẳng cổng của service.
- Body, cookie, ID cần dữ liệu thật thì điền giá trị giả dễ nhận ra và có trong bảng của `postman/README.md`, vd.
  `PASTE_ACCESS_TOKEN`, ID mẫu `17939522301001`.
- Thêm, sửa (method, path, body, header) hay xoá API thì thêm, sửa hay xoá file cURL tương ứng **trong cùng commit**,
  và chạy thử lệnh đó với service đang chạy.

## Thay đổi database: luôn qua migration

Dự án nhiều người cùng làm, nên database của mọi người phải giống nhau chỉ nhờ chạy code mới nhất. Flyway tự áp dụng
các migration còn thiếu khi service khởi động; Hibernate chỉ kiểm tra (`ddl-auto=validate`), không bao giờ tự sửa bảng.

- **Mọi thay đổi cấu trúc** (tạo/xoá/đổi tên bảng, cột, kiểu dữ liệu, index, constraint, khoá ngoại) **và dữ liệu bắt
  buộc để app chạy** (vd. danh mục cố định, tài khoản admin mặc định) đều viết thành migration SQL trong
  `src/main/resources/db/migration` của service sở hữu database đó. Không sửa tay bằng psql/pgAdmin, không đổi
  `ddl-auto` sang `update`/`create`.
- Tên file `V<số>__<mô_tả_tiếng_anh>.sql`, số tăng dần liên tục trong từng service, vd. `V2__add_avatar_to_users.sql`.
  Trước khi đặt số, xem số lớn nhất đang có trong thư mục và trên nhánh `main` để không trùng với người khác.
- **Không sửa, không xoá, không đổi tên migration đã commit**: Flyway so checksum, nên máy đã chạy bản cũ sẽ không
  khởi động được. Cần sửa thì viết migration mới.
- Migration phải chạy được trên database đã có dữ liệu: thêm cột `NOT NULL` thì kèm `DEFAULT` hoặc cập nhật dữ liệu cũ
  trước, đổi tên cột thì dùng `ALTER ... RENAME` thay vì xoá rồi tạo lại.
- Migration và entity JPA sửa trong **cùng một commit**; khởi động thử service để `validate` xác nhận hai bên khớp.
- Dữ liệu thử nghiệm của riêng mình (tài khoản test, đơn hàng mẫu) **không** viết thành migration. Khi tạo hay xoá dữ
  liệu như vậy trên database dùng chung, báo lại cho người dùng.

## Test: mọi thay đổi source code phải đi kèm test đúng

Code chỉ được coi là xong khi có test chứng minh nó chạy đúng. Áp dụng cho **mọi module** và **mọi thay đổi**.

- **Có test tương ứng:** mỗi class có logic (service, validator, controller, client, filter, interceptor, util,
  exception handler, config có điều kiện, compact constructor có kiểm tra...) có class test cùng package trong
  `src/test/java`, tên `<TênClass>Test`, vd. `UserQueryService` -> `UserQueryServiceTest`. Không bắt buộc với record/DTO
  không có logic, `*Application`, config chỉ khai báo bean đơn giản.
- **Thêm code:** viết test trong **cùng commit**, phủ đường đi thành công, từng lỗi (`BusinessException` với đúng status
  và mã lỗi), trường hợp biên và ngữ nghĩa `null` được ghi trong Javadoc/comment.
- **Sửa code:** cập nhật test theo hành vi mới; sửa bug thì thêm test tái hiện bug (fail trước khi sửa, pass sau khi
  sửa). **Xoá / đổi tên / di chuyển code:** xoá, đổi tên, di chuyển test tương ứng, không để test mồ côi.
- **Không làm test "xanh" bằng cách sai:** không xoá test, không nới assertion, không `@Disabled` để che lỗi. Test fail
  vì hành vi đổi có chủ đích thì sửa test và ghi rõ lý do trong commit. `@Disabled` chỉ dùng khi có lý do ghi trong
  annotation, vd. `@Disabled("BUG: ...")`, và phải báo lại cho người dùng.
- **Unit test không dùng tài nguyên thật:** repository mock bằng Mockito, HTTP server phụ thuộc thay bằng
  `com.sun.net.httpserver.HttpServer` cục bộ, gRPC bằng server in-process (`grpc-inprocess`), thời gian bằng `Clock` cố
  định. Ví dụ: `AuthServiceLoginTest`, `AuthHttpClientTest`, `AuthGrpcClientTest`, `UserGrpcServiceTest`.
- **Trước khi báo xong hoặc commit:** chạy `./mvnw test -pl <module>` cho mọi module bị ảnh hưởng (hoặc `./mvnw clean
  install` khi đổi module dùng chung như `common-lib`, `fd-grpc-api`); tất cả phải pass. Báo lại số test đã chạy, và
  nói rõ nếu có phần chưa có test.
- Test theo cùng quy ước comment bên dưới: mỗi class test có Javadoc ngắn nói test cái gì và bằng cách nào; tên method
  test là camelCase mô tả hành vi, vd. `unknownUserGivesNotFoundWithErrorCode`; dùng AssertJ.

### Mức độ test: đủ để bắt lỗi, không thừa đến mức cản refactor

Mục tiêu là mỗi hành vi đã hứa (trong Javadoc, mã lỗi, API) đều có test, không phải 100% coverage. Test thừa tốn công
bảo trì và gãy khi refactor dù hành vi không đổi.

| Loại code | Mức test |
|---|---|
| Validator, service nghiệp vụ, client gọi service khác, filter/interceptor, util | **Kỹ:** thành công, từng nhánh lỗi (status + mã lỗi + field lỗi), biên, `null` |
| Exception handler, mapper, config có điều kiện | Mỗi nhánh/ánh xạ một test |
| Controller | Mỏng: lấy đúng dữ liệu từ `ApiRequest`, gọi đúng service, đúng status; không test lại validator/service |
| Record/DTO/entity không logic, getter/setter, `*Application`, config chỉ khai báo bean | Không viết test |
| Ghép nối giữa các service, luồng qua gateway | File cURL trong `postman/<module>/` (mục "cURL cho mọi API HTTP"), chạy thử khi đổi API |

- **Test hành vi qua API công khai**, không test chi tiết cài đặt: không reflection vào field private, không gọi method
  private, không assert annotation, thứ tự gọi nội bộ hay số lần gọi mock khi điều đó không phải là hành vi được hứa.
  Refactor mà không đổi hành vi thì test không được gãy.
- **Mỗi test kiểm tra một hành vi**, tên nói rõ hành vi đó. Nhiều đầu vào cùng một nhánh thì dùng `@ParameterizedTest`
  với vài giá trị đại diện và giá trị biên (nhỏ nhất, lớn nhất, ngay ngoài biên), không liệt kê mọi giá trị.
- **Assert đúng mức:** lỗi nghiệp vụ assert status + mã lỗi + field lỗi; chỉ assert nguyên văn message khi message là
  thứ client hiển thị. Không assert log.
- **Không test lại framework và thư viện đã có test** (Spring, Jackson, Hibernate, `common-lib`): vd. không test
  `ResponseHandler` hay format `ApiResponse` trong service, chỉ test service trả đúng status/mã lỗi.
- Test đa luồng, hiệu năng chỉ viết khi code hứa điều đó (vd. "thread-safe"). Test timeout dùng timeout ngắn (vài trăm
  ms), không `Thread.sleep` để chờ.
- Unit test phải nhanh (cả module vài giây): không khởi động Spring context đầy đủ (`@SpringBootTest`) cho unit test;
  dùng context runner hoặc dựng object bằng tay.
- Coverage tham khảo: khoảng **80% dòng** cho phần logic (validator, service, client, util). Đây là chỉ báo để tìm chỗ
  thiếu, không phải mục tiêu: không viết test vô nghĩa để tăng số.

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
 * Account of a person using the app, stored in the {@code users} table (created by the Flyway migrations in
 * {@code db/migration}, checked by Hibernate with {@code ddl-auto=validate}).
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
- **Mỗi comment chỉ một dòng** (tối đa ~120 ký tự) trong `pom.xml`, `application.properties` và `api-routes.xml`:
  giữ ý chính (làm gì, vì sao, mặc định); chi tiết dài (ví dụ, lệnh, giải thích) đưa vào README hoặc Javadoc của class
  liên quan.
- `api-routes.xml`: mỗi `<route>` có một comment `<!-- -->` ngay trên, nói API làm gì và schema/validator/service kiểm tra phần nào.
- `application.properties`: comment `#` trên dòng riêng, trước property hoặc nhóm property; nói rõ giá trị mặc định khi có.
- `pom.xml`: comment ngay trên dependency/plugin/property, nói vì sao cần nó.
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
