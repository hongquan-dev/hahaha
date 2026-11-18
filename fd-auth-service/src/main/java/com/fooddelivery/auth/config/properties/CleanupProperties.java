package com.fooddelivery.auth.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the cleanup job, bound at startup from the {@code auth.cleanup.*} properties of
 * {@code application.properties}, where each property is described. Read by {@code DataCleanupService}; its schedule,
 * {@code auth.cleanup.cron}, is read by {@code @Scheduled} directly.
 *
 * <p>Checked when bound: a non-positive retention or a batch size below 1 throws, so the application refuses to start.
 * Immutable.
 *
 * @param retention how long a login session is kept after it ended, e.g. to look into an incident (default 7d)
 * @param batchSize rows, or for sessions whole families, deleted per statement, so no statement locks the tables long
 *                  (default 500)
 */
@ConfigurationProperties(prefix = "auth.cleanup")
public record CleanupProperties(@DefaultValue("7d") Duration retention, @DefaultValue("500") int batchSize) {

    public CleanupProperties {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("auth.cleanup.retention must be a positive duration, e.g. 7d");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("auth.cleanup.batch-size must be at least 1, got " + batchSize);
        }
    }
}
