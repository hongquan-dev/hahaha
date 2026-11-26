package com.fooddelivery.restaurant.service;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.restaurant.common.model.Restaurant;
import com.fooddelivery.restaurant.common.model.RestaurantStatus;
import com.fooddelivery.restaurant.dto.CreateRestaurantRequest;
import com.fooddelivery.restaurant.dto.RestaurantDto;
import com.fooddelivery.restaurant.repository.RestaurantRepository;
import com.fooddelivery.web.exception.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hibernate.exception.ConstraintViolationException.ConstraintKind.CHECK;
import static org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link RestaurantService#create} and {@link RestaurantService#findMine}, with a mocked {@link RestaurantRepository} (the owner has no
 * restaurant unless a test says otherwise, {@code saveAndFlush} returns the entity it gets). A race between two
 * requests of the same owner is reproduced by making {@code saveAndFlush} throw what Spring Data throws when PostgreSQL
 * rejects the insert.
 */
class RestaurantServiceTest {

    private static final long OWNER_ID = 17939522301001L;
    private static final CreateRestaurantRequest REQUEST =
            new CreateRestaurantRequest(" Phở Hà Nội ", " 12 Lý Thái Tổ ", "0912.345.678", "  ");

    private final RestaurantRepository restaurantRepository = mock(RestaurantRepository.class);
    private final RestaurantService restaurantService = new RestaurantService(restaurantRepository);

    @BeforeEach
    void setUp() {
        when(restaurantRepository.saveAndFlush(any(Restaurant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void savesTheNormalizedRestaurantOfTheCallerAsPending() {
        RestaurantDto result = restaurantService.create(OWNER_ID, REQUEST);

        Restaurant saved = savedRestaurant();
        assertThat(saved.getOwnerUserId()).isEqualTo(OWNER_ID);
        assertThat(saved.getName()).isEqualTo("Phở Hà Nội");
        assertThat(saved.getAddress()).isEqualTo("12 Lý Thái Tổ");
        assertThat(saved.getPhoneNumber()).isEqualTo("+84912345678");
        assertThat(saved.getDescription()).isNull();
        assertThat(saved.getStatus()).isEqualTo(RestaurantStatus.PENDING);
        assertThat(result).isEqualTo(RestaurantDto.from(saved));
    }

    @Test
    void idIsARestaurantId() {
        restaurantService.create(OWNER_ID, REQUEST);

        assertThat(IdUtils.decodeObjectType(savedRestaurant().getId())).isEqualTo(EntityType.RESTAURANT);
    }

    @Test
    void ownerWithARestaurantGivesConflict() {
        when(restaurantRepository.existsByOwnerUserId(OWNER_ID)).thenReturn(true);

        assertConflict(createError());
        verify(restaurantRepository, never()).saveAndFlush(any());
    }

    // Constraint names may come back in any case from the database
    @ParameterizedTest
    @ValueSource(strings = {"uk_restaurants_owner_user_id", "UK_RESTAURANTS_OWNER_USER_ID"})
    void ownerConstraintViolationOnSaveGivesConflict(String constraint) {
        // Another request of the same owner saved a restaurant between the check and this save
        when(restaurantRepository.saveAndFlush(any(Restaurant.class)))
                .thenThrow(integrityViolation(UNIQUE, constraint));

        assertConflict(createError());
    }

    @Test
    void otherIntegrityViolationOnSaveIsThrownAsItIs() {
        for (DataIntegrityViolationException error : List.of(
                integrityViolation(UNIQUE, "pk_restaurants"),
                integrityViolation(UNIQUE, null),
                integrityViolation(CHECK, "ck_restaurants_status"),
                new DataIntegrityViolationException("value too long for type character varying(100)"))) {
            when(restaurantRepository.saveAndFlush(any(Restaurant.class))).thenThrow(error);

            assertThat(catchThrowable(() -> restaurantService.create(OWNER_ID, REQUEST))).isSameAs(error);
        }
    }

    @Test
    void findMineReturnsTheOwnersRestaurant() {
        Restaurant restaurant = new Restaurant(17939522302001L, OWNER_ID, "Phở Hà Nội", "12 Lý Thái Tổ",
                "+84912345678", null, RestaurantStatus.PENDING);
        when(restaurantRepository.findByOwnerUserId(OWNER_ID)).thenReturn(Optional.of(restaurant));

        assertThat(restaurantService.findMine(OWNER_ID)).isEqualTo(RestaurantDto.from(restaurant));
    }

    @Test
    void findMineWithoutRestaurantGivesNotFound() {
        when(restaurantRepository.findByOwnerUserId(OWNER_ID)).thenReturn(Optional.empty());

        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> restaurantService.findMine(OWNER_ID));

        assertThat(e.getStatus()).isEqualTo(ApiStatus.NOT_FOUND);
        assertThat(e.getCode()).isEqualTo("RESTAURANT_NOT_FOUND");
    }

    private static void assertConflict(BusinessException e) {
        assertThat(e.getStatus()).isEqualTo(ApiStatus.CONFLICT);
        assertThat(e.getCode()).isEqualTo("RESTAURANT_ALREADY_EXISTS");
    }

    // What Spring Data throws when the database rejects the insert, with Hibernate's violation as the cause
    private static DataIntegrityViolationException integrityViolation(ConstraintKind kind, String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("constraint violated", new SQLException("rejected"), kind,
                        constraint));
    }

    private Restaurant savedRestaurant() {
        ArgumentCaptor<Restaurant> captor = ArgumentCaptor.forClass(Restaurant.class);
        verify(restaurantRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private BusinessException createError() {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> restaurantService.create(OWNER_ID, REQUEST));
        assertThat(e).as("BusinessException from create").isNotNull();
        return e;
    }
}
