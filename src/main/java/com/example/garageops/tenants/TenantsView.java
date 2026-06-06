package com.example.garageops.tenants;

import java.util.List;

import com.example.garageops.ui.MainLayout;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;

/**
 * The S-03 tenants screen: a single flat grid of active tenants (name + contact) with an
 * <b>Add tenant</b> action and per-row <b>View / Edit / Archive</b>. A tenant has no parent, so —
 * unlike {@code LocationsView}'s per-location sections — this is one grid, not a section per row.
 *
 * <p>All business logic lives in {@link TenantService}; this view only gathers input and re-fetches
 * after every mutation (no manual page refresh). The add/edit dialog binds a throwaway {@link Tenant}
 * bean so keystrokes never mutate the live entity in the grid; archive is gated by a
 * {@link ConfirmDialog} whose copy states records are retained, not deleted (FR-021). <b>View</b>
 * navigates to the parameterized profile route {@code tenants/:id}.
 *
 * <p>{@code @PermitAll} mirrors the sibling views; the parent {@code MainLayout} is already annotated,
 * so the whole navigation chain is owner-gated.
 */
@Route(value = "tenants", layout = MainLayout.class)
@PageTitle("Tenants")
@PermitAll
public class TenantsView extends VerticalLayout {

	private final TenantService tenantService;

	private final VerticalLayout content = new VerticalLayout();

	public TenantsView(TenantService tenantService) {
		this.tenantService = tenantService;

		setSizeFull();
		setPadding(true);

		H2 title = new H2("Tenants");
		Button addTenant = new Button("Add tenant", e -> openTenantDialog(null));
		addTenant.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

		HorizontalLayout header = new HorizontalLayout(title, addTenant);
		header.setWidthFull();
		header.setAlignItems(FlexComponent.Alignment.CENTER);
		header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

		content.setPadding(false);
		content.setWidthFull();

		add(header, content);
		refresh();
	}

	/** Re-fetch active tenants and rebuild the content — the single post-mutation refresh hook. */
	private void refresh() {
		content.removeAll();
		List<Tenant> tenants = tenantService.listActive();
		if (tenants.isEmpty()) {
			content.add(new Paragraph("No tenants yet. Add your first tenant to get started."));
			return;
		}

		Grid<Tenant> grid = new Grid<>(Tenant.class, false);
		grid.addColumn(Tenant::getName).setHeader("Name").setAutoWidth(true);
		grid.addColumn(Tenant::getContactInfo).setHeader("Contact").setAutoWidth(true);
		grid.addComponentColumn(this::tenantActions).setHeader("Actions").setAutoWidth(true);
		grid.setItems(tenants);
		grid.setAllRowsVisible(true);
		content.add(grid);
	}

	private HorizontalLayout tenantActions(Tenant tenant) {
		Button view = new Button("View",
			e -> UI.getCurrent().navigate("tenants/" + tenant.getId()));
		Button edit = new Button("Edit", e -> openTenantDialog(tenant));
		Button archive = new Button("Archive", e -> confirmArchiveTenant(tenant));
		archive.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

		HorizontalLayout layout = new HorizontalLayout(view, edit, archive);
		layout.setPadding(false);
		return layout;
	}

	/** Add (when {@code existing} is {@code null}) or edit a tenant's name + contact, via {@link Binder}. */
	private void openTenantDialog(Tenant existing) {
		boolean adding = existing == null;
		Dialog dialog = new Dialog(adding ? "Add tenant" : "Edit tenant");

		TextField name = new TextField("Name");
		name.setWidthFull();
		TextField contact = new TextField("Contact info");
		contact.setWidthFull();

		Binder<Tenant> binder = new Binder<>(Tenant.class);
		binder.forField(name)
			.asRequired("Name is required")
			.withValidator(v -> !v.trim().isEmpty(), "Name is required")
			.bind(Tenant::getName, (t, v) -> t.editProfile(v, t.getContactInfo()));
		binder.forField(contact)
			.bind(Tenant::getContactInfo, (t, v) -> t.editProfile(t.getName(), v));

		// Bind a throwaway bean (a fresh copy when editing) so keystrokes never mutate the live
		// entity that also sits in the rendered grid; the persisted values are read from this bean.
		Tenant bean = adding
			? new Tenant("", null)
			: new Tenant(existing.getName(), existing.getContactInfo());
		binder.setBean(bean);

		Button save = new Button("Save", e -> {
			if (!binder.validate().isOk()) {
				return;
			}
			String contactValue = bean.getContactInfo() == null || bean.getContactInfo().trim().isEmpty()
				? null
				: bean.getContactInfo().trim();
			if (adding) {
				tenantService.add(bean.getName().trim(), contactValue);
			} else {
				tenantService.editProfile(existing.getId(), bean.getName().trim(), contactValue);
			}
			dialog.close();
			refresh();
		});
		save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		Button cancel = new Button("Cancel", e -> dialog.close());

		dialog.add(name, contact);
		dialog.getFooter().add(cancel, save);
		dialog.open();
	}

	/** Archive a tenant behind a confirm that states records are retained, not deleted (FR-021). */
	private void confirmArchiveTenant(Tenant tenant) {
		ConfirmDialog dialog = new ConfirmDialog();
		dialog.setHeader("Archive tenant");
		dialog.setText("Archive \"" + tenant.getName()
			+ "\"? Records are retained, not deleted.");
		dialog.setCancelable(true);
		dialog.setConfirmText("Archive");
		dialog.setConfirmButtonTheme("error primary");
		dialog.addConfirmListener(e -> {
			tenantService.archive(tenant.getId());
			refresh();
		});
		dialog.open();
	}
}
