package com.example.garageops.security;

import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * The owner login screen. Anonymous-accessible by design — it is the entry point the
 * {@link VaadinSecurityConfigurer}-managed chain redirects unauthenticated visitors to.
 *
 * <p>The {@link LoginForm} POSTs to Spring Security's {@code /login} endpoint
 * ({@code setAction("login")}); form-login processes the credentials, and on failure
 * redirects back to {@code /login?error}, surfaced here as the form's error state. No custom
 * POST handling. {@code autoLayout = false} keeps the view out of the app-shell router layout.
 */
@Route(value = "login", autoLayout = false)
@PageTitle("Login")
@AnonymousAllowed
public class LoginView extends Main implements BeforeEnterObserver {

	private final LoginForm login;

	public LoginView() {
		login = new LoginForm();
		login.setAction("login");

		VerticalLayout layout = new VerticalLayout();
		layout.setAlignItems(FlexComponent.Alignment.CENTER);
		layout.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
		layout.setSizeFull();
		layout.add(login);

		add(layout);
		setSizeFull();
	}

	@Override
	public void beforeEnter(BeforeEnterEvent event) {
		if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
			login.setError(true);
		}
	}
}
