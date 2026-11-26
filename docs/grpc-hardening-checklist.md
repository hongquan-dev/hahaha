# Checklist: hoàn thiện gRPC giữa các service

Phạm vi: `fd-grpc-api` và mọi service có gRPC sau này. Hiện **chưa có API gRPC nào**: gRPC `UserService/GetUser`
(auth → order) chỉ để học nên đã bị xoá; `fd-grpc-api` giữ phần hỗ trợ dùng chung (`BusinessExceptionGrpcHandler`,
`GrpcLoggingInterceptor`, `CallerServiceClientInterceptor`, `GrpcMetadataKeys`). Mục tiêu của file này: khi thêm gRPC
mới, mọi gRPC đều giống nhau và an toàn ngay từ đầu. Lịch sử giai đoạn có `GetUser` xem
[`gateway-grpc-checklist.md`](gateway-grpc-checklist.md).

Các mục sắp theo **mức độ tăng dần**: mức 1 chỉ sửa cấu hình hoặc tài liệu, mức sau cần nhiều code và hạ tầng hơn.
Trong cùng một mức, mục đứng trước nên làm trước. Mỗi mục có:

- **Lý do**: vấn đề hiện tại.
- **Tác dụng**: làm xong thì được gì.
- **Làm ở đâu**: file hoặc class cần sửa.
- **Kiểm tra**: cách nghiệm thu. Chỉ tick khi kiểm tra đạt.

Mọi mục có code phải theo `CLAUDE.md`: có test trong cùng commit, cập nhật `ARCHITECTURE.md` khi thêm class, và chạy
`./mvnw clean install` vì `fd-grpc-api` là module dùng chung. Chưa có service nào dùng gRPC, nên code của mức 2 được
kiểm bằng test của `fd-grpc-api` trên `EchoService` (proto chỉ dùng cho test, `src/test/proto`).

---

## Mức 1. Cấu hình và quy ước (đã xong)

### 1.1 Tắt server reflection

- [x] `spring.grpc.server.reflection.enabled=false` ở **mọi môi trường**, kể cả dev. Quyết định: gRPC chỉ dành cho
      các service gọi nhau, và chúng đã có hợp đồng trong class sinh từ `.proto`; không có bên ngoài nào cần
      reflection. Không thêm profile dev
- [x] Dev thử bằng `grpcurl -import-path fd-grpc-api/src/main/proto -proto <file>.proto ...`; health check bằng
      `grpc_health_probe` (có sẵn hợp đồng của health service, cần dependency `grpc-services`)

Cấu hình này đã bị xoá cùng gRPC server của auth. Quy tắc được giữ trong mẫu cấu hình của mục 2.1 để gRPC server
sau này áp dụng ngay.

**Lý do:** reflection mặc định bật. Nó cho bất kỳ ai kết nối được cổng gRPC xem toàn bộ hợp đồng mà không cần đoán.
**Tác dụng:** người ngoài không liệt kê được service và method bằng `grpcurl list`. Đây chỉ là giấu bản đồ, không
phải lớp bảo vệ chính: bảo vệ thật là mục 1.2 và 3.1.

Kết quả đã chạy (khi còn `GetUser`; grpcurl 1.9.4, grpc_health_probe 0.4.40 tải vào `/tmp`):
- Log khởi động auth không còn `grpc.reflection.v1.ServerReflection`; `grpcurl list` → `server does not support the
  reflection API`
- `grpcurl` có `-proto` gọi `GetUser` đúng user; ID không có → `NotFound`, trailer `error-code: USER_NOT_FOUND`
- `grpc_health_probe -addr=localhost:9081` → `status: SERVING`; order vẫn gọi được auth (`caller=service-order`)

### 1.2 Chỉ mở cổng của service trong mạng nội bộ

- [x] `server.address=127.0.0.1` cho auth, order, restaurant, delivery, chat. Chỉ gateway `8090` nghe trên mọi địa
      chỉ
- [x] gRPC server: `spring.grpc.server.address=127.0.0.1` (đã xoá cùng gRPC của auth; giữ trong mẫu của mục 2.1)
- [x] Deploy (Docker, K8s): đặt `SERVER_ADDRESS=0.0.0.0` (và `SPRING_GRPC_SERVER_ADDRESS=0.0.0.0` nếu có gRPC) để
      các container gọi được nhau, chỉ publish `8090`. Ghi trong mục port của `README.md`

