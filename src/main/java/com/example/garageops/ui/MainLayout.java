package com.example.garageops.ui;

import com.example.garageops.locations.LocationsView;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.spring.security.AuthenticationContext;

import jakarta.annotation.security.PermitAll;

/**
 * The canonical app-shell. Every authenticated view plugs into this parent layout, which
 * carries the app header and the logout control. S-02+ add navigation here.
 *
 * <p>{@code @PermitAll} because Vaadin's view-access check covers the whole navigation chain:
 * a permitted child view (e.g. {@code HomeView}) is still denied if its parent layout carries
 * no access annotation. The flat single-owner role means any authenticated user may see the shell.
 *
 * <p>Logout is delegated to the shared {@link AuthenticationContext} bean (constructor-injected)
 * — {@link AuthenticationContext#logout()} invalidates the session and redirects to the
 * configured success URL, where the security chain bounces the now-anonymous user to the login.
 *
 * <p>The drawer holds the app navigation (S-02 onward); a {@link DrawerToggle} in the navbar opens
 * it, which also keeps nav reachable on a phone-sized viewport.
 */
@PermitAll
public class MainLayout extends AppLayout {

	public MainLayout(AuthenticationContext authenticationContext) {
		H1 appName = new H1("GarageOps");
		// Default H1 font-size/margins overflow the fixed-height navbar and hide siblings;
		// the Vaadin AppLayout examples scale the title down to fit the bar.
		appName.getStyle().set("font-size", "1.125rem").set("margin", "0");

		Button logout = new Button("Log out", event -> authenticationContext.logout());

		HorizontalLayout header = new HorizontalLayout(appName, logout);
		header.setWidthFull();
		header.setAlignItems(FlexComponent.Alignment.CENTER);
		header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
		header.setPadding(true);

		addToNavbar(new DrawerToggle(), header);

		SideNav nav = new SideNav();
		nav.addItem(new SideNavItem("Locations", LocationsView.class));
		addToDrawer(nav);
	}
}
