package com.example.stocksinglewas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StockSingleWasApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockSingleWasApplication.class, args);
    }

}