**Lý do:** service phía sau tin `X-User-Id` do gateway gắn, nên ai gọi thẳng `<IP>:8083` kèm `X-User-Id` tự điền là
**mạo danh được bất kỳ user nào**. Cổng gRPC plaintext, không xác thực thì ai trong LAN có file `.proto` cũng gọi được.
**Tác dụng:** máy khác chỉ còn vào được qua gateway, nơi kiểm tra token. Lưu ý nó chặn theo **máy**, không theo bên
gọi: mọi chương trình trên cùng máy (hay trong cùng mạng container khi deploy) vẫn gọi được.

Kết quả đã chạy (cả 6 service, IP LAN của máy là `10.2.22.53`):
- `ss -ltn`: 8081-8085 (và 9081 lúc còn gRPC) nghe trên `127.0.0.1`, 8090 trên `*`
- Qua `10.2.22.53`: 8081-8085 bị từ chối (curl exit 7), kể cả order kèm `X-User-Id` giả; gRPC 9081 không kết nối được;
  gateway chạy
- Luồng đầy đủ qua `10.2.22.53:8090` (tài khoản test `p12test_675161`) → 200. Cookie `access_token` có `Secure` nên
  gọi gateway bằng IP qua HTTP thì dùng header `Authorization: Bearer`
- Sau khi xoá `GetUser`: auth, order, gateway khởi động bình thường, không còn cổng 9081; `GET /order/health` qua
  gateway → 200

### 1.3 Service gọi nhau chỉ qua gRPC

- [x] Quy tắc trong mục "Quy tắc phụ thuộc" của `ARCHITECTURE.md`: service gọi service **chỉ qua gRPC**, không viết
      API HTTP nội bộ (`/internal/**`). HTTP chỉ dành cho client đi qua gateway và các chuẩn bắt buộc là HTTP (JWKS
      cho gateway, callback/webhook của bên thứ ba)
- [x] Bỏ `/internal/**` và "HTTP `RestClient`" khỏi `ARCHITECTURE.md`, `postman/README.md`, `CLAUDE.md`

**Lý do:** có hai cách gọi mà không có quy tắc chọn thì mỗi người làm một kiểu, và mỗi cách phải bảo mật, log, ánh xạ
lỗi riêng.
**Tác dụng:** một đường gọi duy nhất, nên mọi thứ của mức 2-4 chỉ làm một lần ở `fd-grpc-api`.

### ~~1.4 Chỉ trả dữ liệu mà bên gọi thật sự cần~~ (bỏ)

Mục này nhắm vào field `phone_number` của `GetUser`, đã bị xoá. Nguyên tắc "chỉ trả field bên gọi cần, field bỏ đi
thì `reserved`" chuyển vào quy ước `.proto` của mục 2.1.

---

## Mức 2. Nền tảng chung cho mọi gRPC (đã xong)

Phân tích lại sau khi xoá `GetUser`: các mục mức 2 cũ được viết để **sửa** một client có sẵn (`AuthGrpcClient`). Giờ
chưa có client nào, nên đây là lúc rẻ nhất để làm: không phải sửa code cũ, gRPC đầu tiên dùng luôn phần chung. Bị xoá
cùng `GetUser` còn có **mẫu** để chép (cách đặt tên, cấu hình, test). Vì vậy mục quan trọng nhất là viết mẫu đó ra
(2.1). Hai mục mới: deadline mặc định (2.3) và hỗ trợ test cho service (2.6). Mục "server dừng khi bên gọi bỏ cuộc"
cũ không cần code chung nên gộp vào quy ước của 2.1.

### 2.1 Quy ước cho mọi gRPC mới

Mục tiêu (bạn chốt): yêu cầu **bất kỳ con Claude nào** tạo gRPC, dù là cái đầu tiên, thì kết quả đúng một cấu trúc.
Vì vậy 2.1 gồm bốn phần, từ "hướng dẫn" tới "ép buộc":

- [x] **A. Mẫu** `docs/grpc-conventions.md`: bảng tên cần thay, các bước theo thứ tự, code mẫu chép được cho mọi
      file (`.proto`, pom, properties, `XxxGrpcValidator`, `XxxGrpcService`, `GrpcClientConfig`, record,
      `XxxGrpcClient`, test server, test client), tài liệu cần cập nhật, cách kiểm tra, bảng "không làm". Port gRPC =
      port HTTP + 1000; deadline dùng mặc định của 2.3 thay cho `services.<tên>.timeout`
