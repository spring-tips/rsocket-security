<!-- 
Hi, Spring fans! In this installment, we look at how to lock down RSocket services with Spring Security. 

speaker: 
Josh Long 
@starbuxman 
-->

# Rsocket and Spring Security

Hi Spring fans! In this installment, we look at using Spring Security and RSocket together. RSocket is a payload and platform agonostic wire protocol developed by engineers at Netflix and Facebook that supports the Reactive Streams concepts on the wire. The protocol is a stateful-connection centric protocol: a requester node connects and stays connected to another responder node. Once connected, either side can transmit information at any time. Connections are multiplexed, meaning one connection can handle multiple requests. RSocket is designed from the ground up to support propagating out-of-band information like headers and service health information, in addition to the payloads themselves. So, one user may use the connection with one service, or multiple users may use the same connection. 

In this video, we build on Spring Framework 5.2's core RSocket support (along with the very convenient `@MessageMapping` component model) to build an RSocket client that then connects, in a secure way, to an RSocket service. 

Let's introduce a basic RSocket service. You'll need to go to the [Spring Initializr](http://start.spring.io) and generate a new project using with RSocket and Security selected and - importantly - Spring Boot 2.3 or later.

```java
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
```

I've done two other videos on [RSocket](https://www.youtube.com/watch?v=GDIDSzZLjjg) and the [Spring support for RSocket](https://www.youtube.com/watch?v=BxHqeq58xrE) that you might consult before watching this one. The first introduces raw RSocket API, and the second introduces the component model in Spring. Please refer to those for a sense of what's happening, basically, in this controller.

Spring Security provides three mechanisms for securing RSocket-based services. BASIC authentication is sort of like HTTP BASIC - it supports usernames and passwords. It is also now deprecated. So, we'll focus on Simple authentication in this video. Simple authentication is also username- and password-based. RSocket also supports JWT-based authentication. JWT supports token-based authentication and is perhaps the more interesting mechanism for sophisticated security use-cases. (RSocket-based JWT authentication will perhaps be the subject of another video.)

As RSocket connections can be stateful and shared, we'll need to decide: do we do authentication on the connection creation, or for each message sent across the connection? If it's shared, we will want each user to provide their own authentication for every request.

Spring Security addresses two concerns: authentication and authorization. These are related, but orthagonal, concerns. Authentication answers the question: who's making the request to a system? Authorization answers the question: what are they allowed to do once they're inside the system? 

Let's introduce the Spring Security configuration for the application. 

```java

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
	MapReactiveUserDetailsService authentication() {
		var jlong = User.withDefaultPasswordEncoder().username("jlong").password("pw").roles("USER").build();
		var rwinch = User.withDefaultPasswordEncoder().username("rwinch").password("pw").roles("ADMIN", "USER").build();
		return new MapReactiveUserDetailsService(jlong, rwinch);
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
}

```


The security configuration features three beans. The first, `messageHandler`, activates parts of the Spring Security component model that let us inject the authenticated user (with the `@AuthenticatedPrincipal` annotation) into our handler methods (those annotated with `@MessageMapping`).  

The second bean, `authentication`, installs a simple username and password dictionary. You could talk to any number of different identity providers, but for ease of the demonstration, I've configured an in-memory `MapReactiveUserDetailsService`.

The third bean, `authorization`,  is - to my mind at least - the most interesting. The goal with this bean is to tell the framework which RSocket routes (in this case, `greetings`) are accessible to requests. This is hopefully self-describing: all requests to `greetings` should be authenticated. Otherwise, any other request is allowed to pass through unchecked. 

Now that we've got that up and running, let's look at the client. 

```java
package com.example.greetingsclient;

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
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.rsocket.metadata.SimpleAuthenticationEncoder;
import org.springframework.security.rsocket.metadata.UsernamePasswordMetadata;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Mono;

@Log4j2
@SpringBootApplication
public class GreetingsClientApplication {

	private final MimeType mimeType = MimeTypeUtils.parseMimeType(WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION.getString());
	private final UsernamePasswordMetadata credentials = new UsernamePasswordMetadata("jlong", "pw");

	@SneakyThrows
	public static void main(String[] args) {
		SpringApplication.run(GreetingsClientApplication.class, args);
		System.in.read();
	}

	@Bean
	RSocketStrategiesCustomizer rSocketStrategiesCustomizer() {
		return strategies -> strategies.encoder(new SimpleAuthenticationEncoder());
	}

	@Bean
	RSocketRequester rSocketRequester(RSocketRequester.Builder builder) {
		return builder
//			.setupMetadata(this.credentials , this.mimeType)
			.connectTcp("localhost", 8888)
			.block();
	}

	@Bean
	ApplicationListener<ApplicationReadyEvent> ready(RSocketRequester greetings) {
		return event ->
			greetings
				.route("greetings")
				.metadata(this.credentials, this.mimeType)
				.data(Mono.empty())
				.retrieveFlux(GreetingResponse.class)
				.subscribe(gr -> log.info("secured response: " + gr.toString()));
	}
}


@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingResponse {
	private String message;
}
```


We will send metadata to the service. We have two choices. If the connection to the RSocket connection is shared, then we'd want to send the metadata for each request. This is what we've done in this example, as it's the more likely scenario. On the other hand, if you need to only authenticate once, then you can send the metadata in the establishment of the connection in `rSocketRequester` bean. 

We use the `RSocketRequester` client in the event listener where we make a call to the `greetings` route on the service. It's basically the same as it's always been, with the slight difference that we're encoding metadata in the request for authentication. 

We've only begun to scratch the service in this blog - watch the video for more details. 
