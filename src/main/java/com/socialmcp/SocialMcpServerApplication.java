package com.socialmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SocialMcpServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SocialMcpServerApplication.class, args);
	}

}
