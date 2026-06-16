package com.example.garageops.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.example.garageops.garages.Garage;
import com.example.garageops.persistence.ArchivableEntity;
import com.example.garageops.tenants.Tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * The rental agreement linking one {@link Tenant} to one {@link Garage} (S-04). Carries the FR-009
 * terms (start date, required planned end, monthly rent, payment day-of-month) plus the FR-010
 * {@code endedOn} actual move-out date. Archivable per FR-021: ending a contract sets {@code endedOn}
 * and leaves a normal queryable row; {@code archived_at} is orthogonal and set only by the
 * parent-archive cascade.
 *
 * <p><b>Active/ended/upcoming is derived, never stored.</b> {@link #isActiveOn(LocalDate)} is the one
 * predicate — {@code endedOn == null AND start ≤ today ≤ plannedEnd} — that the overlap guard, the
 * garage "rented" status, and the history status label all read from. Keeping it here means S-05's
 * overdue engine and S-06's dashboard inherit a single definition.
 *
 * <p>Both {@code @ManyToOne} sides are the FK-owning child side; there is <b>no</b> {@code @OneToMany}
 * back-collection on {@link Tenant} or {@link Garage} (the no-parent-collection rule). Column mapping
 * must match {@code V6__contracts.sql} exactly ({@code ddl-auto=validate}).
 */
@Entity
@Table(name = "contracts")
public class Contract extends ArchivableEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tenant_id", nullable = false)
	private Tenant tenant;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "garage_id", nullable = false)
	private Garage garage;

	@NotNull
	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@NotNull
	@Column(name = "planned_end_date", nullable = false)
	private LocalDate plannedEndDate;

	@NotNull
	@Positive
	@Column(name = "monthly_rent", nullable = false)
	private BigDecimal monthlyRent;

	@Min(1)
	@Max(28)
	@Column(name = "payment_day_of_month", nullable = false)
	private int paymentDayOfMonth;

	/**
	 * Days of grace added to {@code paymentDayOfMonth} before a period counts as overdue (FR-013).
	 * A per-contract historical fact — changing one contract never reclassifies another's past
	 * periods — defaulted to 5 at both the entity and DB level (S-05). The overdue engine reads this
	 * per contract; it is not yet owner-editable, so every contract carries the default for now.
	 */
	@Min(0)
	@Column(name = "grace_days", nullable = false)
	private int graceDays = 5;

	@Column(name = "ended_on")
	private LocalDate endedOn;

	protected Contract() {
		// JPA requires a no-arg constructor.
	}

	public Contract(Tenant tenant, Garage garage, LocalDate startDate, LocalDate plannedEndDate,
			BigDecimal monthlyRent, int paymentDayOfMonth) {
		this.tenant = tenant;
		this.garage = garage;
		this.startDate = startDate;
		this.plannedEndDate = plannedEndDate;
		this.monthlyRent = monthlyRent;
		this.paymentDayOfMonth = paymentDayOfMonth;
	}

	/**
	 * Surrogate key, widened to {@code public} so views can pass it to {@code ContractService} (the
	 * protected {@code BaseEntity#getId} is reachable only by subclasses, and views are not).
	 */
	@Override
	public Long getId() {
		return super.getId();
	}

	/**
	 * Record the actual move-out date, ending this contract early (FR-010). The actual end must fall
	 * within the agreed window ({@code startDate ≤ actualEnd ≤ plannedEndDate}); a contract already
	 * ended cannot be ended again.
	 */
	public void endEarly(LocalDate actualEnd) {
		if (isEnded()) {
			throw new IllegalArgumentException("Contract is already ended");
		}
		if (actualEnd.isBefore(startDate)) {
			throw new IllegalArgumentException("Actual end date cannot be before the start date");
		}
		if (actualEnd.isAfter(plannedEndDate)) {
			throw new IllegalArgumentException("Actual end date cannot be after the planned end date");
		}
		this.endedOn = actualEnd;
	}

	/** @return {@code true} once an actual move-out date has been recorded. */
	public boolean isEnded() {
		return endedOn != null;
	}

	/**
	 * @return {@code true} if this contract is currently active on {@code today}: not ended, started
	 *         on or before {@code today}, and not past its planned end. The single source of truth for
	 *         "currently active" across the overlap guard, the "rented" status, and the history label.
	 */
	public boolean isActiveOn(LocalDate today) {
		return endedOn == null && !startDate.isAfter(today) && !plannedEndDate.isBefore(today);
	}

	public Tenant getTenant() {
		return tenant;
	}

	public Garage getGarage() {
		return garage;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getPlannedEndDate() {
		return plannedEndDate;
	}

	public BigDecimal getMonthlyRent() {
		return monthlyRent;
	}

	public int getPaymentDayOfMonth() {
		return paymentDayOfMonth;
	}

	/** @return the per-contract grace days (FR-013); defaults to 5. */
	public int getGraceDays() {
		return graceDays;
	}

	public LocalDate getEndedOn() {
		return endedOn;
	}
}
