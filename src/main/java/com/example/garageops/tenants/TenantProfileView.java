package com.example.garageops.tenants;

import java.time.LocalDate;
import java.util.List;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractService;
import com.example.garageops.payments.LatePayerFlag;
import com.example.garageops.payments.LatePayerService;
import com.example.garageops.payments.Payment;
import com.example.garageops.payments.PaymentService;
import com.example.garageops.ui.MainLayout;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
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
 * The tenant profile (FR-008) and FR-018 drill-through target, reached as {@code tenants/<id>} via a
 * typed {@code HasUrlParameter<Long>}. Renders the tenant's name with an open header slot for a
 * future late-payer badge (S-07), and a "current and past contracts" section.
 *
 * <p><b>The contract section is an honest empty-state, not a stub.</b> No {@code Contract} entity
 * exists yet (S-04). When S-04 lands it fills the seam by swapping the body of {@link
 * #contractsSection()} — the {@link Paragraph} becomes a {@code Grid<Contract>} — without touching the
 * route, the header, or this class's shape (the same placeholder-now, real-later precedent the
 * landing view followed).
 * The header keeps a layout slot open for the badge but builds none: an always-absent badge would
 * falsely signal "not a late payer".
 *
 * <p>The header's late-payer slot (S-07) is now filled: {@link LatePayerService} derives the FR-020
 * flag live on load and, when set, a "frequent late payer" badge renders beside the name. When unset,
 * the header is unchanged — no always-absent badge, which would falsely read as "not a late payer".
 *
 * <p>An unknown or archived tenant id throws {@link NotFoundException} so the route surfaces a 404
 * rather than a blank or partial profile. {@code @PermitAll} mirrors the sibling views; the parent
 * {@code MainLayout} is already annotated, so the route is owner-gated.
 */
@Route(value = "tenants", layout = MainLayout.class)
@PageTitle("Tenant")
@PermitAll
public class TenantProfileView extends VerticalLayout implements HasUrlParameter<Long> {

	private final TenantService tenantService;
	private final ContractService contractService;
	private final PaymentService paymentService;
	private final LatePayerService latePayerService;

	public TenantProfileView(TenantService tenantService, ContractService contractService,
			PaymentService paymentService, LatePayerService latePayerService) {
		this.tenantService = tenantService;
		this.contractService = contractService;
		this.paymentService = paymentService;
		this.latePayerService = latePayerService;

		setSizeFull();
		setPadding(true);
	}

	@Override
	public void setParameter(BeforeEvent event, Long id) {
		// A single typed route parameter (Vaadin's documented choice for a single id): the Router parses
		// the Long and 404s a non-numeric segment before this runs, so no manual parsing is needed. The
		// exact-route {@code tenants} list view (TenantsView) takes precedence for the bare path, so the
		// two coexist — this target only matches {@code tenants/<id>}.
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
		HorizontalLayout header = new HorizontalLayout(name);
		// S-07 badge slot: render only when the tenant is actually flagged. An always-absent badge
		// would falsely read as "not a late payer", so nothing is added below the threshold.
		LatePayerFlag flag = latePayerService.evaluate(tenant.getId());
		if (flag.flagged()) {
			Span badge = new Span("frequent late payer");
			badge.getElement().getThemeList().add("badge error");
			badge.getElement().setProperty("title",
				flag.eventCount() + " overdue events in the last " + flag.windowMonths() + " months");
			header.add(badge);
		}
		header.setWidthFull();
		header.setAlignItems(FlexComponent.Alignment.CENTER);
		header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

		add(header);
		if (tenant.getContactInfo() != null && !tenant.getContactInfo().isBlank()) {
			add(new Paragraph(tenant.getContactInfo()));
		}
		add(contractsSection(tenant));
		add(paymentsSection(tenant));
	}

