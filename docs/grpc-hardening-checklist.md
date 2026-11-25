# Checklist: hoàn thiện gRPC giữa các service

Phạm vi: `fd-grpc-api`, gRPC server của `fd-auth-service`, gRPC client của `fd-order-service`, và mọi service có gRPC
sau này. Phần đã có (hợp đồng `.proto` có version, deadline, ánh xạ lỗi qua trailer `error-code`, log, header caller,
health service, test in-process) xem [`gateway-grpc-checklist.md`](gateway-grpc-checklist.md), file này không nhắc lại.

Các mục sắp theo **mức độ tăng dần**: mức 1 chỉ sửa cấu hình hoặc tài liệu, mức sau cần nhiều code và hạ tầng hơn.
Trong cùng một mức, mục đứng trước nên làm trước. Mỗi mục có:

- **Lý do**: vấn đề hiện tại.
- **Tác dụng**: làm xong thì được gì.
- **Làm ở đâu**: file hoặc class cần sửa.
- **Kiểm tra**: cách nghiệm thu. Chỉ tick khi kiểm tra đạt.

Mọi mục có code phải theo `CLAUDE.md`: có test trong cùng commit, cập nhật `ARCHITECTURE.md` khi thêm class, và chạy
`./mvnw clean install` vì `fd-grpc-api` là module dùng chung.

---

## Mức 1. Cấu hình và quy ước (làm ngay, gần như không có rủi ro)

### 1.1 Chỉ bật reflection ở môi trường dev

- [ ] Chuyển `spring.grpc.server.reflection.enabled=true` sang `application-dev.properties` (hoặc profile tương
      đương), file chung để `false`

**Lý do:** reflection đang bật trong `application.properties` chung. Comment có ghi "set false in production" nhưng
không có gì bắt buộc làm theo, nên chỉ cần quên một lần là production cũng bật.
**Tác dụng:** người ngoài không liệt kê được service và method của auth bằng `grpcurl list`. Muốn dò API thì phải
có file `.proto`. Dev vẫn dùng `grpcurl` và Postman như cũ.
**Làm ở đâu:** `fd-auth-service/src/main/resources/application*.properties`, mục gRPC trong `fd-auth-service/README.md`.
**Kiểm tra:** chạy auth không có profile dev thì `grpcurl -plaintext localhost:9081 list` báo lỗi reflection; bật
profile dev thì lệnh đó liệt kê được `fooddelivery.auth.v1.UserService`.

### 1.2 Chỉ mở cổng gRPC trong mạng nội bộ

- [ ] Server gRPC chỉ nghe trên địa chỉ nội bộ (property địa chỉ của server gRPC, hoặc network của Docker/K8s,
      hoặc firewall). Không publish cổng 9081 ra ngoài
- [ ] Ghi quy tắc này vào mục port của `README.md` và phần triển khai

**Lý do:** cổng 9081 hiện là plaintext và không xác thực. Ai kết nối được tới cổng đó đều gọi được `GetUser` với ID
bất kỳ và nhận số điện thoại, role của user. ID có cấu trúc dễ đoán (14 chữ số, có mã loại), nên dò lần lượt từng ID
không khó.
**Tác dụng:** đây là lớp bảo vệ rẻ nhất, chặn được truy cập từ Internet trong khi chờ mục 3.1. Lưu ý nó chỉ là tạm:
không chặn được một service nội bộ khác đã bị chiếm quyền.
**Làm ở đâu:** `application.properties` của auth, cấu hình deploy (docker-compose, K8s Service loại `ClusterIP`).
**Kiểm tra:** từ máy ngoài mạng nội bộ, `grpcurl` tới cổng 9081 không kết nối được; từ order vẫn gọi được.

### 1.3 Ghi rõ khi nào dùng gRPC, khi nào dùng HTTP `/internal/**`

- [ ] Thêm quy tắc vào mục "Quy tắc phụ thuộc" của `ARCHITECTURE.md`, vd. "service gọi service: gRPC; HTTP
      `/internal/**` chỉ dành cho bên gọi không phải service của dự án (JWKS cho gateway, webhook)"

