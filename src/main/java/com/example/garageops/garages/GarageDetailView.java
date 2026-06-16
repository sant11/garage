package com.example.garageops.garages;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractService;
import com.example.garageops.payments.Payment;
import com.example.garageops.payments.PaymentService;
import com.example.garageops.tenants.Tenant;
import com.example.garageops.tenants.TenantService;
import com.example.garageops.ui.MainLayout;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;
import jakarta.persistence.EntityNotFoundException;

/**
 * The garage drill-through target (S-04, also serves S-06 FR-018), reached as {@code garages/<id>}
 * (a typed {@code HasUrlParameter<Long>}) from the portfolio. Shows the garage's label, location, and default
 * rent; its full rental history (FR-011) with a derived Active/Ended/Upcoming status; a
 * <b>New contract</b> action (FR-009) that pre-fills the rent from the garage default and rejects an
 * overlapping window with a clear message; and an <b>End early</b> action (FR-010) on the currently
 * active contract.
 *
 * <p>All business logic lives in {@link ContractService}; this view only gathers input and re-fetches
 * after every mutation (no manual page refresh). The create dialog binds a {@link ComboBox} of active
 * tenants and surfaces the service's overlap/validation {@link IllegalStateException} inline without
 * closing the dialog. Tenant-name cells link back to {@code tenants/:id} so the drill-through runs
 * both directions.
 *
 * <p>An unknown or archived garage id throws {@link NotFoundException} so the route surfaces a 404
 * rather than a blank or partial view. {@code @PermitAll} mirrors the sibling views; the parent
 * {@code MainLayout} is already annotated, so the route is owner-gated.
 */
@Route(value = "garages", layout = MainLayout.class)
@PageTitle("Garage")
@PermitAll
public class GarageDetailView extends VerticalLayout implements HasUrlParameter<Long> {

	private final GarageService garageService;
	private final ContractService contractService;
	private final TenantService tenantService;
	private final PaymentService paymentService;

	private Garage garage;

	public GarageDetailView(GarageService garageService, ContractService contractService,
			TenantService tenantService, PaymentService paymentService) {
		this.garageService = garageService;
		this.contractService = contractService;
		this.tenantService = tenantService;
		this.paymentService = paymentService;

		setSizeFull();
		setPadding(true);
	}

	@Override
	public void setParameter(BeforeEvent event, Long id) {
		// A single typed route parameter (Vaadin's documented choice for a single id): the Router parses
		// the Long and 404s a non-numeric segment before this runs, so no manual parsing is needed.
		try {
			this.garage = garageService.findActive(id);
		} catch (EntityNotFoundException e) {
			// Unknown or archived id: surface a 404 rather than rendering a blank/partial view.
			throw new NotFoundException("Unknown garage: " + id);
		}
		refresh();
	}

	/** Re-fetch the garage and rebuild the whole view — the single post-mutation refresh hook. */
	private void refresh() {
		this.garage = garageService.findActive(garage.getId());
		removeAll();

		H2 label = new H2(garage.getLabel());
		Button newContract = new Button("New contract", e -> openCreateDialog());
		newContract.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

		HorizontalLayout header = new HorizontalLayout(label, newContract);
		header.setWidthFull();
		header.setAlignItems(FlexComponent.Alignment.CENTER);
		header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

		add(header);
		add(new Paragraph(garage.getLocation().getName()
			+ " · default rent " + garage.getMonthlyRent().toPlainString()));
		add(historySection());
	}

