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
java -jar fd-auth-service/target/fd-auth-service-1.0.0.jar   # chạy jar đã build (đủ thư viện bên trong)
```

Thư viện dùng chung `food-delivery-libs` (repo riêng) phải được `mvn install` vào `~/.m2` trước.

## Tạo file cấu hình trước khi chạy

File `application.properties` của các service **không có trên git** (chứa mật khẩu database, khoá ký token). Git chỉ
có file mẫu `application.properties.example` nằm cạnh chỗ của nó, trong `src/main/resources/` của từng service. Thiếu
file thật thì service không khởi động được.

1. Tạo file thật từ file mẫu, một lần sau khi clone (lệnh không ghi đè file đã có):

   ```bash
   for f in fd-*/src/main/resources/application.properties.example; do cp -n "$f" "${f%.example}"; done
   ```

   Windows: trong mỗi thư mục `fd-*/src/main/resources/`, copy `application.properties.example` thành
   `application.properties`.

2. Điền giá trị thật vào các chỗ đánh dấu:

   | Service | Property | Giá trị |
   |---|---|---|
   | auth, order, restaurant | `spring.datasource.password` (thay `CHANGE_ME`) | Mật khẩu PostgreSQL trên máy bạn; sai user hay URL thì sửa luôn `spring.datasource.username` / `url` |
   | auth | `auth.jwt.private-key`, `auth.jwt.public-key` (thay `PASTE_...`) | Cặp khoá RSA tự sinh bằng lệnh bên dưới |

   Sinh cặp khoá RSA rồi in ra phần Base64 trên một dòng, dán vào hai property trên:

   ```bash
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
   openssl pkey -in private.pem -pubout -out public.pem
   grep -v -- '-----' private.pem | tr -d '\n'; echo    # -> auth.jwt.private-key
   grep -v -- '-----' public.pem | tr -d '\n'; echo     # -> auth.jwt.public-key
   rm private.pem public.pem
   ```

   Mỗi máy dùng cặp khoá riêng cũng được: gateway lấy public key của auth qua JWKS, không cần cấu hình gì thêm.

3. Các property còn lại giữ nguyên giá trị mẫu là chạy được trên máy dev. Ý nghĩa từng property có comment ngay trong
   file; riêng `auth.*` xem thêm mục 8 của [fd-auth-service/README.md](fd-auth-service/README.md).

Khi pull code mới, so file của mình với file mẫu (vd. `diff fd-auth-service/src/main/resources/application.properties{,.example}`)
để thêm các property mới.

## Chạy gateway, auth, order và restaurant

1. PostgreSQL ở `localhost:5432` có ba database, tạo một lần bằng tay (bảng do Flyway tạo khi service khởi động):

   ```sql
   CREATE DATABASE "FD_auth_db";
   CREATE DATABASE "FD_order_db";
   CREATE DATABASE "FD_restaurant_db";
   ```

2. Khởi động, mỗi lệnh một terminal. Thứ tự không bắt buộc: gateway chỉ tải public key của auth ở request đầu tiên.

   ```bash
   ./mvnw spring-boot:run -pl fd-auth-service       # 8081
   ./mvnw spring-boot:run -pl fd-order-service      # 8083
   ./mvnw spring-boot:run -pl fd-restaurant-service # 8082
   ./mvnw spring-boot:run -pl fd-gateway-service    # 8090
   ```

3. Thử bằng Postman: mỗi API có một file cURL trong `postman/<service>/`, copy toàn bộ rồi dán vào thanh URL của
   Postman (xem `postman/README.md`).

```
Client ──> gateway :8090 ── /auth/**  (login, register: RateLimit theo IP) ──> auth :8081
                         ├─ /order/** (JwtAuth: kiểm tra token, gắn X-User-Id, X-User-Role) ──> order :8083
                         └─ /restaurant/** (JwtAuth như order; role từng API do service kiểm tra) ──> restaurant :8082
```

Mỗi response có header `X-Request-Id`; mọi dòng log của request đó, ở mọi service, có cùng ID trong `[...]` sau tên
service (gateway ghi `requestId=` trong dòng log của nó). Khi client báo lỗi, tìm ID này trong log là thấy cả chuỗi xử
lý.

Chi tiết từng bước và kết quả kiểm thử: [`docs/gateway-grpc-checklist.md`](docs/gateway-grpc-checklist.md) (nhật ký
giai đoạn trước; gRPC `GetUser` trong đó đã bị xoá). Việc còn lại để gRPC sau này đồng nhất:
[`docs/grpc-hardening-checklist.md`](docs/grpc-hardening-checklist.md).

## Service Ports

Client (frontend, Postman) chỉ gọi qua API Gateway `8090`; gateway chuyển `/auth/**` sang auth-service,
`/order/**` và `/restaurant/**` (sau khi kiểm tra token) sang order-service và restaurant-service. Các port còn lại là nội bộ, không được mở ra ngoài: service
phía sau tin header `X-User-Id` do gateway gắn.

Vì vậy chỉ gateway nghe trên mọi địa chỉ. Các service khác chỉ nghe trên `127.0.0.1` (`server.address`; gRPC server
sau này cũng vậy, `spring.grpc.server.address`): máy khác trong LAN gọi thẳng `<IP>:8081` bị từ chối kết nối, còn
gateway, các service và Postman trên **cùng máy** vẫn gọi được. Khi deploy (Docker, K8s), đặt `SERVER_ADDRESS=0.0.0.0`
(và `SPRING_GRPC_SERVER_ADDRESS=0.0.0.0` nếu có gRPC) để các container gọi được nhau, và **không** publish các port
này ra ngoài; chỉ publish `8090`.

| Service | Port | Health check |
|---|---:|---|
| API Gateway | `8090` | `GET http://localhost:8090/gateway/health` |
| Auth Service | `8081` | `GET http://localhost:8081/auth/.well-known/jwks.json` |
| Restaurant Service | `8082` | `GET http://localhost:8082/restaurant/health` |
| Order Service | `8083` | `GET http://localhost:8083/order/health` |
| Delivery Service | `8084` | `GET http://localhost:8084/delivery/health` |
| Chat Service | `8085` | `GET http://localhost:8085/chat/health` |

Hiện chưa có API gRPC nào. Service gọi nhau chỉ qua gRPC; hợp đồng (file `.proto`) sẽ nằm trong
`fd-grpc-api/src/main/proto/<service>/v1/`, phần hỗ trợ dùng chung (lỗi, log, header caller) đã có sẵn trong
`fd-grpc-api`.