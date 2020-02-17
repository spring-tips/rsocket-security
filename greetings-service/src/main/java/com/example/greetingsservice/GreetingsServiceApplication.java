package com.example.greetingsservice;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.messaging.rsocket.annotation.support.RSocketMessageHandler;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.rsocket.EnableRSocketSecurity;
import org.springframework.security.config.annotation.rsocket.RSocketSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.messaging.handler.invocation.reactive.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.rsocket.core.PayloadSocketAcceptorInterceptor;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.stream.Stream;

@SpringBootApplication
public class GreetingsServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(GreetingsServiceApplication.class, args);
	}
}

@Configuration
@EnableRSocketSecurity
class RSocketSecurityConfiguration {

	@Bean
	RSocketMessageHandler messageHandler(RSocketStrategies strategies) {
		var mh = new RSocketMessageHandler();
		mh.getArgumentResolverConfigurer().addCustomResolver(new AuthenticationPrincipalArgumentResolver());
		mh.setRSocketStrategies(strategies);
		return mh;
	}

	@Bean
	PayloadSocketAcceptorInterceptor authorization(RSocketSecurity security) {
		return security
			.authorizePayload(spec ->
				spec
					.route("greetings").authenticated()
					.anyExchange().permitAll()
			)
			.simpleAuthentication(Customizer.withDefaults())
			.build();
	}

	@Bean
	MapReactiveUserDetailsService authentication() {
		var jlong = User.withDefaultPasswordEncoder().username("jlong").password("pw").roles("USER").build();
		var rwinch = User.withDefaultPasswordEncoder().username("rwinch").password("pw").roles("ADMIN", "USER").build();
		return new MapReactiveUserDetailsService(jlong, rwinch);
	}
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingResponse {
	private String message;
}

@Controller
class GreetingController {

	@MessageMapping("greetings")
	Flux<GreetingResponse> greet(@AuthenticationPrincipal Mono<UserDetails> user) {
		return user.map(UserDetails::getUsername).flatMapMany(GreetingController::greet);
	}

	private static Flux<GreetingResponse> greet(String name) {
		return Flux.fromStream(
			Stream
				.generate(() -> new GreetingResponse("Hello " + name + " @ " + Instant.now().toString())))
			.delayElements(Duration.ofSeconds(1));
	}


}