**Lý do:** `ARCHITECTURE.md` đang ghi "gọi nhau qua gRPC (hoặc HTTP `/internal/**`)". Có hai cách mà không có quy tắc
chọn thì mỗi người làm một kiểu, và mỗi API nội bộ phải bảo mật, log, ánh xạ lỗi cho cả hai đường.
**Tác dụng:** chỉ còn một đường chính để đầu tư (xác thực, metrics, retry). Người mới không phải đoán nên chọn cách nào.
**Làm ở đâu:** `ARCHITECTURE.md` (mục 3 và bảng "việc thường gặp").
**Kiểm tra:** review tài liệu; API nội bộ mới tạo sau đó theo đúng quy tắc.

### 1.4 Chỉ trả dữ liệu mà bên gọi thật sự cần

- [ ] Xem lại các field của `UserResponse`, nhất là `phone_number`: nếu chưa service nào dùng thì bỏ
- [ ] Khi bỏ field: **không** dùng lại số của nó. Ghi `reserved 3; reserved "phone_number";` trong `.proto`

**Lý do:** mỗi field cá nhân trả qua API nội bộ là thêm một chỗ có thể rò rỉ (qua log, qua service bị chiếm quyền).
Hiện order chỉ dùng user để test, chưa có nghiệp vụ nào cần số điện thoại.
**Tác dụng:** thu hẹp dữ liệu nhạy cảm đi qua mạng. Sau này service nào cần thì thêm lại bằng một **số field mới**;
`.proto` cho phép thêm field mà không phá client cũ.
**Làm ở đâu:** `fd-grpc-api/src/main/proto/auth/v1/user_service.proto`, `UserGrpcService.toProto`, `AuthUser`,
`AuthGrpcClient` và test tương ứng.
**Kiểm tra:** `./mvnw clean install` pass; `GET /order/test/auth-grpc` trả user không có số điện thoại.

---

## Mức 2. Code dùng chung (làm trước khi có client gRPC thứ hai)

### 2.1 Đưa phần ánh xạ lỗi phía client vào `fd-grpc-api`

- [ ] Thêm vào `com.fooddelivery.grpc.support` một helper đổi `StatusRuntimeException` thành `BusinessException`:
      đọc trailer `error-code`, đổi gRPC status thành HTTP status (chiều ngược của
      `BusinessExceptionGrpcHandler.toGrpcStatus`)
- [ ] `AuthGrpcClient` dùng helper này, chỉ còn tự định nghĩa lỗi riêng của nó (`AUTH_UNAVAILABLE`,
      `AUTH_CALL_FAILED`)

**Lý do:** chiều server (HTTP status sang gRPC status) đã nằm chung một chỗ, nhưng chiều client (gRPC status sang
`BusinessException`) đang viết tay trong `AuthGrpcClient.toBusinessException`. Mỗi client mới (restaurant, delivery)
sẽ phải chép lại đoạn switch này. Bản chép dễ lệch nhau, vd. một client quên đọc trailer nên mất mã lỗi gốc.
**Tác dụng:** hai chiều ánh xạ nằm cạnh nhau trong cùng một module, nên test được chiều đi và về khớp nhau. Client
mới chỉ cần vài dòng.
**Làm ở đâu:** `fd-grpc-api` (class mới và test), `AuthGrpcClient`, `ARCHITECTURE.md`.
**Kiểm tra:** test của helper phủ từng status và các trường hợp có hoặc thiếu trailer; `AuthGrpcClientTest` vẫn pass,
không phải sửa assertion.

### 2.2 Truyền request ID xuyên suốt gateway → order → auth

- [ ] Gateway gắn header request ID (vd. `X-Request-Id`, tạo mới nếu client không gửi)
- [ ] Service đưa request ID vào context log (Log4j2 `ThreadContext`) ở đầu request
- [ ] Client interceptor trong `fd-grpc-api` chép request ID sang metadata gRPC; `GrpcLoggingInterceptor` đọc ra và
      ghi vào log. Thêm tên key vào `GrpcMetadataKeys`

