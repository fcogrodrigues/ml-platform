package com.ifood.mlplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.ifood.mlplatform")
public class MiniMlPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(MiniMlPlatformApplication.class, args);
    }
}