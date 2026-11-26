# Checklist: Role chủ quán, check role theo API và API tạo nhà hàng

Phạm vi: `food-delivery-libs` (`common-dto-lib`, `common-api-flow-lib`), `fd-auth-service`, `fd-restaurant-service`,
`fd-gateway-service`. Mỗi bước có mục **Kiểm tra**; chỉ tick khi kiểm tra đạt. Các mã `[Dx]` tham chiếu tới bảng quyết
định ở bước 0.

Luồng sau khi xong:

```
Client ──> gateway :8090  JwtAuth: token hợp lệ? (401) -> gắn X-User-Id, X-User-Role
       ──> restaurant :8082
             ApiFlowInterceptor: route có khai báo? (404) -> role thuộc <roles>? (401/403) -> schema (400) -> validator (400)
             RestaurantController -> RestaurantService: user đã có quán chưa? (409) -> lưu
```

---

## 0. Chốt quyết định

- [x] **D1** Bốn role cố định, lưu dạng text: `CUSTOMER`, `RESTAURANT_OWNER`, `DRIVER`, `ADMIN`. Mỗi tài khoản một role
- [x] **D2** Enum `UserRole` khai báo ở thư viện (`common-dto-lib`, package `com.fooddelivery.dto.auth`), các service kéo
      về. Cột `users.role`: migration mới đổi role null / lạ thành `CUSTOMER`, rồi `VARCHAR(32) NOT NULL` + `CHECK`.
      Entity dùng `@Enumerated(STRING)`. Token và header vẫn là chuỗi, không đổi hợp đồng với gateway
- [x] **D3** Tài khoản chủ quán: đăng ký tài khoản thường qua `POST /auth/register`, rồi đổi role bằng SQL (ghi trong
      `postman/README.md`). Không viết thành migration
- [x] **D4** Thư viện: route có thẻ `<roles>` tuỳ chọn, nhiều role cách nhau bằng dấu phẩy, vd.
      `<roles>RESTAURANT_OWNER,ADMIN</roles>`. Vì `UserRole` đã ở thư viện nên tên role được kiểm tra lúc startup: sai
      tên (vd. `RESTAURANT_OWNR`) thì service không khởi động
- [x] **D5** Route không có `<roles>` thì không check role (API công khai như login, register vẫn chạy như cũ)
- [x] **D6** Route có `<roles>`: thiếu `X-User-Id` -> 401 `UNAUTHENTICATED`; role thiếu, sai tên hoặc không thuộc danh
      sách -> 403 `ACCESS_DENIED`. Chạy ngay sau khi tìm được route, **trước** schema và validator
- [x] **D7** Tên header đọc từ `api-flow.user-id-header` / `api-flow.user-role-header`, mặc định `X-User-Id` /
      `X-User-Role`. `ApiRequest` có thêm `getUserId()` và `getUserRole()`; `RequestValue` có thêm `asLong()` cho ID 14 số
- [x] **D8** API tạo quán: `POST /restaurant/restaurants`, chỉ role `RESTAURANT_OWNER`. Body: `name`, `address`,
      `phoneNumber` (bắt buộc), `description` (tuỳ chọn). Chủ quán lấy từ `X-User-Id`, **không** nhận từ body
- [x] **D9** Mỗi chủ quán chỉ có một quán (unique `owner_user_id`), tạo lần hai -> 409 `RESTAURANT_ALREADY_EXISTS`
- [x] **D10** Quán mới tạo có `status=PENDING`; cột có `CHECK` gồm `PENDING`, `ACTIVE`, `SUSPENDED`
- [x] **D11** Restaurant-service có database riêng `FD_restaurant_db`, tạo tay một lần (ghi trong README)
- [x] **D12** Làm tiếp trên các thay đổi chưa commit đang có trong `food-delivery-libs` (request ID)

---

## Phần A: Thư viện (`food-delivery-libs`)

