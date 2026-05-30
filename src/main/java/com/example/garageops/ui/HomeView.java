package com.example.garageops.ui;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;

/**
 * Placeholder landing view at {@code /} — the post-login redirect target. {@code @PermitAll}
 * because the single owner role is flat; any authenticated user may see it. A later slice
 * (S-06) replaces this with the real dashboard; this view intentionally does not pre-empt
 * that design.
 */
@Route(value = "", layout = MainLayout.class)
@PageTitle("GarageOps")
@PermitAll
public class HomeView extends VerticalLayout {

	public HomeView() {
		add(new H2("You're signed in"));
		add(new Paragraph("The GarageOps dashboard arrives in a later slice."));
	}
}
