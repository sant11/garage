package com.example.garageops.security;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Access-control foundation, now fronted by Vaadin Flow (S-01).
 *
 * <p>The chain is built through {@link VaadinSecurityConfigurer}, which wires the Vaadin
 * {@code LoginView}, enables CSRF (ignoring Vaadin internal requests), configures logout and
 * request caching, and denies every request that isn't a framework request, an
 * {@code @AnonymousAllowed} view, or an explicitly permitted matcher. The only HTTP-level
 * carve-out is {@code /actuator/health} (the deploy healthcheck) — deliberately not broadened
 * to {@code /actuator/**} (privacy NFR). View access is otherwise annotation-driven.
 *
 * <p>Authentication is DB-backed: the {@code UserDetailsService} is the repository-backed
 * {@code OwnerDetailsService} in the {@code account} package (S-01 Phase 3 retired the in-memory
 * placeholder). This config keeps only the filter chain and the BCrypt {@link PasswordEncoder};
 * Spring's default {@code DaoAuthenticationProvider} wires the two together.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(auth -> auth
			.requestMatchers("/actuator/health").permitAll());
		http.with(VaadinSecurityConfigurer.vaadin(), cfg -> cfg.loginView(LoginView.class));
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
