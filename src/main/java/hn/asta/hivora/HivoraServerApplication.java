package hn.asta.hivora;

import hn.asta.hivora.config.HivoraProperties;
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
@EnableConfigurationProperties(HivoraProperties.class)
public class HivoraServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(HivoraServerApplication.class, args);
	}
}
