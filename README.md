# Food Delivery Backend

## Build

Dùng **Maven Wrapper** `./mvnw` (Windows: `mvnw.cmd`) ở thư mục gốc, không dùng `mvn` cài trên máy: wrapper tự tải
đúng Maven 3.9.16 (ghi trong `.mvn/wrapper/maven-wrapper.properties`). Module `fd-grpc-api` sinh code gRPC bằng
`protobuf-maven-plugin`, plugin này cần Maven 3.9.6 trở lên.

```bash
./mvnw clean install                              # build và test mọi module
./mvnw spring-boot:run -pl fd-auth-service        # chạy một service
```

Thư viện dùng chung `food-delivery-libs` (repo riêng) phải được `mvn install` vào `~/.m2` trước.

## Service Ports

Client (frontend, Postman) chỉ gọi qua API Gateway `8080`; gateway chuyển `/auth/**` sang auth-service. Các port
còn lại là nội bộ, chỉ gọi thẳng khi debug.

| Service | Port | Health check |
|---|---:|---|
| API Gateway | `8080` | `GET http://localhost:8080/gateway/health` |
| Auth Service | `8081` | `GET http://localhost:8081/auth/health` |
| Auth Service (gRPC) | `9081` | `grpcurl -plaintext localhost:9081 grpc.health.v1.Health/Check` |
| Restaurant Service | `8082` | `GET http://localhost:8082/restaurant/health` |
| Order Service | `8083` | `GET http://localhost:8083/order/health` |
| Delivery Service | `8084` | `GET http://localhost:8084/delivery/health` |
| Chat Service | `8085` | `GET http://localhost:8085/chat/health` |

Hợp đồng gRPC giữa các service (file `.proto`) nằm trong module `fd-grpc-api`, ví dụ
`fd-grpc-api/src/main/proto/auth/v1/user_service.proto`.