### A1. `UserRole` và thẻ `<roles>`

- [x] `UserRole` trong `common-dto-lib`, `fromName` trả `null` nếu sai tên; `UserRoleTest`
- [x] `ApiConfigWrapper.RouteXmlDto`: field `roles`
- [x] `ApiRegistry.parseRoles`: tách dấu phẩy, trim, bỏ trùng; thẻ rỗng, phần tử rỗng, tên sai -> `ApiFlowConfigException`
- [x] `RouteDefinition.getRoles()` (`Set<UserRole>`, rỗng nếu không giới hạn)

**Kiểm tra:** `ApiRegistryTest` có route có roles, không có roles, roles rỗng, phần tử rỗng, sai tên.
- [x] Đạt

### A2. Check role mỗi request

- [x] `ApiFlowErrorCode`: `UNAUTHENTICATED` (401), `ACCESS_DENIED` (403)
- [x] `ApiFlowProperties`: `user-id-header`, `user-role-header`
- [x] `ApiFlowInterceptor`: check role sau khi tìm được route, trước schema
- [x] `ApiRequest.getUserId()`, `getUserRole()`; `ApiRequestArgumentResolver` dùng đúng tên header đã cấu hình
- [x] `RequestValue.asLong()`

**Kiểm tra:** `ApiFlowInterceptorTest` (đúng role, sai role, role lạ, thiếu role, thiếu user, role trước body, đổi tên
header), `ApiFlowEndToEndTest` (route `GET /items/mine` có `<roles>`, đổi tên header qua property).
- [x] Đạt. Lúc chạy test bắt được một bug: `Set.copyOf(...).contains(null)` ném NPE khi header role sai tên, đã sửa
      sang `EnumSet`

### A3. Tài liệu và cài đặt

- [x] README `common-api-flow-lib`, `common-dto-lib`; `ARCHITECTURE.md` của libs (class, luồng, hợp đồng)
- [x] `mvn install` ở `food-delivery-libs`: dto 64, web 88, api-flow 233 test pass, `ArchitectureDocTest` pass

---

## Phần B: Role trong `fd-auth-service`

- [x] Migration `V4__constrain_user_role.sql`
- [x] `User.role` kiểu `UserRole`, `@Enumerated(STRING)`, `nullable = false`
- [x] `AuthService.DEFAULT_ROLE = UserRole.CUSTOMER`; `TokenService` ghi claim bằng `role.name()`; `UserDto.role` vẫn là chuỗi
- [x] Cập nhật test (`TokenServiceTest`, `SessionServiceTest`, `AuthServiceLoginTest`, `AuthServiceRegisterTest`)

**Kiểm tra:** auth-service khởi động, Flyway chạy V4, `validate` pass; register ra `CUSTOMER`; tài khoản đổi role bằng
SQL đăng nhập lại thì `GET /auth/me` trả `"role": "RESTAURANT_OWNER"`.
- [x] Đạt (314 test pass)

---

## Phần C: `fd-restaurant-service`

- [x] `pom.xml` dùng `common-lib`; `application.properties` (datasource, Flyway, `validate`, API flow)
- [x] `api-routes.xml`: health check + route tạo quán có `<roles>RESTAURANT_OWNER</roles>`
- [x] `V1__create_restaurants.sql`, `Restaurant`, `RestaurantStatus`, `RestaurantRepository`
- [x] `schemas/create-restaurant.json`, `CreateRestaurantRequest` (`normalized()`), `RestaurantDto`
- [x] `RestaurantValidator.validateCreate`, `RestaurantController.create`, `RestaurantService.create` (check trước +
      `saveAndFlush` bắt unique constraint khi hai request cùng lúc)
- [x] Test: `CreateRestaurantRequestTest`, `RestaurantValidatorTest`, `RestaurantServiceTest`, `RestaurantControllerTest`

