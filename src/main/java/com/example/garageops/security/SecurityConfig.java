package com.example.garageops.security;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
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
 * <p>The owner is still a config-driven in-memory placeholder here; S-01 Phase 3 swaps the
 * {@link UserDetailsService} for a DB-backed store without touching this chain.
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

	/**
	 * One in-memory owner built from configuration. The stored password is already a BCrypt
	 * hash; the {@link PasswordEncoder} bean above verifies it at login. Defining this bean
	 * makes Boot's default random-password user back off.
	 */
	@Bean
	UserDetailsService users(
			@Value("${garageops.owner.username:owner}") String username,
			@Value("${garageops.owner.password-hash:$2a$10$QMGr6Q3SaPmUEkg6/ukov.oRuLjMXe502Lj5WHIgrWAi/dGcBh26a}") String passwordHash) {
		var owner = User.withUsername(username)
			.password(passwordHash)
			.roles("OWNER")
			.build();
		return new InMemoryUserDetailsManager(owner);
	}
}
