package com.example.rsocketclient;

import io.rsocket.metadata.WellKnownMimeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.rsocket.messaging.RSocketStrategiesCustomizer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.security.rsocket.metadata.BasicAuthenticationEncoder;
import org.springframework.security.rsocket.metadata.SimpleAuthenticationEncoder;
import org.springframework.security.rsocket.metadata.UsernamePasswordMetadata;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Mono;

import java.util.Objects;

import static org.springframework.security.rsocket.metadata.UsernamePasswordMetadata.BASIC_AUTHENTICATION_MIME_TYPE;

@Log4j2
@SpringBootApplication
public class RsocketClientApplication {

	@Bean
	RSocketStrategiesCustomizer rSocketStrategiesCustomizer() {
		return strategies -> strategies.encoder(new SimpleAuthenticationEncoder());
	}

	@Bean
	RSocketRequester rSocketRequester(RSocketRequester.Builder builder) {
		return builder
			.connectTcp("localhost", 8888)
			.block();
	}

	@Bean
	ApplicationListener<ApplicationReadyEvent> secureClient(RSocketRequester localhost) {
		return event -> {
			var credentials = new UsernamePasswordMetadata("user", "password");
			var mimeType = MimeTypeUtils.parseMimeType(
				WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION.getString());
			localhost
				.route("greeting")
				.metadata(credentials, mimeType)
				.data(Mono.empty())
				.retrieveMono(GreetingResponse.class)
				.subscribe(gr -> log.info("secure response: " + gr));
		};
	}

	@SneakyThrows
	public static void main(String[] args) {
		SpringApplication.run(RsocketClientApplication.class, args);
		System.in.read();
	}

}

@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingRequest {
	private String name;
}


@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingResponse {
	private String message;
}
