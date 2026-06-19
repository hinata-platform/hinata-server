package com.ahmadre.hinata;

import com.ahmadre.hinata.config.HinataProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableMongoAuditing
@EnableConfigurationProperties(HinataProperties.class)
public class HinataServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(HinataServerApplication.class, args);
	}
}
