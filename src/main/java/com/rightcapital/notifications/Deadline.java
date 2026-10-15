package com.rightcapital.notifications;

import org.springframework.dao.QueryTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** One monotonic deadline, passed explicitly across all stages of an operation. */
public final class Deadline {
    private final long end;
    private Deadline(long end) { this.end = end; }
    public static Deadline after(Duration budget) { return new Deadline(System.nanoTime() + budget.toNanos()); }
    public Deadline reserving(Duration reserve) { return new Deadline(end - reserve.toNanos()); }
    public int remainingMillis() {
        long remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
        if (remaining < 1) throw new QueryTimeoutException("Operation budget exhausted");
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }
    int cleanupMillis() {
        // Zero means unlimited to JDBC. Exhausted budgets get only best-effort cleanup.
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())));
    }
}
