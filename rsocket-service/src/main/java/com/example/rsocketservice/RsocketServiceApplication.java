package com.example.rsocketservice;

import io.rsocket.ConnectionSetupPayload;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.rsocket.annotation.ConnectMapping;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.rsocket.EnableRSocketSecurity;
import org.springframework.security.config.annotation.rsocket.RSocketSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.rsocket.core.PayloadSocketAcceptorInterceptor;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@SpringBootApplication
public class RsocketServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(RsocketServiceApplication.class, args);
	}
}

@Log4j2
@Controller
class GreetingService {

	private GreetingResponse greet(String name) {
		return new GreetingResponse("Hello " + name + " @ " + Instant.now());
	}


	@MessageMapping("greeting")
	Mono<GreetingResponse> greeting(@Headers Map<String, Object> headers) {
		headers.forEach((k, v) -> log.info(k + '=' + v));
		return ReactiveSecurityContextHolder.getContext().map(sc -> sc.getAuthentication().getName()).map(this::greet);
	}

	@MessageMapping("error-signal")
	Mono<String> handleAndReturnError() {
		return Mono.error(new IllegalArgumentException("Invalid input error"));
	}


	@MessageExceptionHandler(IllegalArgumentException.class)
	Mono<String> onIllegalArgumentException(
		IllegalArgumentException iae) {
		log.error(iae);
		return Mono.just("OoOps!");
	}
}

@Configuration
@EnableRSocketSecurity
class RSocketSecurityConfiguration {

	@Bean
	PayloadSocketAcceptorInterceptor rsocketInterceptor(RSocketSecurity rsocket) {
		return rsocket
			.authorizePayload(authorize ->
				authorize
					.route("greeting").authenticated()
					.anyExchange().permitAll()
			)
			.simpleAuthentication(Customizer.withDefaults())
			.build();
	}

	@Bean
	MapReactiveUserDetailsService userDetailsService() {
		UserDetails user = User.withDefaultPasswordEncoder()
			.username("user")
			.password("password")
			.roles("USER")
			.build();
		return new MapReactiveUserDetailsService(user);
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
