package com.rightcapital.notifications;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OriginPolicyTest {
    private final OriginPolicy policy = new OriginPolicy(new NotificationProperties(
        Map.of("business", "key"), List.of("https://supplier.example"), false, 1, 20,
        Duration.ofHours(24), Duration.ofSeconds(10), Duration.ofSeconds(30),
        Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofSeconds(5)));

    @Test void rejectsUrlThatWouldLoseCharactersDuringPersistence() {
        for (String invalid : List.of("\uD800", "\uDFFF")) {
            var error = assertThrows(ApiException.class,
                () -> policy.validate("https://supplier.example/hook/" + invalid + "/end"));
            assertEquals(400, error.status());
        }
    }

    @Test void retainsValidUnicodeAndEncodedQueryCharacters() {
        String target = "https://supplier.example/订单/\uD83D\uDE80?q=%3F";
        assertEquals(target, policy.validate(target).toString());
    }

    @Test void rejectsEmptyQueryInsteadOfSilentlyDroppingItsDelimiter() {
        var error = assertThrows(ApiException.class, () -> policy.validate("https://supplier.example/hook?"));
        assertEquals(400, error.status());
        assertEquals("empty_query_not_supported", error.getMessage());
    }
}
