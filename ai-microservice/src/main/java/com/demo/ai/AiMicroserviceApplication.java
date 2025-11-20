package com.demo.ai;

import com.demo.ai.service.MultiTenantAiService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AiMicroserviceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiMicroserviceApplication.class, args);
    }

    @Bean
    CommandLineRunner initModels(MultiTenantAiService aiService) {
        return args -> aiService.loadAllTenants();
    }
}
