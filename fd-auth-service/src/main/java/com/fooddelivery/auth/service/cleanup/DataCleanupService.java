package com.fooddelivery.auth.service.cleanup;

import com.fooddelivery.auth.config.properties.CleanupProperties;
import com.fooddelivery.auth.config.properties.LoginThrottleProperties;
import com.fooddelivery.auth.repository.LoginFailureRepository;
import com.fooddelivery.auth.repository.RefreshTokenRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * Cleanup job of the tables that grow with every login: {@code refresh_tokens} gains a row per refresh and
 * {@code login_failures} a row per identifier typed wrongly, and neither row is ever needed once its session or
 * lockout is over.
 *
 * <p>Run by the scheduler on {@code auth.cleanup.cron} (hourly by default), and callable directly. Deletes:
 * <ul>
 *   <li>whole login sessions (refresh token families) that ended more than {@code auth.cleanup.retention} ago:
 *       logged out, revoked, idle-expired or past their absolute expiry. Live sessions are never touched, including
 *       their replaced tokens, which reveal a stolen token when reused</li>
 *   <li>{@code login_failures} rows whose lock and counting window have both ended</li>
 * </ul>
 * Each statement deletes at most {@code auth.cleanup.batch-size} and runs in its own transaction, so the tables are
 * never locked long. Several auth-service instances may run it at once: a row already deleted is simply not found.
 */
@Service
public class DataCleanupService {
    private static final Logger log = LogManager.getLogger(DataCleanupService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginFailureRepository loginFailureRepository;
    private final CleanupProperties properties;
    private final LoginThrottleProperties loginThrottleProperties;
    private final Clock clock;

    public DataCleanupService(RefreshTokenRepository refreshTokenRepository,
                              LoginFailureRepository loginFailureRepository, CleanupProperties properties,
                              LoginThrottleProperties loginThrottleProperties, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.loginFailureRepository = loginFailureRepository;
        this.properties = properties;
        this.loginThrottleProperties = loginThrottleProperties;
        this.clock = clock;
    }

    // Scheduled entry point. A failure is logged by the scheduler and the next run tries again
    @Scheduled(cron = "${auth.cleanup.cron:0 0 * * * *}")
    public void scheduledCleanUp() {
        CleanupResult result = cleanUp();
        if (result.refreshTokens() > 0 || result.loginFailures() > 0) {
            log.info("[CLEANUP] Deleted {} refresh tokens of ended sessions and {} login failure rows",
                    result.refreshTokens(), result.loginFailures());
        }
    }

    /**
     * Deletes everything that is due now, batch after batch until nothing is left.
     *
     * @return number of rows deleted from each table
     */
    public CleanupResult cleanUp() {
        Instant now = clock.instant();
        Instant sessionCutoff = now.minus(properties.retention());
        Instant windowCutoff = now.minus(loginThrottleProperties.failureWindow());
        int batchSize = properties.batchSize();

        int refreshTokens = deleteInBatches(
                () -> refreshTokenRepository.deleteFamiliesEndedBefore(sessionCutoff, batchSize));
        int loginFailures = deleteInBatches(
                () -> loginFailureRepository.deleteExpired(now, windowCutoff, batchSize));
        return new CleanupResult(refreshTokens, loginFailures);
    }

    // Repeats a batch delete until it deletes nothing, and returns the total
    private static int deleteInBatches(IntSupplier batch) {
        int total = 0;
        int deleted;
        do {
            deleted = batch.getAsInt();
            total += deleted;
        } while (deleted > 0);
        return total;
    }

    /**
     * Rows deleted by one {@link #cleanUp()}.
     *
     * @param refreshTokens rows of {@code refresh_tokens}, all tokens of the deleted sessions
     * @param loginFailures rows of {@code login_failures}
     */
    public record CleanupResult(int refreshTokens, int loginFailures) {
    }
}