**Kiểm tra:** 48 test pass; service khởi động, Flyway chạy V1, `validate` pass, health check vẫn 200.
- [x] Đạt

---

## Phần D: Gateway

- [x] `services.restaurant.http-url`, route `/restaurant/**` có `JwtAuth`, `/restaurant/health` trong `public-paths`

**Kiểm tra:** `POST /restaurant/restaurants` qua `:8090` không có cookie -> 401 từ gateway.
- [x] Đạt

---

## Phần E: Tài liệu, cURL, kiểm tra cuối

- [x] `postman/fd-restaurant-service/POST_restaurant_restaurants.txt`; `postman/README.md` (giá trị giả, SQL đổi role)
- [x] `ARCHITECTURE.md` (restaurant-service, phân quyền, database, hợp đồng role), `CLAUDE.md` (bước role trong luồng
      API), README (database, cách chạy)
- [x] `./mvnw clean install` ở backend pass: common-lib 19, fd-grpc-api 110, gateway 70, auth 314, restaurant 48

**Kiểm tra end-to-end qua gateway (auth, restaurant, gateway đang chạy):**
- [x] Không có cookie -> 401 `UNAUTHENTICATED` (gateway)
- [x] Tài khoản `CUSTOMER` gọi tạo quán -> 403 `ACCESS_DENIED`
- [x] `CUSTOMER` gửi kèm `X-User-Role: RESTAURANT_OWNER` -> vẫn 403 (gateway đã xoá header giả)
- [x] `RESTAURANT_OWNER`, `name` rỗng -> 400 `INVALID_RESTAURANT_INFO` kèm lỗi field `name`
- [x] Body có `ownerUserId` -> 400 `INVALID_REQUEST_BODY`
- [x] `RESTAURANT_OWNER`, body đúng -> 201, `status=PENDING`, `ownerUserId` là ID của chính tài khoản đó, SĐT `+84...`
- [x] Gọi lần hai -> 409 `RESTAURANT_ALREADY_EXISTS`
- [x] `GET /restaurant/health` qua gateway không cần token -> 200
- [x] Gọi thẳng `:8082` không có header user -> 401 `UNAUTHENTICATED` (từ API flow)

---

## Việc tiếp theo: admin duyệt quán (chưa làm)

Database đã sẵn sàng từ `V2__add_restaurant_review.sql`: `status` có `REJECTED`, cột `review_note`, `submitted_at`,
`reviewed_at`, `reviewed_by` trong `restaurants`, bảng lịch sử `restaurant_reviews`. Khi làm phần duyệt:

- [ ] Tài khoản admin đầu tiên (đề xuất: tạo lúc auth-service khởi động từ biến môi trường)
- [ ] `EntityType` mới cho ID của `restaurant_reviews` (`common-id-lib`, mã chưa dùng), entity `RestaurantReview`
- [ ] Chủ quán: `PUT /restaurant/restaurants/me` sửa và gửi lại (`REJECTED` -> `PENDING`, cập nhật `submitted_at`,
      ghi `SUBMITTED` vào lịch sử); sửa thông tin cần xác minh khi `ACTIVE` thì cũng quay về `PENDING`
- [ ] Admin: danh sách theo trạng thái, `/approve`, `/reject` (bắt buộc lý do), `/suspend` (bắt buộc lý do),
      `/reactivate`; chỉ chuyển theo đúng sơ đồ, sai thì 409 `INVALID_STATUS_TRANSITION`; mỗi lần ghi `reviewed_at`,
      `reviewed_by` và một dòng `restaurant_reviews`
- [ ] `RestaurantDto` trả `reviewNote`; frontend thêm `REJECTED` vào `RestaurantStatus` và `RestaurantStatusBadge`
      (hiện thiếu, nhận `REJECTED` sẽ lỗi), hiện lý do và form sửa ở `/merchant`
- [ ] Làm hồ sơ tài xế ở delivery-service theo cùng mô hình
