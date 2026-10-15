package com.rightcapital.notifications;

public record DeliveryResult(Integer statusCode, String retryAfter, String reason, boolean retryable) {}