	/**
	 * The "current and past contracts" section (FR-008): the tenant's full contract history, newest
	 * first, each row linking through to its garage. The header badge slot stays untouched (S-07).
	 */
	private Component contractsSection(Tenant tenant) {
		VerticalLayout section = new VerticalLayout();
		section.setPadding(false);
		section.setSpacing(true);
		section.add(new H3("Current and past contracts"));

		List<Contract> contracts = contractService.forTenant(tenant.getId());
		if (contracts.isEmpty()) {
			section.add(new Paragraph("No contracts yet."));
			return section;
		}

		LocalDate today = LocalDate.now();
		Grid<Contract> grid = new Grid<>(Contract.class, false);
		grid.addComponentColumn(this::garageLink).setHeader("Garage").setAutoWidth(true);
		grid.addColumn(c -> c.getStartDate().toString()).setHeader("Start").setAutoWidth(true);
		grid.addColumn(c -> c.getPlannedEndDate().toString()).setHeader("Planned end").setAutoWidth(true);
		grid.addColumn(c -> c.getEndedOn() == null ? "—" : c.getEndedOn().toString())
			.setHeader("Ended on").setAutoWidth(true);
		grid.addColumn(c -> c.getMonthlyRent().toPlainString()).setHeader("Rent").setAutoWidth(true);
		grid.addComponentColumn(c -> statusBadge(c, today)).setHeader("Status").setAutoWidth(true);
		grid.setItems(contracts);
		grid.setAllRowsVisible(true);
		section.add(grid);
		return section;
	}

	/**
	 * The tenant's recorded payments across all their contracts (FR-014), newest first, each row
	 * linking through to the garage it was paid against. Friendly empty-state when there are none.
	 */
	private Component paymentsSection(Tenant tenant) {
		VerticalLayout section = new VerticalLayout();
		section.setPadding(false);
		section.setSpacing(true);
		section.add(new H3("Payments"));

		List<Payment> payments = paymentService.historyForTenant(tenant.getId());
		if (payments.isEmpty()) {
			section.add(new Paragraph("No payments recorded yet."));
			return section;
		}

		Grid<Payment> grid = new Grid<>(Payment.class, false);
		grid.addComponentColumn(this::paymentGarageLink).setHeader("Garage").setAutoWidth(true);
		grid.addColumn(p -> p.getDate().toString()).setHeader("Date").setAutoWidth(true);
		grid.addColumn(p -> p.getAmount().toPlainString()).setHeader("Amount").setAutoWidth(true);
		grid.addColumn(p -> p.getNote() == null ? "—" : p.getNote()).setHeader("Note").setAutoWidth(true);
		grid.setItems(payments);
		grid.setAllRowsVisible(true);
		section.add(grid);
		return section;
	}

	/** Garage cell of a payment row — the repository {@code JOIN FETCH}es the garage, so this is safe
	 * off-session; links through to the {@code garages/:id} detail view. */
	private Button paymentGarageLink(Payment payment) {
		var garage = payment.getContract().getGarage();
		Button link = new Button(garage.getLabel(),
			e -> UI.getCurrent().navigate("garages/" + garage.getId()));
		link.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
		return link;
	}

	/** Garage cell, linking through to the {@code garages/:id} detail view (drill-through both ways). */
	private Button garageLink(Contract contract) {
		var garage = contract.getGarage();
		Button link = new Button(garage.getLabel(),
			e -> UI.getCurrent().navigate("garages/" + garage.getId()));
		link.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
		return link;
	}

	private Span statusBadge(Contract contract, LocalDate today) {
		String label;
		String theme;
		if (contract.isActiveOn(today)) {
			label = "Active";
			theme = "badge success";
		} else if (contract.getStartDate().isAfter(today)) {
			label = "Upcoming";
			theme = "badge";
		} else {
			label = "Ended";
			theme = "badge contrast";
		}
		Span badge = new Span(label);
		badge.getElement().getThemeList().add(theme);
		return badge;
	}
}
