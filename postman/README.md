# cURL của các API

Mỗi API HTTP của backend có một file `.txt` chứa đúng một lệnh cURL, chia thư mục theo service:

```
postman/
├── fd-gateway-service/      GET_gateway_health.txt
├── fd-auth-service/         POST_auth_register.txt, POST_auth_login.txt, GET_auth_me.txt, POST_auth_refresh.txt,
│                            POST_auth_logout.txt, GET_auth_.well-known_jwks.json.txt, GET_auth_test_ping.txt,
│                            GET_internal_users_{userId}.txt
├── fd-order-service/        GET_order_health.txt, GET_order_test_auth-http.txt, GET_order_test_auth-grpc.txt
├── fd-restaurant-service/   GET_restaurant_health.txt
├── fd-delivery-service/     GET_delivery_health.txt
└── fd-chat-service/         GET_chat_health.txt
```

**Tên file:** method viết hoa + path của API, `/` đổi thành `_` vì tên file không được chứa `/`, vd.
`POST /auth/login` → `POST_auth_login.txt`, `GET /internal/users/{userId}` → `GET_internal_users_{userId}.txt`.

**Dùng với Postman:** mở file, chọn hết (Ctrl+A), copy, dán vào thanh URL của một request mới. Postman tự chuyển
thành request đủ method, header, body.

**Dữ liệu giả cần thay** trước khi gửi:

| Giá trị giả | Thay bằng |
|---|---|
| `PASTE_ACCESS_TOKEN` | Cookie `access_token` nhận được khi login |
| `PASTE_REFRESH_TOKEN` | Cookie `refresh_token` nhận được khi login |
| `17939522301001` | ID thật của một user |
| `quan.nguyen`, `Abc@12345`, `quan.nguyen@example.com`, `0912345678` | Tài khoản muốn đăng ký / đăng nhập |

Đã login bằng Postman thì Postman tự lưu và gửi cookie: có thể xoá header `Cookie` trong request thay vì dán token.

**URL:** API công khai đi qua gateway `http://localhost:8090`, như frontend. API nội bộ (`/internal/**`) và service
chưa có route ở gateway (restaurant, delivery, chat) gọi thẳng cổng của service.
