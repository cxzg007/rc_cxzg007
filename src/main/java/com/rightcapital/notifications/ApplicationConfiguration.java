package com.rightcapital.notifications;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(NotificationProperties.class)
class ApplicationConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean RetryPolicy retryPolicy(NotificationProperties p) {
        return new RetryPolicy(p.maxAttempts(), p.maxAge(), p.backoffBase(), p.backoffCap(), () -> ThreadLocalRandom.current().nextDouble());
    }
    @Bean HttpClient deliveryHttpClient(NotificationProperties p) {
        return HttpClient.newBuilder().connectTimeout(p.requestTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
