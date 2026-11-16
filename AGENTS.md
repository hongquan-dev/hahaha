# AGENTS.md

Hướng dẫn cho AI agent (Codex, Cursor, Copilot, Gemini...) làm việc trên repo này. Nội dung đầy đủ không chép ở đây:

1. **[CLAUDE.md](CLAUDE.md)**: quy tắc bắt buộc: luồng xử lý một API, thay đổi database qua migration, test đi kèm mọi
   thay đổi (và mức độ test), quy ước comment, cập nhật tài liệu kiến trúc. Áp dụng cho mọi agent, không riêng Claude.
2. **[ARCHITECTURE.md](ARCHITECTURE.md)**: bản đồ cấu trúc: module, port, phụ thuộc, vai trò từng class, luồng chạy,
   hợp đồng không được phá, việc thường gặp sửa ở đâu. Đọc trước khi sửa code.
3. **[README.md](README.md)** và README của từng service: cách chạy, port, Postman, grpcurl.

Thư viện dùng chung ở repo riêng `food-delivery-libs` (có `ARCHITECTURE.md` riêng), phải `mvn install` trước.

Lệnh: `./mvnw test -pl <module>`, `./mvnw clean install` (cả repo). Dùng `./mvnw`, không dùng `mvn` của máy.
