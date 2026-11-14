package com.azentio.doppel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProjectDoppelApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProjectDoppelApplication.class, args);
	}

}
