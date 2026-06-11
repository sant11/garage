package com.example.garageops.locations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.garageops.contracts.ContractService;
import com.example.garageops.garages.Garage;
import com.example.garageops.garages.GarageService;
import com.example.garageops.ui.MainLayout;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
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
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;

/**
 * The S-02 portfolio screen: one section per active location, each carrying location-level actions
 * (rename / add garage / archive) and a grid of its active garages with a free/problem status badge
 * and per-garage actions (edit / mark-or-clear problem / archive).
 *
 * <p>All business logic lives in {@link LocationService} / {@link GarageService}; this view only
 * gathers input and re-fetches after every mutation (no manual page refresh, per US-01). Money uses
 * {@link BigDecimalField}; location archive is gated by a {@link ConfirmDialog} that names how many
 * garages will also be archived (retained, never deleted — FR-021); the problem reason is captured
 * in a plain {@link Dialog} with a {@link TextArea} (a {@code ConfirmDialog} isn't built for input).
 *
 * <p>Each garage shows a problem / rented / free status, where "rented" is derived from a current
 * active S-04 contract (FR-005): the set of rented garage ids is computed once per refresh in a
 * single batch query across every displayed garage, never per row. The garage label links through to
 * its {@code garages/:id} detail view. {@code @PermitAll} mirrors {@code HomeView}; the parent
 * {@code MainLayout} is already annotated, so the whole navigation chain is owner-gated.
 */
@Route(value = "locations", layout = MainLayout.class)
@PageTitle("Locations")
@PermitAll
public class LocationsView extends VerticalLayout {

	private final LocationService locationService;
	private final GarageService garageService;
	private final ContractService contractService;

	private final VerticalLayout sections = new VerticalLayout();

	// Garage ids with a currently-active contract, recomputed once per refresh and read per row by the
	// status badge — never a query-per-row (mirrors the single-batch discipline of listActiveByLocations).
	private Set<Long> rentedGarageIds = Set.of();

	public LocationsView(LocationService locationService, GarageService garageService,
			ContractService contractService) {
		this.locationService = locationService;
		this.garageService = garageService;
		this.contractService = contractService;

		setSizeFull();
		setPadding(true);

		H2 title = new H2("Locations");
		Button addLocation = new Button("Add location", e -> openLocationDialog(null));
		addLocation.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

		HorizontalLayout header = new HorizontalLayout(title, addLocation);
		header.setWidthFull();
		header.setAlignItems(FlexComponent.Alignment.CENTER);
		header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

		sections.setPadding(false);
		sections.setWidthFull();

		add(header, sections);
		refresh();
	}

	/** Re-fetch active locations and rebuild every section — the single post-mutation refresh hook. */
	private void refresh() {
		sections.removeAll();
		List<Location> locations = locationService.listActive();
		if (locations.isEmpty()) {
			sections.add(new Paragraph("No locations yet. Add your first location to get started."));
			return;
		}
		// Batch-load all active garages once, grouped by location id, rather than a query per section.
		Map<Long, List<Garage>> garagesByLocation = garageService.listActiveByLocations(
			locations.stream().map(Location::getId).toList());
		// Derive "rented" for every displayed garage in a single batch query, read per row by the badge.
		List<Long> allGarageIds = garagesByLocation.values().stream()
			.flatMap(List::stream).map(Garage::getId).toList();
		rentedGarageIds = contractService.rentedGarageIds(allGarageIds, LocalDate.now());
		for (Location location : locations) {
			sections.add(locationSection(location,
				garagesByLocation.getOrDefault(location.getId(), List.of())));
		}
	}

