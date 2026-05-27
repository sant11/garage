package com.example.garageops.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Locks the access-control foundation (F-01) gating contract against the real
 * {@link SecurityConfig}. These three facts are load-bearing for every downstream slice:
 * unauthenticated access is redirected to login, {@code /actuator/health} stays public for
 * the deploy healthcheck, and a valid owner login authenticates. The test runs under the
 * existing test-profile DataSource/Flyway exclusions, so no database is required.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityGatingTests {

	@Autowired
	private MockMvc mockMvc;

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
		// Plaintext matches the fallback BCrypt hash documented in dev-credentials.md.
		mockMvc.perform(formLogin().user("owner").password("owner-local-dev"))
			.andExpect(authenticated());
	}
}
