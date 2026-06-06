package com.example.garageops.tenants;

import com.example.garageops.persistence.ArchivableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

/**
 * A tenant (S-03) — a renting party identified by a required name plus optional free-text contact
 * info. Archivable per FR-021: archiving stamps {@code archived_at} and retains the row (a tenant's
 * underlying contracts and payments are never deleted).
 *
 * <p>Single-tenant by design: no {@code owner_id} FK (PRD Non-Goal, AGENTS hard rule). Column mapping
 * must match {@code V5__tenants.sql} exactly ({@code ddl-auto=validate}).
 *
 * <p><b>No contract-facing structure yet.</b> S-04 (rental-contracts) introduces {@code Contract}
 * carrying a {@code @ManyToOne Tenant} FK on the child side; this entity holds no {@code @OneToMany}
 * collection (the no-parent-collection rule), no {@code latePayer} flag (S-07), and no placeholder
 * for either. Those seams are filled entirely from the S-04 / S-07 side.
 */
@Entity
@Table(name = "tenants")
public class Tenant extends ArchivableEntity {

	@NotBlank
	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "contact_info")
	private String contactInfo;

	protected Tenant() {
		// JPA requires a no-arg constructor.
	}

	public Tenant(String name, String contactInfo) {
		this.name = name;
		this.contactInfo = contactInfo;
	}

	/**
	 * Surrogate key, widened to {@code public} so the tenants view can pass it to
	 * {@code TenantService} (the protected {@code BaseEntity#getId} is reachable only by subclasses,
	 * and views are not).
	 */
	@Override
	public Long getId() {
		return super.getId();
	}

	public String getName() {
		return name;
	}

	public String getContactInfo() {
		return contactInfo;
	}

	/** Update this tenant's name and contact info. */
	public void editProfile(String name, String contactInfo) {
		this.name = name;
		this.contactInfo = contactInfo;
	}
}
