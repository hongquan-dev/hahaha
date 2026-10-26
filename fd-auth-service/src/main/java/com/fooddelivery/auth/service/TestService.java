package com.fooddelivery.auth.service;

import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.dto.UserSearchRequest;
import com.fooddelivery.auth.dto.UserSummaryDto;
import com.fooddelivery.auth.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

// Nghiệp vụ của các API test trong TestController.
// Dữ liệu đầu vào đã được TestValidator kiểm tra trước, nên ở đây không kiểm tra lại
@Service
public class TestService {
    private static final Logger log = LogManager.getLogger(TestService.class);

    private final UserRepository userRepository;

    public TestService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // Tìm user theo bộ lọc, phân trang (page bắt đầu từ 0), sắp xếp theo id tăng dần
    @Transactional(readOnly = true)
    public Page<UserSummaryDto> searchUsers(Long adminId, UserSearchRequest filter, int page, int size) {
        Page<User> users = userRepository.findAll(buildFilter(filter), PageRequest.of(page, size, Sort.by("id")));
        log.info("Admin {} searched users {} page={} size={}: {} result(s)",
                adminId, filter, page, size, users.getTotalElements());
        return users.map(UserSummaryDto::from);
    }

    // Chỉ thêm điều kiện cho field có giá trị; không có điều kiện nào thì lấy tất cả
    private static Specification<User> buildFilter(UserSearchRequest filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.role() != null && !filter.role().isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("role")), filter.role().trim().toUpperCase()));
            }
            if (filter.active() != null) {
                predicates.add(cb.equal(root.get("active"), filter.active()));
            }
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                String pattern = "%" + filter.keyword().trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("username")), pattern),
                        cb.like(cb.lower(root.get("email")), pattern)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