**Lý do:** hiện log của gateway, order và auth là ba dòng độc lập. Khi một request lỗi, muốn biết dòng nào ở auth
thuộc request nào ở order thì chỉ đoán được theo thời gian.
**Tác dụng:** grep một ID là thấy cả chuỗi xử lý qua mọi service, nên debug lỗi giữa các service nhanh hơn hẳn. Đây
cũng là bước chuẩn bị cho tracing đầy đủ (mục 4.3).
**Làm ở đâu:** `fd-gateway-service` (filter), `common-web-lib` hoặc filter của từng service, `fd-grpc-api`
(`CallerServiceClientInterceptor` hoặc interceptor mới, `GrpcLoggingInterceptor`).
**Kiểm tra:** gọi `GET /order/test/auth-grpc` qua gateway, cả ba service log cùng một request ID.

### 2.3 Server dừng việc khi bên gọi đã bỏ cuộc

- [ ] Quy ước cho RPC nặng (nhiều query, vòng lặp, gọi tiếp service khác): kiểm tra `Context.current().isCancelled()`
      giữa các bước, và khi gọi tiếp service khác thì truyền deadline còn lại xuống
- [ ] Ghi quy ước vào `ARCHITECTURE.md`; `GetUser` hiện tại không cần sửa

**Lý do:** order gửi deadline 2s sang auth. Khi hết hạn, order đã trả 503 nhưng auth vẫn chạy tiếp tới hết. Với
`GetUser` (một query theo khoá chính) thì không đáng kể, nhưng RPC nặng sẽ tốn DB cho kết quả không ai nhận.
**Tác dụng:** khi một service quá tải, nó không phải làm thêm những việc đã vô ích, nên hồi phục nhanh hơn thay vì
càng ngày càng chậm.
**Làm ở đâu:** quy ước trong `ARCHITECTURE.md`; áp dụng trong các `*GrpcService` mới.
**Kiểm tra:** test của RPC nặng, dùng deadline ngắn (vài trăm ms), xác nhận server dừng ở bước kế tiếp.

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

**Lý do:** đây là vấn đề lớn nhất hiện tại. Mục 1.2 chỉ chặn bên ngoài mạng; bên trong mạng, bất kỳ process nào cũng
đọc được mọi user. Header `x-caller-service` không giúp được, vì bên gọi tự khai: ai cũng ghi được `service-order`.
**Tác dụng:** chỉ service có credential mới gọi được, và `x-caller-service` trở thành thông tin đáng tin (lấy từ
credential, không lấy từ header tự khai). Đây là điều kiện để làm phân quyền ở mục 3.2.
**Làm ở đâu:** `fd-grpc-api` (`GrpcSupportAutoConfiguration` đăng ký hai interceptor mới), `application.properties`
của auth và order, README.
**Kiểm tra:** `grpcurl` không có credential nhận `UNAUTHENTICATED`; order có credential gọi bình thường; test
interceptor phủ các trường hợp thiếu, sai, đúng.

### 3.2 Phân quyền theo service gọi

- [ ] Mỗi gRPC method khai báo danh sách service được gọi, vd. `UserService/GetUser`: `service-order`,
      `service-delivery`
- [ ] Service gọi không có trong danh sách thì trả `PERMISSION_DENIED`

**Lý do:** sau mục 3.1, mọi service đã xác thực đều gọi được mọi method. Khi có thêm API nhạy cảm (khoá tài khoản,
đổi role), không nên để chat-service cũng gọi được.
**Tác dụng:** nguyên tắc "quyền tối thiểu": nếu một service bị chiếm quyền thì chỉ lộ những gì service đó vốn được
phép. Danh sách quyền cũng cho thấy ngay service nào phụ thuộc service nào.
**Làm ở đâu:** server interceptor của mục 3.1 (đọc danh sách từ cấu hình), `ARCHITECTURE.md`.
**Kiểm tra:** test interceptor: service được phép thì qua, service khác nhận `PERMISSION_DENIED`.

### 3.3 Mã hoá đường truyền (TLS / mTLS)

- [ ] Server gRPC dùng TLS; client tin CA nội bộ (cấu hình SSL bundle của Spring Boot)
- [ ] Nâng lên mTLS khi đã có cách cấp và xoay vòng chứng chỉ (cert-manager, service mesh như Istio/Linkerd)
- [ ] Dev vẫn được chạy plaintext bằng profile

