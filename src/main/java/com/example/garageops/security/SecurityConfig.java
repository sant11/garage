package com.example.garageops.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Access-control foundation (F-01): gates every route to the authenticated owner.
 *
 * <p>Public carve-out is deliberately tight — only {@code /actuator/health} (the deploy
 * healthcheck), the generated {@code /login} endpoints, and static assets are open; every
 * other request requires authentication. The owner is a config-driven in-memory placeholder;
 * S-01 swaps the {@link UserDetailsService} for a DB-backed store without touching the chain.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/login", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
				.anyRequest().authenticated())
			.formLogin(Customizer.withDefaults())
			// CSRF disabled by decision; S-01 must re-enable before shipping real forms.
			.csrf(csrf -> csrf.disable());
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
