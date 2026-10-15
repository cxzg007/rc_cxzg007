package com.rightcapital.notifications;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import java.sql.SQLException;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiErrorHandlerTest {
    private MockMvc failingApi(RuntimeException failure) {
        var service = mock(NotificationService.class);
        when(service.submit(eq("client-a"), any(NotificationRequest.class))).thenThrow(failure);
        return MockMvcBuilders.standaloneSetup(new NotificationController(service))
            .setControllerAdvice(new ApiErrorHandler()).build();
    }

    static Stream<RuntimeException> storageFailures() {
        var cause = new SQLException("Database details: vendor-secret, private-body");
        return Stream.of(
            new DataAccessResourceFailureException("Database request failed", cause),
            new CannotCreateTransactionException("Could not open JDBC Connection for transaction", cause),
            new TransactionSystemException("JDBC commit failed", cause),
            new TransactionSystemException("JDBC rollback failed", cause));
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void storageFailureReturnsRetryableResponseWithoutDatabaseDetails(RuntimeException failure) throws Exception {
        var api = failingApi(failure);
        var response = assertDoesNotThrow(() -> api.perform(post("/notifications")
            .requestAttr("clientId", "client-a").contentType(MediaType.APPLICATION_JSON)
            .content("{\"request_id\":\"event-1\"}")));
        response.andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"error\":\"storage_unavailable\"}"))
            .andExpect(result -> {
                String body = result.getResponse().getContentAsString();
                assertFalse(body.contains("vendor-secret"));
                assertFalse(body.contains("private-body"));
            });
    }

    @Test void unrelatedProgrammingErrorIsNotReportedAsStorageFailure() {
        var failure = new IllegalStateException("Application bug");
        var api = failingApi(failure);
        var thrown = assertThrows(ServletException.class, () -> api.perform(post("/notifications")
            .requestAttr("clientId", "client-a").contentType(MediaType.APPLICATION_JSON)
            .content("{\"request_id\":\"event-1\"}")));
        assertSame(failure, thrown.getCause());
    }
}
