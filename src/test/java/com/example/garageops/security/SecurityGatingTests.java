package com.example.garageops.security;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Locks the access-control gating contract against the real {@link SecurityConfig}, built on
 * {@code VaadinSecurityConfigurer}. These three facts are load-bearing for every downstream slice:
 * unauthenticated access is redirected to the Vaadin login view, {@code /actuator/health} stays
 * public for the deploy healthcheck, and a valid owner login authenticates.
 *
 * <p>Authentication is now DB-backed, but this test stays DB-free (JPA autoconfig is excluded in
 * the test profile). The repository-backed {@code OwnerDetailsService} can't reach a database, so
 * the {@link UserDetailsService} is mocked to return a known owner whose stored BCrypt hash matches
 * a known plaintext — exercising the real filter chain and {@code PasswordEncoder} without a DB. No
 * repository bean is required: the {@code OwnerBootstrap} runner sees an empty {@code ObjectProvider}
 * and no-ops.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityGatingTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserDetailsService userDetailsService;

	@BeforeEach
	void stubOwner() {
		// Plaintext "owner-local-dev" matches this fallback BCrypt hash (dev-credentials.md).
		var owner = User.withUsername("owner")
			.password("$2a$10$QMGr6Q3SaPmUEkg6/ukov.oRuLjMXe502Lj5WHIgrWAi/dGcBh26a")
			.roles("OWNER")
			.build();
		given(userDetailsService.loadUserByUsername("owner")).willReturn(owner);
	}

	@Test
	void unauthenticatedRequestToGatedPathRedirectsToLogin() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void actuatorHealthIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk());
	}

	@Test
	void validOwnerLoginAuthenticates() throws Exception {
		mockMvc.perform(formLogin().user("owner").password("owner-local-dev"))
			.andExpect(authenticated());
	}
}
