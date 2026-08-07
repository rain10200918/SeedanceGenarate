package org.example.seedancegenarate;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication
@MapperScan("org.example.seedancegenarate.mapper")
public class SeedanceGenarateApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeedanceGenarateApplication.class, args);
    }

}
