package io.nostro.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.nostro")
public class NostroApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NostroApiApplication.class, args);
    }
}
