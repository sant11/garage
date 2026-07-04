package com.example.garageops.dashboard;

import java.util.List;

import com.example.garageops.payments.OverdueRow;
import com.example.garageops.payments.OverdueService;
import com.example.garageops.ui.MainLayout;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;

/**
 * The post-login landing screen and product north star (US-01, FR-015–FR-018, S-06): the three
 * urgency-ordered signals that tell the owner where attention is needed today — <b>overdue</b>
 * payments, <b>vacant</b> garages, and contracts <b>ending soon</b> — each a counted section that
 * drills through to the garage detail.
 *
 * <p>The data is <b>derived live on every navigation</b>, never stored: overdue comes from
 * {@link OverdueService#currentDues()} (reused verbatim from S-05), and vacant / ending-soon from
 * {@link DashboardService}. All three return off-session-safe row records (labels already resolved),
 * so the grids render under {@code open-in-view=false} without touching a lazy association.
 * {@link #refresh()} is called from the constructor — Vaadin builds a fresh view instance per
 * navigation, so a return visit recomputes and reflects any mutation made elsewhere, with no manual
 * browser refresh (US-01).
 *
 * <p>Each section shows its count in the header ("Overdue (3)") and a friendly {@link Paragraph}
 * when empty. {@code @PermitAll} mirrors the sibling views; the parent {@code MainLayout} is already
 * annotated, so the route is owner-gated. This view owns route {@code ""}, replacing the S-02
 * placeholder home view.
 */
@Route(value = "", layout = MainLayout.class)
@PageTitle("Dashboard")
@PermitAll
public class DashboardView extends VerticalLayout {

	private final OverdueService overdueService;
	private final DashboardService dashboardService;

	public DashboardView(OverdueService overdueService, DashboardService dashboardService) {
		this.overdueService = overdueService;
		this.dashboardService = dashboardService;

		setSizeFull();
		setPadding(true);
		refresh();
	}

	/** Rebuild all three sections from the live derivations — the single render hook. */
	private void refresh() {
		removeAll();
		add(new H2("Dashboard"));
		add(overdueSection());
		add(vacantSection());
		add(endingSoonSection());
	}

	private VerticalLayout overdueSection() {
		List<OverdueRow> rows = overdueService.currentDues();
		VerticalLayout section = new VerticalLayout(sectionHeader("Overdue", rows.size()));
		section.setPadding(false);
		section.setSpacing(false);
		if (rows.isEmpty()) {
			section.add(new Paragraph("No overdue payments."));
			return section;
		}

		Grid<OverdueRow> grid = new Grid<>(OverdueRow.class, false);
		grid.addComponentColumn(r -> garageLink(r.garageLabel(), r.garageId())).setHeader("Garage").setAutoWidth(true);
		grid.addColumn(OverdueRow::tenantName).setHeader("Tenant").setAutoWidth(true);
		grid.addColumn(r -> r.amountDue().toPlainString()).setHeader("Amount due").setAutoWidth(true);
		grid.addComponentColumn(r -> badge(r.daysOverdue() + " days", "error")).setHeader("Days overdue").setAutoWidth(true);
		grid.setItems(rows);
		grid.setAllRowsVisible(true);
		section.add(grid);
		return section;
	}

	private VerticalLayout vacantSection() {
		List<VacantGarageRow> rows = dashboardService.vacantGarages();
		VerticalLayout section = new VerticalLayout(sectionHeader("Vacant", rows.size()));
		section.setPadding(false);
		section.setSpacing(false);
		if (rows.isEmpty()) {
			section.add(new Paragraph("No vacant garages."));
			return section;
		}

		Grid<VacantGarageRow> grid = new Grid<>(VacantGarageRow.class, false);
		grid.addComponentColumn(r -> garageLink(r.garageLabel(), r.garageId())).setHeader("Garage").setAutoWidth(true);
		grid.addColumn(VacantGarageRow::locationName).setHeader("Location").setAutoWidth(true);
		grid.addComponentColumn(r -> badge(r.daysVacant() + " days", "contrast")).setHeader("Days vacant").setAutoWidth(true);
		grid.setItems(rows);
		grid.setAllRowsVisible(true);
		section.add(grid);
		return section;
	}

	private VerticalLayout endingSoonSection() {
		List<EndingSoonRow> rows = dashboardService.endingSoon();
		VerticalLayout section = new VerticalLayout(sectionHeader("Ending soon", rows.size()));
		section.setPadding(false);
		section.setSpacing(false);
		if (rows.isEmpty()) {
			section.add(new Paragraph("No contracts ending soon."));
			return section;
		}

		Grid<EndingSoonRow> grid = new Grid<>(EndingSoonRow.class, false);
		grid.addComponentColumn(r -> garageLink(r.garageLabel(), r.garageId())).setHeader("Garage").setAutoWidth(true);
		grid.addColumn(EndingSoonRow::tenantName).setHeader("Tenant").setAutoWidth(true);
		grid.addColumn(r -> r.plannedEndDate().toString()).setHeader("Planned end").setAutoWidth(true);
		grid.addComponentColumn(r -> badge(r.daysRemaining() + " days", "warning")).setHeader("Days remaining").setAutoWidth(true);
		grid.setItems(rows);
		grid.setAllRowsVisible(true);
		section.add(grid);
		return section;
	}

	/** A section title carrying its row count, e.g. "Overdue (3)". */
	private H2 sectionHeader(String title, int count) {
		H2 header = new H2(title + " (" + count + ")");
		header.getStyle().set("font-size", "1.25rem");
		return header;
	}

	private Span badge(String text, String theme) {
		Span badge = new Span(text);
		badge.getElement().getThemeList().add("badge " + theme);
		return badge;
	}

	/** Garage label cell, drilling through to the {@code garages/:id} detail (LocationsView pattern). */
	private Button garageLink(String label, Long garageId) {
		Button link = new Button(label, e -> UI.getCurrent().navigate("garages/" + garageId));
		link.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
		return link;
	}
}