	private Component locationSection(Location location, List<Garage> garages) {
		H3 name = new H3(location.getName());

		Button rename = new Button("Rename", e -> openLocationDialog(location));
		Button addGarage = new Button("Add garage", e -> openGarageDialog(location, null));
		Button archive = new Button("Archive", e -> confirmArchiveLocation(location, garages.size()));
		archive.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

		HorizontalLayout actions = new HorizontalLayout(rename, addGarage, archive);
		actions.setPadding(false);

		HorizontalLayout head = new HorizontalLayout(name, actions);
		head.setWidthFull();
		head.setAlignItems(FlexComponent.Alignment.CENTER);
		head.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

		Grid<Garage> grid = new Grid<>(Garage.class, false);
		grid.addComponentColumn(this::garageLink).setHeader("Garage").setAutoWidth(true);
		grid.addColumn(g -> g.getMonthlyRent().toPlainString()).setHeader("Monthly rent").setAutoWidth(true);
		grid.addComponentColumn(this::statusBadge).setHeader("Status").setAutoWidth(true);
		grid.addComponentColumn(this::garageActions).setHeader("Actions").setAutoWidth(true);
		grid.setItems(garages);
		grid.setAllRowsVisible(true);

		VerticalLayout section = new VerticalLayout(head, grid);
		section.setWidthFull();
		section.setPadding(true);
		section.setSpacing(true);
		section.getStyle()
			.set("border", "1px solid var(--lumo-contrast-20pct)")
			.set("border-radius", "var(--lumo-border-radius-l)");
		return section;
	}

	/** Garage label cell, linking through to the {@code garages/:id} detail / rental-history view. */
	private Button garageLink(Garage garage) {
		Button link = new Button(garage.getLabel(),
			e -> UI.getCurrent().navigate("garages/" + garage.getId()));
		link.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
		return link;
	}

	/** Status badge: a problem flag wins, then a current active contract ("rented"), else "free". */
	private Span statusBadge(Garage garage) {
		if (garage.isProblem()) {
			Span badge = new Span("problem");
			badge.getElement().getThemeList().add("badge error");
			if (garage.getProblemReason() != null) {
				badge.getElement().setProperty("title", garage.getProblemReason());
			}
			return badge;
		}
		boolean rented = rentedGarageIds.contains(garage.getId());
		Span badge = new Span(rented ? "rented" : "free");
		badge.getElement().getThemeList().add(rented ? "badge contrast" : "badge success");
		return badge;
	}

	private Component garageActions(Garage garage) {
		Button edit = new Button("Edit", e -> openGarageDialog(null, garage));
		Button problem = garage.isProblem()
			? new Button("Clear problem", e -> {
				garageService.clearProblem(garage.getId());
				refresh();
			})
			: new Button("Mark problem", e -> openProblemDialog(garage));
		Button archive = new Button("Archive", e -> {
			garageService.archive(garage.getId());
			refresh();
		});
		archive.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

		HorizontalLayout layout = new HorizontalLayout(edit, problem, archive);
		layout.setPadding(false);
		return layout;
	}

	/** Add (when {@code existing} is {@code null}) or rename a location, validated via {@link Binder}. */
	private void openLocationDialog(Location existing) {
		boolean adding = existing == null;
		Dialog dialog = new Dialog(adding ? "Add location" : "Rename location");

		TextField name = new TextField("Name");
		name.setWidthFull();

		Binder<Location> binder = new Binder<>(Location.class);
		binder.forField(name)
			.asRequired("Name is required")
			.withValidator(v -> !v.trim().isEmpty(), "Name is required")
			.bind(Location::getName, Location::rename);

		// Bind a throwaway bean (a fresh copy when renaming) so keystrokes never mutate the live
		// entity that also sits in the rendered list; the persisted value is read from this bean.
		Location bean = adding ? new Location("") : new Location(existing.getName());
		binder.setBean(bean);

		Button save = new Button("Save", e -> {
			if (!binder.validate().isOk()) {
				return;
			}
			if (adding) {
				locationService.add(bean.getName().trim());
			} else {
				locationService.rename(existing.getId(), bean.getName().trim());
			}
			dialog.close();
			refresh();
		});
		save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		Button cancel = new Button("Cancel", e -> dialog.close());

		dialog.add(name);
		dialog.getFooter().add(cancel, save);
		dialog.open();
	}

