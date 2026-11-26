package com.fooddelivery.restaurant.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiResponse;
import com.fooddelivery.restaurant.dto.CreateRestaurantRequest;
import com.fooddelivery.restaurant.dto.RestaurantDto;
import com.fooddelivery.restaurant.service.RestaurantService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link RestaurantController} with a mocked {@link RestaurantService}: each endpoint takes the owner
 * from the user header (and the body from the {@link ApiRequest}), calls the service and answers with the right
 * status. The rules of
 * the validator and the service are tested elsewhere.
 */
class RestaurantControllerTest {

    private static final RestaurantDto RESTAURANT = new RestaurantDto(17939522302001L, 17939522301001L, "Phở Hà Nội",
            "12 Lý Thái Tổ", "+84912345678", null, "PENDING", LocalDateTime.parse("2026-11-26T10:00:00"));

    private final RestaurantService restaurantService = mock(RestaurantService.class);
    private final RestaurantController controller = new RestaurantController(restaurantService);

    @Test
    void createPassesTheCallerAndTheBodyAndReturnsCreated() {
        CreateRestaurantRequest body = new CreateRestaurantRequest(" Phở Hà Nội ", "12 Lý Thái Tổ", "0912345678",
                null);
        when(restaurantService.create(17939522301001L, body)).thenReturn(RESTAURANT);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/restaurant/restaurants");
        servletRequest.addHeader("X-User-Id", "17939522301001");

        ResponseEntity<ApiResponse<RestaurantDto>> response = controller.create(new ApiRequest(servletRequest,
                Map.of(), "{\"name\":\" Phở Hà Nội \",\"address\":\"12 Lý Thái Tổ\","
                + "\"phoneNumber\":\"0912345678\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().data()).isEqualTo(RESTAURANT);
    }

    @Test
    void findMineLooksUpTheCallerAndReturnsOk() {
        when(restaurantService.findMine(17939522301001L)).thenReturn(RESTAURANT);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/restaurant/restaurants/me");
        servletRequest.addHeader("X-User-Id", "17939522301001");

        ResponseEntity<ApiResponse<RestaurantDto>> response =
                controller.findMine(new ApiRequest(servletRequest, Map.of(), ""));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEqualTo(RESTAURANT);
    }
}