- [x] **B. Điểm vào:** mục "gRPC giữa các service" trong `CLAUDE.md` (luôn được nạp) và project skill
      `.claude/skills/add-grpc/SKILL.md` (Claude Code tự gọi khi được yêu cầu tạo gRPC)
- [x] **C. Mặc định an toàn:** `GrpcServerDefaultsEnvironmentPostProcessor` đặt `spring.grpc.server.address=127.0.0.1`
      và `reflection.enabled=false` cho mọi service có `fd-grpc-api`; `application.properties` và biến môi trường
      vẫn ghi đè được. Quên cấu hình bảo mật của mức 1 cũng không sao
- [x] **D. Test quy ước:** `GrpcConventionsTest` (common-lib, quy tắc trong `GrpcConventions`) quét repo, fail nếu:
      `.proto` sai đường dẫn / `package` / `java_package` / thiếu `java_multiple_files` / `rpc` không có comment;
      `@GrpcService` không tên `XxxGrpcService` hay không ở `<service>.grpc`; client không ở `<service>.client` hay
      không dùng `GrpcClientErrors`; `@ImportGrpcClients` ngoài `GrpcClientConfig`; class khác import `io.grpc` hay
      class sinh từ proto; server / client thiếu test; port gRPC khác port HTTP + 1000. Mỗi quy tắc có test bằng mẫu
      đúng và mẫu sai

**Lý do:** trước đây quy ước chỉ nằm ngầm trong code của `GetUser`; code bị xoá thì mẫu cũng mất. Không có mẫu viết
ra, mỗi người thêm gRPC sẽ tự chọn tên package, port, cách xử lý lỗi, và quên các quyết định đã chốt ở mức 1 (tắt
reflection, chỉ nghe `127.0.0.1`).
**Tác dụng:** mọi gRPC giống nhau, đọc một cái là hiểu tất cả; review chỉ cần so với mẫu. Các quyết định bảo mật của
mức 1 được áp dụng ngay từ gRPC đầu tiên.
**Làm ở đâu:** `docs/grpc-conventions.md`, `CLAUDE.md`, `.claude/skills/add-grpc/`, `fd-grpc-api`
(`GrpcServerDefaultsEnvironmentPostProcessor`, `META-INF/spring.factories`), common-lib (`GrpcConventions`,
`GrpcConventionsTest`), `ARCHITECTURE.md` mục 6, 8, 9.
**Kiểm tra:** một agent Claude **mới**, không được nói gì về quy ước, tạo một gRPC **khác** ví dụ trong tài liệu, trên
bản sao của repo; kết quả phải đúng cấu trúc và build pass.