	/**
	 * Add a garage under {@code parent} (when {@code existing} is {@code null}) or edit an existing
	 * one. The combined {@code edit(label, rent)} setter is split per field so each {@link Binder}
	 * binding preserves the other value; money uses {@link BigDecimalField}.
	 */
	private void openGarageDialog(Location parent, Garage existing) {
		boolean adding = existing == null;
		Dialog dialog = new Dialog(adding ? "Add garage" : "Edit garage");

		TextField label = new TextField("Label");
		label.setWidthFull();
		BigDecimalField rent = new BigDecimalField("Default monthly rent");
		rent.setWidthFull();

		Binder<Garage> binder = new Binder<>(Garage.class);
		binder.forField(label)
			.asRequired("Label is required")
			.withValidator(v -> !v.trim().isEmpty(), "Label is required")
			.bind(Garage::getLabel, (g, v) -> g.edit(v, g.getMonthlyRent()));
		binder.forField(rent)
			.asRequired("Rent is required")
			.withValidator(v -> v != null && v.signum() > 0, "Rent must be a positive amount")
			.bind(Garage::getMonthlyRent, (g, v) -> g.edit(g.getLabel(), v));

		// Bind a throwaway bean (a fresh copy when editing) so keystrokes never mutate the live
		// entity that also sits in the rendered list; the persisted values are read from this bean.
		Garage bean = adding
			? new Garage(parent, "", null)
			: new Garage(existing.getLocation(), existing.getLabel(), existing.getMonthlyRent());
		binder.setBean(bean);

		Button save = new Button("Save", e -> {
			if (!binder.validate().isOk()) {
				return;
			}
			if (adding) {
				garageService.add(parent.getId(), bean.getLabel().trim(), bean.getMonthlyRent());
			} else {
				garageService.edit(existing.getId(), bean.getLabel().trim(), bean.getMonthlyRent());
			}
			dialog.close();
			refresh();
		});
		save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		Button cancel = new Button("Cancel", e -> dialog.close());

		dialog.add(label, rent);
		dialog.getFooter().add(cancel, save);
		dialog.open();
	}

	/** Capture a free-text problem reason in a plain dialog (a {@code ConfirmDialog} isn't for input). */
	private void openProblemDialog(Garage garage) {
		Dialog dialog = new Dialog("Mark problem");

		TextArea reason = new TextArea("What's the problem?");
		reason.setWidthFull();

		Button save = new Button("Mark problem", e -> {
			String value = reason.getValue();
			if (value == null || value.trim().isEmpty()) {
				reason.setInvalid(true);
				reason.setErrorMessage("A reason is required");
				return;
			}
			garageService.markProblem(garage.getId(), value.trim());
			dialog.close();
			refresh();
		});
		save.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
		Button cancel = new Button("Cancel", e -> dialog.close());

		dialog.add(reason);
		dialog.getFooter().add(cancel, save);
		dialog.open();
	}

	/** Archive a location behind a confirm that names how many active garages will also be archived. */
	private void confirmArchiveLocation(Location location, int garageCount) {
		ConfirmDialog dialog = new ConfirmDialog();
		dialog.setHeader("Archive location");
		String detail = garageCount == 0
			? "Archive \"" + location.getName() + "\"? It has no active garages."
			: "Archive \"" + location.getName() + "\"? This will also archive its " + garageCount
				+ " active garage" + (garageCount == 1 ? "" : "s") + ". Records are retained, not deleted.";
		dialog.setText(detail);
		dialog.setCancelable(true);
		dialog.setConfirmText("Archive");
		dialog.setConfirmButtonTheme("error primary");
		dialog.addConfirmListener(e -> {
			locationService.archive(location.getId());
			refresh();
		});
		dialog.open();
	}
}
