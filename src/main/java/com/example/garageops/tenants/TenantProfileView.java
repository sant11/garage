package com.example.garageops.tenants;

import com.example.garageops.ui.MainLayout;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;
import jakarta.persistence.EntityNotFoundException;

/**
 * The tenant profile (FR-008) and FR-018 drill-through target, reached via the project's first
 * parameterized route {@code tenants/:id}. Renders the tenant's name with an open header slot for a
 * future late-payer badge (S-07), and a "current and past contracts" section.
 *
 * <p><b>The contract section is an honest empty-state, not a stub.</b> No {@code Contract} entity
 * exists yet (S-04). When S-04 lands it fills the seam by swapping the body of {@link
 * #contractsSection()} — the {@link Paragraph} becomes a {@code Grid<Contract>} — without touching the
 * route, the header, or this class's shape (mirroring the {@code HomeView} "comes later" precedent).
 * The header keeps a layout slot open for the badge but builds none: an always-absent badge would
 * falsely signal "not a late payer".
 *
 * <p>An unknown or archived tenant id throws {@link NotFoundException} so the route surfaces a 404
 * rather than a blank or partial profile. {@code @PermitAll} mirrors the sibling views; the parent
 * {@code MainLayout} is already annotated, so the route is owner-gated.
 */
@Route(value = "tenants/:id", layout = MainLayout.class)
@PageTitle("Tenant")
@PermitAll
public class TenantProfileView extends VerticalLayout implements HasUrlParameter<Long> {

	private final TenantService tenantService;

	public TenantProfileView(TenantService tenantService) {
		this.tenantService = tenantService;

		setSizeFull();
		setPadding(true);
	}

	@Override
	public void setParameter(BeforeEvent event, Long id) {
		Tenant tenant;
		try {
			tenant = tenantService.findActive(id);
		} catch (EntityNotFoundException e) {
			// Unknown or archived id: surface a 404 rather than rendering a blank/partial profile.
			throw new NotFoundException("Unknown tenant: " + id);
		}
		render(tenant);
	}

	private void render(Tenant tenant) {
		removeAll();

		H2 name = new H2(tenant.getName());
		// Open header slot: a future late-payer badge (S-07) drops in beside the name here. No badge
		// is built now — an always-absent badge would falsely read as "not a late payer".
		HorizontalLayout header = new HorizontalLayout(name);
		header.setWidthFull();
		header.setAlignItems(FlexComponent.Alignment.CENTER);
		header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

		add(header);
		if (tenant.getContactInfo() != null && !tenant.getContactInfo().isBlank()) {
			add(new Paragraph(tenant.getContactInfo()));
		}
		add(contractsSection());
	}

	/**
	 * The "current and past contracts" section. S-04 fills this seam by swapping the body — the
	 * empty-state {@link Paragraph} becomes a {@code Grid<Contract>} — without changing the route or
	 * the rest of this view.
	 */
	private Component contractsSection() {
		VerticalLayout section = new VerticalLayout();
		section.setPadding(false);
		section.setSpacing(true);
		section.add(new H3("Current and past contracts"));
		section.add(new Paragraph("No contract history yet. Contracts arrive in a later slice."));
		return section;
	}
}
