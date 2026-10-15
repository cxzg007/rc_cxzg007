package com.rightcapital.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record NotificationRequest(@JsonProperty("request_id") String requestId, String url, String method,
                                  Map<String, String> headers, String body) {}
