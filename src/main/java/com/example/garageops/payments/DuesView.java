package com.example.garageops.payments;

import java.util.List;

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
 * The Dues view (S-05, US-01 {@code prd.md:54}): every garage whose current period is overdue, with
 * the columns the owner needs to chase payment — garage, tenant, amount due, days overdue — plus a
 * drill-through to the garage detail.
 *
 * <p>Overdue is <b>derived live, never stored</b>: the view asks {@link OverdueService#currentDues()},
 * which resolves "now" through the application's fixed-zone clock and feeds the batch paid-sum into the
 * pure overdue rule. Rows arrive as off-session-safe {@link OverdueRow} projections (labels already
 * resolved), so they render under {@code open-in-view=false} without touching a lazy association.
 *
 * <p>A friendly empty-state {@link Paragraph} stands in when nothing is overdue. {@code @PermitAll}
 * mirrors the sibling views; the parent {@code MainLayout} is already annotated, so the route is
 * owner-gated.
 */
@Route(value = "dues", layout = MainLayout.class)
@PageTitle("Dues")
@PermitAll
public class DuesView extends VerticalLayout {

	private final OverdueService overdueService;

	public DuesView(OverdueService overdueService) {
		this.overdueService = overdueService;

		setSizeFull();
		setPadding(true);
		refresh();
	}

	/** Rebuild the whole view from the live overdue derivation — the single render hook. */
	private void refresh() {
		removeAll();
		add(new H2("Dues"));

		List<OverdueRow> dues = overdueService.currentDues();
		if (dues.isEmpty()) {
			add(new Paragraph("No overdue dues."));
			return;
		}

		Grid<OverdueRow> grid = new Grid<>(OverdueRow.class, false);
		grid.addColumn(OverdueRow::garageLabel).setHeader("Garage").setAutoWidth(true);
		grid.addColumn(OverdueRow::tenantName).setHeader("Tenant").setAutoWidth(true);
		grid.addColumn(r -> r.amountDue().toPlainString()).setHeader("Amount due").setAutoWidth(true);
		grid.addComponentColumn(this::daysOverdueBadge).setHeader("Days overdue").setAutoWidth(true);
		grid.addComponentColumn(this::garageLink).setHeader("").setAutoWidth(true);
		grid.setItems(dues);
		grid.setAllRowsVisible(true);
		add(grid);
	}

	private Span daysOverdueBadge(OverdueRow row) {
		Span badge = new Span(row.daysOverdue() + " days");
		badge.getElement().getThemeList().add("badge error");
		return badge;
	}

	/** Drill-through to the overdue garage's detail view ({@code garages/:id}). */
	private Button garageLink(OverdueRow row) {
		Button link = new Button("View garage",
			e -> UI.getCurrent().navigate("garages/" + row.garageId()));
		link.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
		return link;
	}
}
