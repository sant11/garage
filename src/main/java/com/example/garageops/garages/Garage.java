package com.example.garageops.garages;

import java.math.BigDecimal;

import com.example.garageops.locations.Location;
import com.example.garageops.persistence.ArchivableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * A garage under a {@link Location} (S-02), carrying a label, a default monthly rent, and an optional
 * free-text problem reason ({@code null} = not flagged). Archivable per FR-021 — archiving stamps and
 * retains, never deletes.
 *
 * <p>The {@code @ManyToOne} to {@link Location} is the FK-owning side and carries <b>no</b> cascade /
 * {@code orphanRemoval}: archive-with-cascade is a deliberate service-layer stamp pass so children are
 * retained by construction (R4). "Rented" status is derived from S-04 contracts and is out of scope
 * here — this slice ships free/problem only. Column mapping must match
 * {@code V3__locations_and_garages.sql} ({@code ddl-auto=validate}).
 */
@Entity
@Table(name = "garages")
public class Garage extends ArchivableEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "location_id", nullable = false)
	private Location location;

	@NotBlank
	@Column(name = "label", nullable = false)
	private String label;

	@NotNull
	@Positive
	@Column(name = "monthly_rent", nullable = false)
	private BigDecimal monthlyRent;

	@Column(name = "problem_reason")
	private String problemReason;

	protected Garage() {
		// JPA requires a no-arg constructor.
	}

	public Garage(Location location, String label, BigDecimal monthlyRent) {
		this.location = location;
		this.label = label;
		this.monthlyRent = monthlyRent;
	}

	/**
	 * Surrogate key, widened to {@code public} so the portfolio view can pass it to
	 * {@code GarageService} (the protected {@code BaseEntity#getId} is reachable only by
	 * subclasses, and views are not).
	 */
	@Override
	public Long getId() {
		return super.getId();
	}

	public Location getLocation() {
		return location;
	}

	public String getLabel() {
		return label;
	}

	public BigDecimal getMonthlyRent() {
		return monthlyRent;
	}

	public String getProblemReason() {
		return problemReason;
	}

	/** Edit the garage's label and default monthly rent. */
	public void edit(String label, BigDecimal monthlyRent) {
		this.label = label;
		this.monthlyRent = monthlyRent;
	}

	/** Flag this garage as having a problem, recording the free-text reason. */
	public void markProblem(String reason) {
		this.problemReason = reason;
	}

	/** Clear a previously recorded problem. */
	public void clearProblem() {
		this.problemReason = null;
	}

	/** @return {@code true} while a problem reason is recorded. */
	public boolean isProblem() {
		return problemReason != null;
	}
}
