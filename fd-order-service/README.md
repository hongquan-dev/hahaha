# fd-order-service

Service quản lý **đơn hàng** của ứng dụng giao đồ ăn. Chạy ở port `8083`, client gọi qua gateway (`8090`).

Hiện mới có nền tảng (API flow, database) và health check; nghiệp vụ đơn hàng chưa làm.

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

---

## 2. Danh sách API

| Method | Path | Thành công | Mô tả |
|---|---|---|---|
| `GET` | `/order/health` | 200 + text | Health check, không cần token |

cURL từng API (copy, dán vào thanh URL của Postman): [`postman/fd-order-service/`](../postman/fd-order-service/).

---

## 3. Luồng xử lý

```
Client ─> gateway :8090  JwtAuth (trừ /order/health): kiểm tra cookie access_token (401/503 nếu sai), xoá
          │              X-User-Id client gửi, gắn X-User-Id / X-User-Role từ token
          ▼
order :8083
  ├─ api-routes.xml         API chưa khai báo → 404 API_NOT_FOUND
  └─ OrderController        GET /order/health
```

Order **không** đọc token: API cần người dùng sẽ tin `X-User-Id` của gateway. Vì vậy port 8083 không được mở ra
ngoài: order chỉ nghe trên `127.0.0.1` (`server.address`), máy khác gọi thẳng `<IP>:8083` với `X-User-Id` tự điền bị
từ chối kết nối.

---

## 4. Lỗi

| HTTP | `code` | Khi nào |
|---:|---|---|
| 404 | `API_NOT_FOUND` | Method + path chưa khai báo trong `api-routes.xml` |
| 401 | `UNAUTHENTICATED` | (gateway) thiếu/sai token ở API cần đăng nhập |
| 401 | `TOKEN_EXPIRED` | (gateway) token hết hạn; client refresh rồi gọi lại |
| 503 | `AUTH_UNAVAILABLE` | (gateway) chưa từng lấy được public key của auth-service |

---

## 5. Cấu hình (`application.properties`)

| Property | Giá trị | Ý nghĩa |
|---|---|---|
| `server.address` | `127.0.0.1` | Chỉ máy này gọi được; client đi qua gateway. Deploy: `SERVER_ADDRESS=0.0.0.0`, không publish port |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/FD_order_db` | Database của order |

---

## 6. Cấu trúc source code

```
src/main/java/com/fooddelivery/order/
├── OrderApplication.java         entry point
└── controller/OrderController    GET /order/health

src/main/resources/
├── api-routes.xml                khai báo 1 route
└── application.properties
```

---

## 7. Kiểm thử

Chưa có unit test: `OrderController` chỉ trả text cố định (không cần test theo `CLAUDE.md`). Thử bằng file cURL trong
`postman/fd-order-service/`.
