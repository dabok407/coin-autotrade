package com.example.upbit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UpbitAutotradeApplication {
    public static void main(String[] args) {
        SpringApplication.run(UpbitAutotradeApplication.class, args);
    }
}
