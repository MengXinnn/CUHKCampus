package com.cuhkcampus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class CuhkCampusApplication {
    @Bean RestClient.Builder restClientBuilder() {return RestClient.builder();}
    public static void main(String[] args) {
        SpringApplication.run(CuhkCampusApplication.class, args);
    }
}
