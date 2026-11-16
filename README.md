# Food Delivery Backend

Tài liệu này hướng dẫn build, chạy và thử. Cấu trúc bên trong (module, vai trò từng class, luồng chạy, sửa ở đâu) xem
[ARCHITECTURE.md](ARCHITECTURE.md); quy tắc làm việc xem [CLAUDE.md](CLAUDE.md).

## Build

Dùng **Maven Wrapper** `./mvnw` (Windows: `mvnw.cmd`) ở thư mục gốc, không dùng `mvn` cài trên máy: wrapper tự tải
đúng Maven 3.9.16 (ghi trong `.mvn/wrapper/maven-wrapper.properties`). Module `fd-grpc-api` sinh code gRPC bằng
`protobuf-maven-plugin`, plugin này cần Maven 3.9.6 trở lên.

```bash
./mvnw clean install                              # build và test mọi module
./mvnw spring-boot:run -pl fd-auth-service        # chạy một service
```

Thư viện dùng chung `food-delivery-libs` (repo riêng) phải được `mvn install` vào `~/.m2` trước.

## Chạy gateway, auth và order

1. PostgreSQL ở `localhost:5432` có hai database, tạo một lần bằng tay (bảng do Flyway tạo khi service khởi động):

   ```sql
   CREATE DATABASE "FD_auth_db";
   CREATE DATABASE "FD_order_db";
   ```

2. Khởi động, mỗi lệnh một terminal. Thứ tự không bắt buộc: gateway chỉ tải public key của auth ở request đầu tiên,
   order chỉ kết nối gRPC tới auth ở lời gọi đầu tiên.

   ```bash
   ./mvnw spring-boot:run -pl fd-auth-service       # HTTP 8081 + gRPC 9081
   ./mvnw spring-boot:run -pl fd-order-service      # 8083
   ./mvnw spring-boot:run -pl fd-gateway-service    # 8080
   ```

3. Thử bằng Postman: import `postman/fd-auth-service.postman_collection.json` (đăng ký, đăng nhập...) và
   `postman/fd-order-service.postman_collection.json` (đặt biến `identifier`, `password` rồi chạy cả collection).
   gRPC thử bằng `grpcurl`, xem mục gRPC trong `fd-auth-service/README.md`.

```
Client ──> gateway :8080 ── /auth/**  ─────────────────────────────> auth :8081
                         └─ /order/** (JwtAuth: kiểm tra token, gắn X-User-Id) ──> order :8083
                                                                        ├─ HTTP ─> auth :8081 /internal/users/{id}
                                                                        └─ gRPC ─> auth :9081 UserService/GetUser
```

Chi tiết từng bước và kết quả kiểm thử: [`docs/gateway-grpc-checklist.md`](docs/gateway-grpc-checklist.md).

## Service Ports

Client (frontend, Postman) chỉ gọi qua API Gateway `8080`; gateway chuyển `/auth/**` sang auth-service và
`/order/**` (sau khi kiểm tra token) sang order-service. Các port còn lại là nội bộ, không được mở ra ngoài: service
phía sau tin header `X-User-Id` do gateway gắn, và API `/internal/**`, gRPC chưa có xác thực giữa các service.

| Service | Port | Health check |
|---|---:|---|
| API Gateway | `8080` | `GET http://localhost:8080/gateway/health` |
| Auth Service | `8081` | `GET http://localhost:8081/auth/.well-known/jwks.json` |
| Auth Service (gRPC) | `9081` | `grpcurl -plaintext localhost:9081 grpc.health.v1.Health/Check` |
| Restaurant Service | `8082` | `GET http://localhost:8082/restaurant/health` |
| Order Service | `8083` | `GET http://localhost:8083/order/health` |
| Delivery Service | `8084` | `GET http://localhost:8084/delivery/health` |
| Chat Service | `8085` | `GET http://localhost:8085/chat/health` |

Hợp đồng gRPC giữa các service (file `.proto`) nằm trong module `fd-grpc-api`, ví dụ
`fd-grpc-api/src/main/proto/auth/v1/user_service.proto`.