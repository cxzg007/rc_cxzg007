package com.rightcapital.notifications;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.math.BigInteger;
import java.util.Locale;
import java.util.function.DoubleSupplier;

public final class RetryPolicy {
    public record Decision(boolean success, Instant retryAt, String reason) {}

    private final int maxAttempts;
    private final Duration maxAge;
    private final Duration base;
    private final Duration cap;
    private final DoubleSupplier random;

    public RetryPolicy(int maxAttempts, Duration maxAge, Duration base, Duration cap, DoubleSupplier random) {
        this.maxAttempts = maxAttempts;
        this.maxAge = maxAge;
        this.base = base;
        this.cap = cap;
        this.random = random;
    }

    public Decision decide(int attempt, Instant acceptedAt, Instant now, DeliveryResult result) {
        if (result.statusCode() != null && result.statusCode() >= 200 && result.statusCode() < 300)
            return new Decision(true, null, null);
        if (!result.retryable()) return new Decision(false, null, result.reason());
        if (attempt >= maxAttempts) return new Decision(false, null, "attempt_budget_exhausted");
        long delay = base.toMillis();
        for (int i = 1; i < attempt && delay < cap.toMillis(); i++) delay = Math.min(cap.toMillis(), delay * 2);
        delay = Math.min(delay, cap.toMillis());
        var retryAt = now.plusMillis((long) (delay * (0.5 + 0.5 * random.getAsDouble())));
        if (result.statusCode() != null && (result.statusCode() == 429 || result.statusCode() == 503)) {
            var requested = retryAfter(result.retryAfter(), now);
            if (requested != null && requested.isAfter(retryAt)) retryAt = requested;
        }
        if (!retryAt.isBefore(acceptedAt.plus(maxAge))) return new Decision(false, null, "age_budget_exhausted");
        return new Decision(false, retryAt, result.reason());
    }

    private Instant retryAfter(String value, Instant now) {
        if (value == null) return null;
        var text = value.trim();
        // A syntactically valid huge delay must not fall back to an earlier retry.
        if (text.matches("[0-9]+")) {
            var seconds = new BigInteger(text);
            if (seconds.compareTo(BigInteger.valueOf(maxAge.toSeconds())) > 0) return Instant.MAX;
            return now.plusSeconds(seconds.longValue());
        }
        try { return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
        catch (RuntimeException ignored) { /* RFC 9110 also requires the two obsolete HTTP-date forms. */ }
        try {
            if (text.matches("(?i)(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday), .*")) {
                int year = now.atZone(ZoneOffset.UTC).getYear();
                var formatter = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-")
                    .appendValueReduced(ChronoField.YEAR, 2, 2, year - 49).appendPattern(" HH:mm:ss 'GMT'")
                    .toFormatter(Locale.US).withResolverStyle(ResolverStyle.STRICT);
                var parsed = LocalDateTime.parse(text.substring(text.indexOf(',') + 1).trim(), formatter).atZone(ZoneOffset.UTC);
                if (parsed.isAfter(now.atZone(ZoneOffset.UTC).plusYears(50))) parsed = parsed.minusYears(100);
                return parsed.toInstant();
            }
            var asctime = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT);
            return LocalDateTime.parse(text.replaceAll(" +", " "), asctime).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) { return null; }
    }
}
