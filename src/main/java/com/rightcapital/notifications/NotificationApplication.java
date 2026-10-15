package com.rightcapital.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NotificationApplication {
    public static void main(String[] args) {
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        SpringApplication.run(NotificationApplication.class, args);
    }
}
