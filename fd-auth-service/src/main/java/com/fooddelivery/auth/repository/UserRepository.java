package com.fooddelivery.auth.repository;

import com.fooddelivery.auth.common.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// JpaSpecificationExecutor: cho phép lọc động (field nào có giá trị mới thêm điều kiện), dùng ở TestService
@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);
}