Kết quả nghiệm thu (bản sao `/tmp/fd-trial`, yêu cầu: "order cần lấy thông tin nhiều user cùng lúc theo danh sách ID
từ auth"):
- Agent **tự gọi skill `add-grpc`** và đọc `docs/grpc-conventions.md`, không cần nhắc
- Kết quả đúng mẫu: `auth/v1/user_service.proto` (`ListUsers`, comment liệt kê 2 lỗi, chỉ có `id` + `username`),
  `UserGrpcService`, `UserGrpcValidator` (không truy vấn DB, giới hạn 100 ID), `UserQueryService` (một query
  `findAllById`), `GrpcClientConfig`, `AuthGrpcClient` (dùng `GrpcClientErrors`, trả `Map<Long, AuthUser>`), port
  9081, **không** tự đặt `address` / `reflection`; tài liệu cập nhật đủ
- Mình chạy lại `./mvnw -o clean verify` trên bản sao: common-lib 19, fd-grpc-api 97, gateway 64, auth 328, order 5
  test, tất cả pass
- Agent chạy thật: auth nghe `127.0.0.1:9081`, grpcurl đủ các trường hợp (thành công, ID không có, 100 / 101 ID, ID sai
  loại), client thật trả 503 `AUTH_UNAVAILABLE` khi auth tắt
- Thử phá quy ước trên chính code của agent (client tự đổi lỗi, proto bỏ `java_multiple_files`) →
  `GrpcConventionsTest` fail, chỉ đúng file và quy tắc

### 2.2 Ánh xạ lỗi phía client dùng chung

- [x] `GrpcClientErrors.toBusinessException(e, "AUTH")` trong `com.fooddelivery.grpc.support`:
  - Lỗi nghiệp vụ (có trailer `error-code` và status đọc ngược được): cùng HTTP status, mã lỗi và message của server
    (`NOT_FOUND` → 404, `INVALID_ARGUMENT` → 400, `UNAVAILABLE` → 503, `DEADLINE_EXCEEDED` → 504...)
  - `UNAVAILABLE`, `DEADLINE_EXCEEDED` không có trailer: 503 `<TARGET>_UNAVAILABLE`
  - Còn lại (vd. `INTERNAL`, kể cả có trailer): 502 `<TARGET>_CALL_FAILED`, không lộ chi tiết của service nhận
  - `target` phải viết hoa (`[A-Z][A-Z0-9_]*`), sai thì `IllegalArgumentException`
- [x] Bảng hai chiều tách thành `GrpcStatuses` (`toGrpc`, `toApiStatus`), `BusinessExceptionGrpcHandler` dùng chung
- [x] Ghi rõ giới hạn: field lỗi (`getErrors()`) không đi qua gRPC, chỉ có mã và message

Kết quả: `GrpcStatusesTest` 35 (mọi `ApiStatus` đi rồi về đúng, trừ 422 → 400 đã ghi rõ), `GrpcClientErrorsTest` 24
(từng status có trailer, không trailer, `INTERNAL` có trailer, `target` sai, và một lời gọi thật in-process server ném
`BusinessException` → client nhận lại đúng 409 `ORDER_ALREADY_PAID`).

**Lý do:** `AuthGrpcClient` cũ tự viết đoạn switch này. Mỗi client mới sẽ chép lại, và các bản chép dễ lệch nhau,
vd. một client quên đọc trailer nên mất mã lỗi gốc, hay đổi `UNAVAILABLE` thành 500.
**Tác dụng:** lỗi qua gRPC giữ đúng mã như gọi trực tiếp, ở **mọi** client. Có thể test cả vòng: server ném
`BusinessException` → handler → client helper → đúng status và mã ban đầu.
**Làm ở đâu:** `fd-grpc-api` (class mới, test với `EchoService`), `ARCHITECTURE.md`.
**Kiểm tra:** test phủ từng status, có hoặc thiếu trailer; test vòng tròn handler → helper cho mỗi `ApiStatus` mà
`toGrpcStatus` ánh xạ.

### 2.3 Deadline mặc định cho mọi lời gọi

- [x] `DefaultDeadlineClientInterceptor` (`@GlobalClientInterceptor`): lời gọi chưa có deadline thì gắn
      `fooddelivery.grpc.client.default-deadline` (mặc định 2s, `GrpcClientProperties` kiểm tra lúc startup: không
      dương thì service không khởi động). Lời gọi đã có deadline giữ nguyên; trong lời gọi gRPC đang xử lý, deadline
      sớm hơn thắng
- [x] `GrpcSupportAutoConfiguration` đăng ký nó cho service có gRPC client

Kết quả: `DefaultDeadlineClientInterceptorTest` 3 (không deadline → mặc định; deadline của client giữ nguyên; deadline
của lời gọi đang xử lý sớm hơn thì thắng), `GrpcClientPropertiesTest` 4, `GrpcSupportAutoConfigurationTest` thêm bean
mới, mặc định 2s, giá trị `0s` làm service không khởi động.

**Lý do:** gRPC mặc định **không có** deadline: quên `withDeadlineAfter` là lời gọi có thể chờ mãi, giữ thread của
service gọi. Đây là lỗi hay gặp nhất với người mới. `GetUser` có deadline chỉ vì người viết `AuthGrpcClient` nhớ thêm.
**Tác dụng:** không client nào treo vô hạn, kể cả khi quên. Client vẫn đặt deadline riêng khi cần (quy ước 2.1).
Deadline của request đang xử lý tự truyền xuống lời gọi tiếp theo (grpc-java lấy theo `Context`), nên chuỗi A → B → C
không vượt quá deadline của A.
**Làm ở đâu:** `fd-grpc-api` (interceptor, property, test), `ARCHITECTURE.md`.
**Kiểm tra:** test với server in-process không trả lời: không đặt deadline → `DEADLINE_EXCEEDED` sau deadline mặc định
(dùng giá trị vài trăm ms trong test); có `withDeadlineAfter` → dùng giá trị đó.

### 2.4 Request ID xuyên suốt các service

- [x] **Hợp đồng chung** `RequestIds` (common-dto-lib, `com.fooddelivery.dto.requestid`): header `X-Request-Id`
      (gRPC `x-request-id`), khoá `requestId` của `ThreadContext`, định dạng hợp lệ 1-64 ký tự `[A-Za-z0-9._-]`
      (chống chèn rác vào log), `orGenerate`. Đặt ở common-dto-lib vì gateway (WebFlux) chỉ dùng lib này: gateway,
      filter HTTP và gRPC cùng một quy tắc
- [x] **(a) HTTP:**
  - Gateway: `RequestIdGlobalFilter` chạy trước mọi filter, giữ ID hợp lệ của client hoặc tạo mới, gửi xuống service
    và trả về client (đúng một giá trị, đè bản service trả). Gateway là reactive, không có `ThreadContext`: log `[AUTH]`,
    `[RATE_LIMIT]` in `requestId=` đọc từ header
  - Service: `RequestIdFilter` (common-web-lib, `HIGHEST_PRECEDENCE`, cả error/async dispatch) đặt ID vào
    `ThreadContext`, trả response header, trả lại giá trị cũ của thread kể cả khi lỗi
  - Log pattern của `common-lib`: `%notEmpty{[%X{requestId}] }` (log lúc startup không có `[]` thừa)
- [x] **(b) gRPC:** `RequestIdClientInterceptor` chép ID của `ThreadContext` sang `x-request-id`;
      `RequestIdServerInterceptor` (ngoài `GrpcLoggingInterceptor`) đặt ID vào `ThreadContext` trong **từng** callback
      và trả lại giá trị cũ; `GrpcLoggingInterceptor` in `requestId=`

**Lý do:** log của các service là các dòng độc lập; không biết dòng nào ở service B thuộc request nào ở service A.
**Tác dụng:** response nào cũng có `X-Request-Id`; tìm ID đó là thấy mọi dòng log của request ở gateway và mọi service.

Kết quả:
- food-delivery-libs `mvn clean install`: dto 55 (`RequestIdsTest` 12), id 44, web 88 (`RequestIdFilterTest` 7,
  `CommonWebAutoConfigurationTest` thêm 2), api-flow 204, tất cả pass
- Backend: `RequestIdServerInterceptorTest` 7, `RequestIdClientInterceptorTest` 3, `RequestIdGlobalFilterTest` 6. Thử
  phá: bỏ đặt ID trong `onHalfClose` → 6/6 test của server interceptor fail
- `ThreadContext` cần `log4j-core` trong test (chỉ `log4j-api` thì nó không lưu gì): đã thêm scope `test` cho
  fd-grpc-api và common-web-lib; service có sẵn qua `common-lib`
- Chạy thật auth, order, gateway: login sai với `X-Request-Id: demo-login-001` → response trả đúng ID, 3 dòng log của
  auth đều có `[demo-login-001]`; không gửi ID → gateway tạo mới, log `[AUTH] ... requestId=<id>`; ID `bad id` → bị
  thay, response chỉ một giá trị; gọi thẳng order → order tự tạo ID

### 2.5 Server dừng việc khi bên gọi đã bỏ cuộc → gộp vào 2.1

Không cần code chung: chỉ là quy ước cho RPC nặng (kiểm tra `Context.current().isCancelled()` giữa các bước), đã ghi
trong mẫu server của 2.1. Deadline truyền xuống lời gọi tiếp theo đã tự có (xem 2.3).

### 2.6 Hỗ trợ test gRPC cho service

- [x] `InProcessGrpcServer` (fd-grpc-api, package `com.fooddelivery.grpc.testing`): `start(services...)` dựng server
      in-process với đúng các interceptor và thứ tự của service đang chạy (`RequestIdServerInterceptor` ->
      `GrpcLoggingInterceptor` -> exception handler + `BusinessExceptionGrpcHandler`), `channel()`, `stopServer()`
      (giả lập service nhận tắt), `close()`
- [x] Đóng gói `test-jar` chỉ chứa helper (không kèm test hay `EchoService`); version ở `dependencyManagement` của pom
      gốc; service dùng `<type>test-jar</type><scope>test</scope>`
- [x] Mẫu test server và client trong `docs/grpc-conventions.md` dùng helper; làm luôn thay vì chờ gRPC đầu tiên

**Lý do:** mỗi test của server tự dựng server và gắn interceptor; chép sai thứ tự là test không còn giống lúc chạy
thật (đã gặp: log không ghi lời gọi lỗi vì sai thứ tự).
**Tác dụng:** test của mọi `XxxGrpcService` kiểm đúng status, `error-code` mà client thật nhận, và ngắn hơn.

Kết quả: `InProcessGrpcServerTest` 3; `GrpcLoggingInterceptorTest` chuyển sang helper, không đổi assertion, vẫn pass;
fd-grpc-api 110 test. Trên bản sao `/tmp/fd-trial`, `UserGrpcServiceTest` của agent chuyển sang helper qua test jar:
3/3 pass, auth 328 test pass.

---

## Mức 3. Bảo mật giữa các service

### 3.1 Xác thực bên gọi gRPC

- [ ] Server interceptor trong `fd-grpc-api`: thiếu hoặc sai credential thì trả `UNAUTHENTICATED`, không vào service
- [ ] Client interceptor tự gắn credential của service vào mọi lời gọi
- [ ] Credential lấy từ cấu hình hoặc biến môi trường, không commit giá trị thật; kiểm tra lúc startup (thiếu thì
      service không khởi động)
- [ ] Cách làm: bắt đầu bằng token riêng cho từng service gọi (đơn giản), hoặc JWT ngắn hạn do auth ký cho service.
      Khi đã có mTLS (mục 3.3) thì có thể dùng chứng chỉ client thay cho token
- [ ] Health service `grpc.health.v1.Health` được miễn kiểm tra để probe vẫn chạy

**Lý do:** mục 1.2 chỉ chặn bên ngoài máy; bên trong, bất kỳ process nào cũng gọi được gRPC. Header
`x-caller-service` không giúp được, vì bên gọi tự khai: ai cũng ghi được `service-order`.
**Tác dụng:** chỉ service có credential mới gọi được, và `x-caller-service` trở thành thông tin đáng tin (lấy từ
credential, không lấy từ header tự khai). Đây là điều kiện để làm phân quyền ở mục 3.2.
**Làm ở đâu:** `fd-grpc-api` (`GrpcSupportAutoConfiguration` đăng ký hai interceptor mới), `application.properties`
của service có gRPC, README.
**Kiểm tra:** `grpcurl` không có credential nhận `UNAUTHENTICATED`; service có credential gọi bình thường; test
interceptor phủ các trường hợp thiếu, sai, đúng.

### 3.2 Phân quyền theo service gọi

- [ ] Mỗi gRPC method khai báo danh sách service được gọi, vd. `RestaurantService/GetRestaurant`: `service-order`
- [ ] Service gọi không có trong danh sách thì trả `PERMISSION_DENIED`

**Lý do:** sau mục 3.1, mọi service đã xác thực đều gọi được mọi method. Khi có API nhạy cảm (khoá tài khoản, đổi
role), không nên để chat-service cũng gọi được.
**Tác dụng:** nguyên tắc "quyền tối thiểu": nếu một service bị chiếm quyền thì chỉ lộ những gì service đó vốn được
phép. Danh sách quyền cũng cho thấy ngay service nào phụ thuộc service nào.
**Làm ở đâu:** server interceptor của mục 3.1 (đọc danh sách từ cấu hình), `ARCHITECTURE.md`.
**Kiểm tra:** test interceptor: service được phép thì qua, service khác nhận `PERMISSION_DENIED`.

### 3.3 Mã hoá đường truyền (TLS / mTLS)

- [ ] Server gRPC dùng TLS; client tin CA nội bộ (cấu hình SSL bundle của Spring Boot)
- [ ] Nâng lên mTLS khi đã có cách cấp và xoay vòng chứng chỉ (cert-manager, service mesh như Istio/Linkerd)
- [ ] Dev vẫn được chạy plaintext bằng profile

**Lý do:** dữ liệu và (sau mục 3.1) credential đi plaintext. Ai nghe được mạng nội bộ đều đọc được, và lấy được token
để giả làm service khác.
**Tác dụng:** không nghe lén, không sửa được dữ liệu giữa đường. mTLS còn xác thực hai chiều bằng chứng chỉ, có thể
thay token của mục 3.1.
**Làm ở đâu:** `application.properties` (SSL bundle), cấu hình deploy. Nếu dùng service mesh thì mesh làm, code không
đổi.
**Kiểm tra:** client không có CA đúng không kết nối được; bắt gói tin trên cổng gRPC không đọc được nội dung.

---

## Mức 4. Vận hành ở production (khi có nhiều instance, nhiều lưu lượng)

### 4.1 Cấu hình địa chỉ theo môi trường và cân bằng tải

- [ ] Target của channel lấy từ biến môi trường khi deploy (vd. `SPRING_GRPC_CLIENT_CHANNEL_RESTAURANT_TARGET`),
      không sửa file
- [ ] Nhiều instance: dùng `dns:///<tên-service>:<port>` với load balancing `round_robin` ở client, hoặc để service
      mesh cân bằng tải

**Lý do:** `static://localhost:<port>` chỉ đúng khi chạy trên máy dev. gRPC giữ **một kết nối HTTP/2 lâu dài** và gửi
mọi lời gọi qua kết nối đó, nên load balancer L4 đặt phía trước sẽ dồn hết lưu lượng của một client vào một instance.
**Tác dụng:** chạy nhiều instance là tải được chia đều; một instance chết thì client tự chuyển sang instance khác.
**Làm ở đâu:** `application.properties` của service gọi, cấu hình deploy, README.
**Kiểm tra:** chạy 2 instance service nhận, gọi nhiều lần, log của cả hai instance đều có lời gọi.

### 4.2 Retry có giới hạn cho lỗi tạm thời

- [ ] Bật retry policy trong service config của channel: chỉ cho `UNAVAILABLE`, tối đa 2-3 lần, có backoff
- [ ] Chỉ áp dụng cho RPC an toàn khi gọi lại (chỉ đọc); RPC ghi dữ liệu thì không retry, hoặc phải có idempotency key
- [ ] Tổng thời gian retry vẫn nằm trong deadline (deadline tính cho cả cuộc gọi)

**Lý do:** khi service nhận restart hoặc một instance vừa chết, lời gọi đầu tiên có thể lỗi `UNAVAILABLE` dù gọi lại
ngay thì thành công. Không retry thì lỗi đó trả thẳng 503 cho người dùng.
**Tác dụng:** người dùng không thấy lỗi khi deploy hay khi một instance gặp sự cố ngắn. Giới hạn số lần và backoff
tránh việc retry dồn dập làm service đang yếu chết hẳn.
**Làm ở đâu:** cấu hình channel (hoặc `GrpcClientConfig`), test với server in-process lỗi lần đầu.
**Kiểm tra:** test: server lỗi `UNAVAILABLE` một lần rồi trả đúng thì client nhận kết quả; lỗi `NOT_FOUND` thì không
retry.

### 4.3 Metrics và tracing

- [ ] Bật observation của Spring gRPC với Micrometer + Actuator: số lời gọi, độ trễ (p50/p95/p99), tỉ lệ lỗi theo
      method và status
- [ ] Thêm tracing (Micrometer Tracing + OpenTelemetry) để xem một request đi qua gateway và các service trên một
      biểu đồ. Khi có tracing thì request ID của mục 2.4 lấy theo trace ID
- [ ] Cảnh báo khi tỉ lệ lỗi hoặc p99 vượt ngưỡng

**Lý do:** log trả lời câu hỏi "chuyện gì xảy ra với request này", nhưng không trả lời "hệ thống có đang chậm dần
không, chậm ở đâu". Hiện chỉ có log.
**Tác dụng:** thấy sớm một service chậm đi trước khi người dùng thấy 503; khi có sự cố thì biết ngay service nào là
nút thắt.
**Làm ở đâu:** pom của service (Actuator, Micrometer registry), `application.properties`, hạ tầng giám sát
(Prometheus/Grafana hoặc tương đương).
**Kiểm tra:** `/actuator/metrics` có metric gRPC theo method; trace một request thấy đủ các service.

### 4.4 Probe và tắt máy êm

- [ ] Liveness/readiness probe của K8s dùng `grpc.health.v1.Health`, trạng thái `NOT_SERVING` khi DB chưa sẵn sàng
- [ ] Tắt máy êm: khi nhận SIGTERM, server ngừng nhận lời gọi mới nhưng chờ lời gọi đang chạy xong (có thời hạn)

**Lý do:** khi deploy bản mới, nếu instance cũ tắt ngay thì các lời gọi đang chạy bị cắt và client nhận lỗi.
**Tác dụng:** deploy không gây lỗi cho người dùng; instance chưa sẵn sàng (DB chưa kết nối) không nhận lưu lượng.
**Làm ở đâu:** `application.properties` (graceful shutdown), manifest K8s.
**Kiểm tra:** gọi liên tục trong lúc restart service nhận, không có lời gọi nào lỗi ngoài các lời gọi đã được retry.

### 4.5 Kiểm tra tương thích `.proto` tự động

- [ ] CI chạy `buf breaking` (hoặc công cụ tương đương) so `.proto` với nhánh `main`: đổi số field, đổi kiểu, xoá
      field không `reserved` thì build fail
- [ ] Thêm `buf lint` để thống nhất cách đặt tên (kiểm tự động một phần quy ước của mục 2.1)

**Lý do:** quy tắc "không đổi số và kiểu field" chỉ nằm trong tài liệu. Vi phạm thì vẫn compile được, và chỉ lỗi khi
service mới và cũ chạy cùng lúc (lúc deploy từng service một), tức là lỗi xuất hiện ở production.
**Tác dụng:** máy bắt lỗi phá hợp đồng ngay lúc review, không phụ thuộc người review có nhớ quy tắc hay không.
**Làm ở đâu:** cấu hình `buf` trong `fd-grpc-api`, pipeline CI.
**Kiểm tra:** thử đổi số field trong một nhánh, CI fail kèm thông báo rõ ràng.

### 4.6 Circuit breaker

- [ ] Bọc các lời gọi gRPC bằng circuit breaker (vd. Resilience4j): service nhận lỗi liên tục thì ngừng gọi một thời
      gian, trả ngay 503 `<SERVICE>_UNAVAILABLE`

**Lý do:** khi service nhận chết hẳn, mỗi request tới service gọi vẫn chờ đủ deadline rồi mới trả lỗi. Với lưu lượng
lớn, thread của service gọi bị giữ hết, và nó chết theo.
**Tác dụng:** lỗi của một service không lan sang service khác; người dùng nhận lỗi ngay thay vì chờ. Service nhận cũng
có thời gian hồi phục mà không bị dồn request.
**Làm ở đâu:** helper chung ở `fd-grpc-api` (cạnh helper lỗi của 2.2), cấu hình ngưỡng.
**Kiểm tra:** test với server in-process luôn lỗi: sau ngưỡng, client trả 503 ngay mà không gọi server.

---

## Mức 5. Khi nghiệp vụ cần (chưa làm trước)

### 5.1 RPC lấy nhiều bản ghi một lần

- [ ] Khi có màn hình cần nhiều bản ghi của service khác (vd. danh sách đơn hàng kèm tên nhà hàng), thêm RPC dạng
      `GetXxxs(repeated int64 ids)` có giới hạn số ID, thay vì gọi `GetXxx` trong vòng lặp

**Lý do:** gọi RPC lấy một bản ghi trong vòng lặp là lỗi N+1 qua mạng: 50 đơn hàng thành 50 lời gọi gRPC và 50 query.
**Tác dụng:** một lời gọi, một query `IN (...)`, nên độ trễ gần như không đổi theo số bản ghi.
**Làm ở đâu:** `.proto`, `XxxGrpcService`, service nghiệp vụ, client tương ứng.
**Kiểm tra:** test thành công, ID không tồn tại (bỏ qua hay lỗi: chốt trong comment `.proto`), vượt giới hạn số ID.

### 5.2 Stub bất đồng bộ và streaming

- [ ] Dùng `FutureStub` khi một request cần gọi nhiều service song song
- [ ] Dùng server streaming khi cần đẩy dữ liệu liên tục giữa các service, vd. vị trí shipper từ delivery

**Lý do:** blocking stub gọi tuần tự, nên tổng thời gian là tổng của từng lời gọi. Một số luồng (theo dõi giao hàng)
cần dữ liệu liên tục chứ không phải hỏi một lần.
**Tác dụng:** giảm độ trễ cho các luồng gọi nhiều service; dùng được thế mạnh của HTTP/2 mà REST không có.
**Làm ở đâu:** client của service cần; RPC streaming mới trong `.proto`.
**Kiểm tra:** test in-process cho từng RPC; đo thời gian trước và sau với API thật.

---

## Thứ tự đề xuất

| Lúc nào | Mục |
|---|---|
| Đã xong | 1.1, 1.2, 1.3 (1.4 bỏ) |
| Đã xong | 2.1, 2.2, 2.3, 2.4, 2.6 (2.5 gộp vào 2.1): **mức 2 xong** |
| Trước khi lên môi trường có người dùng thật | 3.1, 3.2, 4.1, 4.4 |
| Khi có hạ tầng (K8s, CI, giám sát) | 3.3, 4.2, 4.3, 4.5, 4.6 |
| Khi nghiệp vụ cần | 5.1, 5.2 |
