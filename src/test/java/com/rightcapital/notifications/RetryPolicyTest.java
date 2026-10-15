package com.rightcapital.notifications;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    private final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    private final RetryPolicy policy = new RetryPolicy(20, Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofHours(1), () -> 0);

    @Test void successfulLastAttemptIsStillSuccessful() {
        assertTrue(policy.decide(20, now.minusSeconds(10), now, new DeliveryResult(204, null, "http_204", false)).success());
    }
    @Test void retriesTransientFailureWithBackoff() {
        assertEquals(now.plusSeconds(15), policy.decide(1, now, now, new DeliveryResult(503, null, "http_503", true)).retryAt());
        assertEquals(now.plusSeconds(30), policy.decide(2, now, now, new DeliveryResult(null, null, "io_error", true)).retryAt());
    }
    @Test void permanentFailureIsTerminal() {
        var d = policy.decide(1, now, now, new DeliveryResult(401, null, "http_401", false));
        assertFalse(d.success()); assertNull(d.retryAt()); assertEquals("http_401", d.reason());
    }
    @Test void neverExceedsAttemptBudget() {
        assertNull(policy.decide(20, now, now, new DeliveryResult(503, null, "http_503", true)).retryAt());
    }
    @Test void neverSchedulesOutsideAgeBudget() {
        assertNull(policy.decide(1, now.minusSeconds(86395), now, new DeliveryResult(503, null, "http_503", true)).retryAt());
    }
    @Test void retryAfterCanExceedLocalBackoffCap() {
        assertEquals(now.plusSeconds(7200), policy.decide(1, now, now, new DeliveryResult(429, "7200", "http_429", true)).retryAt());
    }
    @Test void parsesHttpDateAndIgnoresInvalidRetryAfter() {
        assertEquals(now.plusSeconds(120), policy.decide(1, now, now, new DeliveryResult(503, "Wed, 14 Oct 2026 00:02:00 GMT", "http_503", true)).retryAt());
        assertEquals(now.plusSeconds(15), policy.decide(1, now, now, new DeliveryResult(503, "invalid", "http_503", true)).retryAt());
        assertNull(policy.decide(1, now, now, new DeliveryResult(429, "99999999999999999999999999", "http_429", true)).retryAt());
    }
    @Test void backoffIsCappedAndJitterRemainsInRange() {
        assertEquals(now.plusSeconds(1800), policy.decide(19, now, now, new DeliveryResult(500, null, "http_500", true)).retryAt());
        var upper = new RetryPolicy(20, Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofHours(1), () -> 1);
        assertEquals(now.plusSeconds(30), upper.decide(1, now, now, new DeliveryResult(500, null, "http_500", true)).retryAt());
    }
    @Test void acceptsObsoleteHttpDatesWithoutRetryingEarly() {
        assertEquals(now.plusSeconds(7200), policy.decide(1, now, now,
            new DeliveryResult(503, "Wednesday, 14-Oct-26 02:00:00 GMT", "http_503", true)).retryAt());
        assertEquals(now.plusSeconds(7200), policy.decide(1, now, now,
            new DeliveryResult(503, "Wed Oct 14 02:00:00 2026", "http_503", true)).retryAt());
    }
    @Test void leapSecondRetryAfterWaitsUntilTheFollowingMinute() {
        var beforeLeap = Instant.parse("2016-12-31T23:58:00Z");
        for (String header : new String[]{"Sat, 31 Dec 2016 23:59:60 GMT",
                "Saturday, 31-Dec-16 23:59:60 GMT", "Sat Dec 31 23:59:60 2016"}) {
            assertEquals(Instant.parse("2017-01-01T00:00:00Z"), policy.decide(1, beforeLeap, beforeLeap,
                new DeliveryResult(503, header, "http_503", true)).retryAt(), header);
        }
    }
}
