package com.ahmadre.hinata;

import com.ahmadre.hinata.config.HinataProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableMongoAuditing
@EnableConfigurationProperties(HinataProperties.class)
public class HinataServerApplication {

	static {
		// Run the whole JVM in UTC so every LocalDate/LocalDateTime→Instant
		// conversion, Spring Data Mongo date mapping and log timestamp is
		// timezone-deterministic regardless of the host's zone. Clients receive
		// UTC (ISO-8601 'Z') and localize for display. In a static initializer so
		// it takes effect before any date library or Mongo converter is touched.
		System.setProperty("user.timezone", "UTC");
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	public static void main(String[] args) {
		SpringApplication.run(HinataServerApplication.class, args);
	}
}
