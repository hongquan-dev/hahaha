# fd-order-service

Service quản lý **đơn hàng** của ứng dụng giao đồ ăn. Chạy ở port `8083`, client gọi qua gateway (`8080`).

Hiện mới có nền tảng (API flow, database, gọi sang auth-service) và hai API test so sánh HTTP với gRPC; nghiệp vụ đơn
hàng chưa làm.

---

## 1. Chạy service

**Yêu cầu:** Java 21; PostgreSQL ở `localhost:5432` có database `FD_order_db` (tạo một lần:
`CREATE DATABASE "FD_order_db";`, chưa có migration nên chưa có bảng); thư viện `food-delivery-libs` đã
`mvn install`.

```bash
# Từ thư mục gốc của repo
./mvnw spring-boot:run -pl fd-order-service
./mvnw test -pl fd-order-service
```

Order khởi động được khi auth-service chưa chạy: kênh gRPC chỉ kết nối ở lời gọi đầu tiên.

---

## 2. Danh sách API

| Method | Path | Thành công | Mô tả |
|---|---|---|---|
| `GET` | `/order/health` | 200 + text | Health check, không cần token |
| `GET` | `/order/test/auth-http` | 200 + `AuthCallResult` | User đang đăng nhập, lấy từ auth-service qua **HTTP** |
| `GET` | `/order/test/auth-grpc` | 200 + `AuthCallResult` | Như trên, qua **gRPC** |

```json
{
  "success": true, "status": 200, "code": "SUCCESS",
  "data": {
    "transport": "gRPC",
    "elapsedMs": 4,
    "user": { "id": 17947973411001, "username": "p0test_97341", "phoneNumber": "+84994797341",
              "role": "CUSTOMER", "active": true }
  }
}
```

`elapsedMs` là thời gian của riêng lời gọi sang auth-service. Hai API phải trả cùng `user`.

Postman: [`postman/fd-order-service.postman_collection.json`](../postman/fd-order-service.postman_collection.json).

---

## 3. Luồng xử lý

```
Client ─> gateway :8080  JwtAuth: kiểm tra cookie access_token (401/503 nếu sai), xoá X-User-Id client gửi,
          │              gắn X-User-Id / X-User-Role từ token
          ▼
order :8083
  ├─ api-routes.xml         API chưa khai báo                       → 404 API_NOT_FOUND
  ├─ AuthTestValidator      thiếu / sai X-User-Id (gọi bỏ qua gateway) → 401 UNAUTHENTICATED
  ├─ AuthTestController     lấy X-User-Id, gọi service
  └─ AuthCallTestService    đo thời gian, gọi một trong hai client
       ├─ AuthHttpClient    RestClient → GET  :8081/internal/users/{userId}
       └─ AuthGrpcClient    stub       → gRPC :9081 UserService/GetUser (deadline mỗi lời gọi)
```

Order **không** đọc token: nó tin `X-User-Id` của gateway. Vì vậy port 8083 không được mở ra ngoài.

Hai client có cùng đầu vào, cùng kết quả (`AuthUser`) và cùng cách báo lỗi, nên phần còn lại của order không phụ thuộc
vào transport; protobuf và status gRPC chỉ nằm trong `AuthGrpcClient`.

---

## 4. Lỗi

| HTTP | `code` | Khi nào |
|---:|---|---|
| 401 | `UNAUTHENTICATED` | Thiếu/sai token (gateway trả), hoặc gọi thẳng order không có `X-User-Id` |
| 401 | `TOKEN_EXPIRED` | Token hết hạn (gateway trả); client refresh rồi gọi lại |
| 404 | `USER_NOT_FOUND` | auth-service không có user này (HTTP 404 / gRPC `NOT_FOUND` + trailer `error-code`) |
| 502 | `AUTH_CALL_FAILED` | auth-service trả 5xx / status gRPC khác, hoặc câu trả lời không đọc được |
| 503 | `AUTH_UNAVAILABLE` | auth-service không kết nối được hoặc quá `services.auth.timeout` (2s) |
| 503 | `AUTH_UNAVAILABLE` | (gateway) chưa từng lấy được public key của auth-service |

---

## 5. Cấu hình (`application.properties`)

| Property | Giá trị | Ý nghĩa |
|---|---|---|
| `services.auth.http-url` | `http://localhost:8081` | Base URL API HTTP của auth-service |
| `services.auth.timeout` | `2s` (mặc định) | Thời gian chờ tối đa mỗi lời gọi: connect + read (HTTP), deadline (gRPC) |
| `spring.grpc.client.channel.auth.target` | `static://localhost:9081` | Địa chỉ kênh gRPC `auth` |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/FD_order_db` | Database của order |

---

## 6. Cấu trúc source code

```
src/main/java/com/fooddelivery/order/
├── OrderApplication.java         entry point, @ConfigurationPropertiesScan
├── client/
│   ├── AuthHttpClient            gọi auth qua HTTP, ánh xạ lỗi thành BusinessException
│   ├── AuthGrpcClient            gọi auth qua gRPC (bọc stub), ánh xạ status thành BusinessException
│   └── AuthUser                  user trả về, chung cho hai client
│                                 (header x-caller-service do CallerServiceClientInterceptor của fd-grpc-api gắn)
├── config/
│   ├── AuthClientProperties      bind services.auth.*
│   └── GrpcClientConfig          @ImportGrpcClients: tạo bean UserServiceBlockingStub trên kênh "auth"
├── controller/
│   ├── OrderController           GET /order/health
│   └── AuthTestController        GET /order/test/auth-http, /order/test/auth-grpc
├── dto/AuthCallResult            transport + elapsedMs + user
├── service/AuthCallTestService   đo thời gian, chọn client
└── validation/AuthTestValidator  kiểm tra X-User-Id

src/main/resources/
├── api-routes.xml                khai báo 3 route
└── application.properties
```

---

## 7. Kiểm thử

Unit test: 15 test, `./mvnw test -pl fd-order-service`.

| Test | Số test | Nội dung chính |
|---|---:|---|
| `AuthHttpClientTest` | 6 | Với HTTP server thật thay cho auth: đọc `data`; 404 giữ mã lỗi; 5xx → 502; thiếu `data` → 502; trả lời chậm → 503 sau timeout; server tắt → 503 |
| `AuthGrpcClientTest` | 7 | Với gRPC server in-process: đủ field và gửi `x-caller-service`; không có SĐT → `null`; `NOT_FOUND`/`INVALID_ARGUMENT` giữ mã lỗi từ trailer; không trả lời trước deadline → 503; server tắt → 503; status khác → 502 |
| `AuthTestValidatorTest` | 2 | `X-User-Id` hợp lệ; thiếu, rỗng, sai định dạng → 401 |