**Lý do:** hiện dữ liệu user và (sau mục 3.1) credential đi plaintext. Ai nghe được mạng nội bộ đều đọc được, và lấy
được token để giả làm order.
**Tác dụng:** không nghe lén, không sửa được dữ liệu giữa đường. mTLS còn xác thực hai chiều bằng chứng chỉ, có thể
thay token của mục 3.1.
**Làm ở đâu:** `application.properties` (SSL bundle), cấu hình deploy. Nếu dùng service mesh thì mesh làm, code không
đổi.
**Kiểm tra:** client không có CA đúng không kết nối được; bắt gói tin trên cổng 9081 không đọc được nội dung.

---

## Mức 4. Vận hành ở production (khi có nhiều instance, nhiều lưu lượng)

### 4.1 Cấu hình địa chỉ theo môi trường và cân bằng tải

- [ ] Target của channel lấy từ biến môi trường khi deploy (`SPRING_GRPC_CLIENT_CHANNEL_AUTH_TARGET`), không sửa file
- [ ] Nhiều instance auth: dùng `dns:///<tên-service>:9081` với load balancing `round_robin` ở client, hoặc
      để service mesh cân bằng tải

**Lý do:** `static://localhost:9081` chỉ đúng khi chạy trên máy dev. gRPC giữ **một kết nối HTTP/2 lâu dài** và gửi
mọi lời gọi qua kết nối đó, nên load balancer L4 đặt phía trước sẽ dồn hết lưu lượng của một client vào một instance.
**Tác dụng:** chạy nhiều instance auth là tải được chia đều; một instance chết thì client tự chuyển sang instance khác.
**Làm ở đâu:** `application.properties` của order, cấu hình deploy, README.
**Kiểm tra:** chạy 2 instance auth, gọi nhiều lần, log của cả hai instance đều có lời gọi.

### 4.2 Retry có giới hạn cho lỗi tạm thời

- [ ] Bật retry policy trong service config của channel: chỉ cho `UNAVAILABLE`, tối đa 2-3 lần, có backoff
- [ ] Chỉ áp dụng cho RPC an toàn khi gọi lại (đọc dữ liệu như `GetUser`); RPC ghi dữ liệu thì không retry, hoặc phải
      có idempotency key
- [ ] Tổng thời gian retry vẫn nằm trong deadline (deadline tính cho cả cuộc gọi)

**Lý do:** khi auth restart hoặc một instance vừa chết, lời gọi đầu tiên có thể lỗi `UNAVAILABLE` dù gọi lại ngay thì
thành công. Hiện tại lỗi đó trả thẳng 503 cho người dùng.
**Tác dụng:** người dùng không thấy lỗi khi deploy hay khi một instance gặp sự cố ngắn. Giới hạn số lần và backoff
tránh việc retry dồn dập làm service đang yếu chết hẳn.
**Làm ở đâu:** cấu hình channel trong order (hoặc `GrpcClientConfig`), test với server in-process lỗi lần đầu.
**Kiểm tra:** test: server lỗi `UNAVAILABLE` một lần rồi trả đúng thì client nhận kết quả; lỗi `NOT_FOUND` thì không
retry.

### 4.3 Metrics và tracing

- [ ] Bật observation của Spring gRPC với Micrometer + Actuator: số lời gọi, độ trễ (p50/p95/p99), tỉ lệ lỗi theo
      method và status
- [ ] Thêm tracing (Micrometer Tracing + OpenTelemetry) để xem một request đi qua gateway → order → auth trên một
      biểu đồ. Khi có tracing thì request ID của mục 2.2 lấy theo trace ID
- [ ] Cảnh báo khi tỉ lệ lỗi hoặc p99 vượt ngưỡng

**Lý do:** log trả lời câu hỏi "chuyện gì xảy ra với request này", nhưng không trả lời "hệ thống có đang chậm dần
không, chậm ở đâu". Hiện chỉ có log.
**Tác dụng:** thấy sớm auth chậm đi trước khi người dùng thấy 503; khi có sự cố thì biết ngay service nào là nút thắt.
**Làm ở đâu:** pom của service (Actuator, Micrometer registry), `application.properties`, hạ tầng giám sát
(Prometheus/Grafana hoặc tương đương).
**Kiểm tra:** `/actuator/metrics` có metric gRPC theo method; trace một request thấy đủ ba service.