	private VerticalLayout historySection() {
		VerticalLayout section = new VerticalLayout();
		section.setPadding(false);
		section.setSpacing(true);
		section.add(new H3("Rental history"));

		List<Contract> contracts = contractService.historyForGarage(garage.getId());
		if (contracts.isEmpty()) {
			section.add(new Paragraph("No contracts yet."));
			return section;
		}

		LocalDate today = LocalDate.now();
		Grid<Contract> grid = new Grid<>(Contract.class, false);
		grid.addComponentColumn(this::tenantLink).setHeader("Tenant").setAutoWidth(true);
		grid.addColumn(c -> c.getStartDate().toString()).setHeader("Start").setAutoWidth(true);
		grid.addColumn(c -> c.getPlannedEndDate().toString()).setHeader("Planned end").setAutoWidth(true);
		grid.addColumn(c -> c.getEndedOn() == null ? "—" : c.getEndedOn().toString())
			.setHeader("Ended on").setAutoWidth(true);
		grid.addColumn(c -> c.getMonthlyRent().toPlainString()).setHeader("Rent").setAutoWidth(true);
		grid.addComponentColumn(c -> statusBadge(c, today)).setHeader("Status").setAutoWidth(true);
		grid.addComponentColumn(c -> contractActions(c, today)).setHeader("Actions").setAutoWidth(true);
		grid.setItems(contracts);
		grid.setAllRowsVisible(true);
		section.add(grid);
		return section;
	}

