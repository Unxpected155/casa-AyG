package com.casaayg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CasaAyGApplication {

    public static void main(String[] args) {
        SpringApplication.run(CasaAyGApplication.class, args);
    }
}