### 4.4 Probe và tắt máy êm

- [ ] Liveness/readiness probe của K8s dùng `grpc.health.v1.Health` (đã có sẵn), trạng thái `NOT_SERVING` khi DB chưa
      sẵn sàng
- [ ] Tắt máy êm: khi nhận SIGTERM, server ngừng nhận lời gọi mới nhưng chờ lời gọi đang chạy xong (có thời hạn)

**Lý do:** health service hiện chỉ để thử bằng `grpcurl`. Khi deploy bản mới, nếu instance cũ tắt ngay thì các lời gọi
đang chạy bị cắt và client nhận lỗi.
**Tác dụng:** deploy không gây lỗi cho người dùng; instance chưa sẵn sàng (DB chưa kết nối) không nhận lưu lượng.
**Làm ở đâu:** `application.properties` (graceful shutdown), manifest K8s.
**Kiểm tra:** gọi liên tục trong lúc restart auth, không có lời gọi nào lỗi ngoài các lời gọi đã được retry.

### 4.5 Kiểm tra tương thích `.proto` tự động

- [ ] CI chạy `buf breaking` (hoặc công cụ tương đương) so `.proto` với nhánh `main`: đổi số field, đổi kiểu, xoá
      field không `reserved` thì build fail
- [ ] Thêm `buf lint` để thống nhất cách đặt tên

**Lý do:** quy tắc "không đổi số và kiểu field" đang chỉ nằm trong comment và `ARCHITECTURE.md`. Vi phạm thì vẫn
compile được, và chỉ lỗi khi service mới và cũ chạy cùng lúc (lúc deploy từng service một), tức là lỗi xuất hiện ở
production.
**Tác dụng:** máy bắt lỗi phá hợp đồng ngay lúc review, không phụ thuộc người review có nhớ quy tắc hay không.
**Làm ở đâu:** cấu hình `buf` trong `fd-grpc-api`, pipeline CI.
**Kiểm tra:** thử đổi số field trong một nhánh, CI fail kèm thông báo rõ ràng.

### 4.6 Circuit breaker

- [ ] Bọc các lời gọi gRPC bằng circuit breaker (vd. Resilience4j): auth lỗi liên tục thì ngừng gọi một thời gian,
      trả ngay 503 `AUTH_UNAVAILABLE`

**Lý do:** khi auth chết hẳn, mỗi request tới order vẫn chờ đủ 2s deadline rồi mới trả lỗi. Với lưu lượng lớn, thread
của order bị giữ hết, và order chết theo auth.
**Tác dụng:** lỗi của một service không lan sang service khác; người dùng nhận lỗi ngay thay vì chờ. Auth cũng có
thời gian hồi phục mà không bị dồn request.
**Làm ở đâu:** `AuthGrpcClient` (hoặc helper chung ở `fd-grpc-api` nếu nhiều client cần), cấu hình ngưỡng.
**Kiểm tra:** test với server in-process luôn lỗi: sau ngưỡng, client trả 503 ngay mà không gọi server.

---

## Mức 5. Khi nghiệp vụ cần (chưa làm trước)

### 5.1 RPC lấy nhiều bản ghi một lần

- [ ] Thêm `GetUsers(repeated int64 user_ids)` (có giới hạn số ID) khi có màn hình cần nhiều user, vd. danh sách đơn
      hàng kèm tên khách

**Lý do:** gọi `GetUser` trong vòng lặp là lỗi N+1 qua mạng: 50 đơn hàng thành 50 lời gọi gRPC và 50 query.
**Tác dụng:** một lời gọi, một query `IN (...)`, nên độ trễ gần như không đổi theo số bản ghi.
**Làm ở đâu:** `.proto`, `UserGrpcService`, `UserQueryService`, client tương ứng.
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
| Ngay bây giờ | 1.1, 1.2, 1.3, 1.4 |
| Trước khi viết client gRPC thứ hai | 2.1, 2.2 |
| Trước khi lên môi trường có người dùng thật | 3.1, 3.2, 4.1, 4.4 |
| Khi có hạ tầng (K8s, CI, giám sát) | 3.3, 4.2, 4.3, 4.5, 4.6 |
| Khi nghiệp vụ cần | 2.3, 5.1, 5.2 |