	/** Tenant-name cell, linking back to the tenant profile so the drill-through runs both ways. */
	private Button tenantLink(Contract contract) {
		Tenant tenant = contract.getTenant();
		Button link = new Button(tenant.getName(),
			e -> UI.getCurrent().navigate("tenants/" + tenant.getId()));
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

	private HorizontalLayout contractActions(Contract contract, LocalDate today) {
		HorizontalLayout layout = new HorizontalLayout();
		layout.setPadding(false);
		// Payments apply to any non-archived contract — including an ended one, which can still take a
		// late settling payment; the service rejects an archived contract.
		layout.add(new Button("Record payment", e -> openRecordPaymentDialog(contract)));
		layout.add(new Button("Payments", e -> openPaymentsDialog(contract)));
		// "End early" only applies to a currently-active contract: an upcoming one hasn't started and
		// an ended/expired one can't take an actual-end date inside its window.
		if (contract.isActiveOn(today)) {
			layout.add(new Button("End early", e -> openEndEarlyDialog(contract)));
		}
		return layout;
	}

	/** Create a contract for this garage (FR-009): pick a tenant, set the window, rent pre-filled. */
	private void openCreateDialog() {
		Dialog dialog = new Dialog("New contract");

		ComboBox<Tenant> tenant = new ComboBox<>("Tenant");
		tenant.setItems(tenantService.listActive());
		tenant.setItemLabelGenerator(Tenant::getName);
		tenant.setWidthFull();

		DatePicker start = new DatePicker("Start date");
		start.setWidthFull();
		DatePicker plannedEnd = new DatePicker("Planned end date");
		plannedEnd.setWidthFull();

		BigDecimalField rent = new BigDecimalField("Monthly rent");
		rent.setValue(garage.getMonthlyRent());
		rent.setWidthFull();

		IntegerField paymentDay = new IntegerField("Payment day of month");
		paymentDay.setMin(1);
		paymentDay.setMax(28);
		paymentDay.setStepButtonsVisible(true);
		paymentDay.setValue(1);
		paymentDay.setWidthFull();

		Paragraph error = new Paragraph();
		error.getStyle().set("color", "var(--lumo-error-text-color)");
		error.setVisible(false);

		Button save = new Button("Create", e -> {
			error.setVisible(false);
			if (tenant.getValue() == null) {
				tenant.setInvalid(true);
				tenant.setErrorMessage("A tenant is required");
				return;
			}
			if (start.getValue() == null || plannedEnd.getValue() == null) {
				return;
			}
			BigDecimal rentValue = rent.getValue();
			Integer dayValue = paymentDay.getValue();
			if (rentValue == null || dayValue == null) {
				return;
			}
			try {
				contractService.create(tenant.getValue().getId(), garage.getId(),
					start.getValue(), plannedEnd.getValue(), rentValue, dayValue);
			} catch (IllegalStateException ex) {
				// Overlap or validation rejection: surface the message and keep the dialog open.
				error.setText(ex.getMessage());
				error.setVisible(true);
				return;
			}
			dialog.close();
			refresh();
		});
		save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		Button cancel = new Button("Cancel", e -> dialog.close());

		dialog.add(new VerticalLayout(tenant, start, plannedEnd, rent, paymentDay, error));
		dialog.getFooter().add(cancel, save);
		dialog.open();
	}

	/** Record a rent payment against the in-context contract (FR-012), reusing the new-contract dialog
	 * precedent: manual validation, the service's {@link IllegalStateException} shown inline without
	 * closing the dialog, success → {@code close()} + {@code refresh()}. */
	private void openRecordPaymentDialog(Contract contract) {
		Dialog dialog = new Dialog("Record payment");

		BigDecimalField amount = new BigDecimalField("Amount");
		amount.setWidthFull();

		DatePicker date = new DatePicker("Payment date");
		date.setValue(LocalDate.now());
		date.setWidthFull();

		TextField note = new TextField("Note (optional)");
		note.setWidthFull();

		Paragraph error = new Paragraph();
		error.getStyle().set("color", "var(--lumo-error-text-color)");
		error.setVisible(false);

		Button save = new Button("Record", e -> {
			error.setVisible(false);
			BigDecimal amountValue = amount.getValue();
			if (amountValue == null || amountValue.signum() <= 0) {
				amount.setInvalid(true);
				amount.setErrorMessage("Amount must be greater than zero");
				return;
			}
			if (date.getValue() == null) {
				date.setInvalid(true);
				date.setErrorMessage("A payment date is required");
				return;
			}
			try {
				paymentService.record(contract.getId(), amountValue, date.getValue(), note.getValue());
			} catch (IllegalStateException ex) {
				// Validation rejection (e.g. archived contract): surface it and keep the dialog open.
				error.setText(ex.getMessage());
				error.setVisible(true);
				return;
			}
			dialog.close();
			refresh();
		});
		save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		Button cancel = new Button("Cancel", e -> dialog.close());

		dialog.add(new VerticalLayout(amount, date, note, error));
		dialog.getFooter().add(cancel, save);
		dialog.open();
	}

	/** The in-context contract's recorded payments (FR-014), newest first; friendly empty-state when
	 * none. Read-only, so it opens in a dialog rather than mutating the page. */
	private void openPaymentsDialog(Contract contract) {
		Dialog dialog = new Dialog("Payments");

		List<Payment> payments = paymentService.historyForContract(contract.getId());
		if (payments.isEmpty()) {
			dialog.add(new Paragraph("No payments recorded yet."));
		} else {
			Grid<Payment> grid = new Grid<>(Payment.class, false);
			grid.addColumn(p -> p.getDate().toString()).setHeader("Date").setAutoWidth(true);
			grid.addColumn(p -> p.getAmount().toPlainString()).setHeader("Amount").setAutoWidth(true);
			grid.addColumn(p -> p.getNote() == null ? "—" : p.getNote()).setHeader("Note").setAutoWidth(true);
			grid.setItems(payments);
			grid.setAllRowsVisible(true);
			dialog.add(grid);
		}

		Button close = new Button("Close", e -> dialog.close());
		dialog.getFooter().add(close);
		dialog.open();
	}

	/** End a contract early on its actual move-out date (FR-010); the service enforces the window. */
	private void openEndEarlyDialog(Contract contract) {
		Dialog dialog = new Dialog("End contract early");

		DatePicker actualEnd = new DatePicker("Actual move-out date");
		actualEnd.setValue(LocalDate.now());
		actualEnd.setWidthFull();

		Paragraph error = new Paragraph();
		error.getStyle().set("color", "var(--lumo-error-text-color)");
		error.setVisible(false);

		Button save = new Button("End contract", e -> {
			error.setVisible(false);
			if (actualEnd.getValue() == null) {
				return;
			}
			try {
				contractService.endEarly(contract.getId(), actualEnd.getValue());
			} catch (IllegalArgumentException | IllegalStateException ex) {
				error.setText(ex.getMessage());
				error.setVisible(true);
				return;
			}
			dialog.close();
			refresh();
		});
		save.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
		Button cancel = new Button("Cancel", e -> dialog.close());

		dialog.add(new VerticalLayout(actualEnd, error));
		dialog.getFooter().add(cancel, save);
		dialog.open();
	}
}
