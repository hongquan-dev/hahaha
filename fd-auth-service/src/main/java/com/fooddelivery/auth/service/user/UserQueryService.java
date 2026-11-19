package com.fooddelivery.auth.service.user;

import com.fooddelivery.auth.dto.InternalUserDto;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only lookups of users for other services, called by the gRPC {@code UserService.GetUser}
 * ({@code UserGrpcService}).
 */
@Service
public class UserQueryService {

    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Finds a user by ID, whether the account is active or locked; the caller decides what a locked account means for
     * it, e.g. refusing an order.
     *
     * <p>Throws {@code BusinessException} 404 {@code USER_NOT_FOUND} if no user has this ID.
     *
     * @param userId 14-digit user ID, already checked by {@code InternalUserValidator}
     * @return the user, without password hash or email
     */
    @Transactional(readOnly = true)
    public InternalUserDto getById(long userId) {
        return userRepository.findById(userId)
                .map(InternalUserDto::from)
                .orElseThrow(() -> new BusinessException(ApiStatus.NOT_FOUND, "USER_NOT_FOUND",
                        "User " + userId + " not found"));
    }
}
