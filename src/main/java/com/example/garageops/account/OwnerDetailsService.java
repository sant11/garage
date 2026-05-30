package com.example.garageops.account;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * DB-backed {@link UserDetailsService} (S-01 Phase 3) — retires the in-memory placeholder. Loads
 * the owner via {@link OwnerAccountRepository} and maps it to a Spring {@link UserDetails} with the
 * stored BCrypt hash and the flat {@code OWNER} role. The {@code PasswordEncoder} (BCrypt) bean in
 * {@code SecurityConfig} verifies the hash at login through the default {@code DaoAuthenticationProvider}.
 *
 * <p>The repository is injected as an {@link ObjectProvider} so this bean stays constructible in the
 * DB-free test context, where JPA autoconfig (and thus the repository bean) is excluded by
 * convention. In production the repository is always present; a login attempt without it fails fast.
 */
@Service
public class OwnerDetailsService implements UserDetailsService {

	private final ObjectProvider<OwnerAccountRepository> repository;

	public OwnerDetailsService(ObjectProvider<OwnerAccountRepository> repository) {
		this.repository = repository;
	}

	@Override
	public UserDetails loadUserByUsername(String username) {
		return repository.getObject().findByUsername(username)
			.map(owner -> User.withUsername(owner.getUsername())
				.password(owner.getPasswordHash())
				.roles("OWNER")
				.build())
			.orElseThrow(() -> new UsernameNotFoundException("Unknown owner: " + username));
	}
}
