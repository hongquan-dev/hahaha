package com.fooddelivery.grpc.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests of the check in {@link GrpcClientProperties}: a deadline that is not positive is rejected with the
 * property name. Binding and the {@code 2s} default are checked in {@code GrpcSupportAutoConfigurationTest}.
 */
class GrpcClientPropertiesTest {

    @Test
    void positiveDeadlineIsAccepted() {
        assertThat(new GrpcClientProperties(Duration.ofMillis(1)).defaultDeadline()).isEqualTo(Duration.ofMillis(1));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void deadlineThatIsNotPositiveIsRejected(Duration deadline) {
        assertThatIllegalArgumentException().isThrownBy(() -> new GrpcClientProperties(deadline))
                .withMessageContaining("fooddelivery.grpc.client.default-deadline");
    }
}
