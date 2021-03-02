package org.devocative.keights;

import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.ClientBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@EnableScheduling
@SpringBootApplication
public class KeightsApplication {

	@Bean
	public CoreV1Api getCoreV1Api() {
		try {
			final var client = ClientBuilder.defaultClient();
			final var httpClient = client
				.getHttpClient()
				.newBuilder()
				.readTimeout(0, TimeUnit.SECONDS)
				.build();
			client.setHttpClient(httpClient);
			client.setVerifyingSsl(false);
			Configuration.setDefaultApiClient(client);

			return new CoreV1Api(client);
		} catch (IOException e) {
			throw new KeightsException(e);
		}
	}

	// ------------------------------

	public static void main(String[] args) {
		SpringApplication.run(KeightsApplication.class, args);
	}
}
