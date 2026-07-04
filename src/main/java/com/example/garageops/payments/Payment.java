package com.example.garageops.payments;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.example.garageops.contracts.Contract;
import com.example.garageops.persistence.ArchivableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * One recorded rent payment tied to a {@link Contract} (FR-012/FR-014). Mirrors {@code Contract}'s
 * structure: a FK-owning child side {@code @ManyToOne(LAZY)} to the contract, with <b>no</b>
 * {@code @OneToMany} back-collection on {@link Contract} (the no-parent-collection rule) — the
 * period paid-sum the overdue engine needs comes from a {@code PaymentRepository} aggregation query,
 * never from {@code contract.getPayments()}.
 *
 * <p>Archivable per FR-021: archiving stamps {@code archived_at} and retains the row, so a payment
 * is never deleted. Archiving a contract cascade-archives its payments (S-05 service layer).
 *
 * <p>Period membership is by {@code date} alone — there is no stored "period" column; the rule sums
 * {@code amount} over the dates falling in the period it resolves. Column mapping must match
 * {@code V7__payments.sql} exactly ({@code ddl-auto=validate}).
 */
@Entity
@Table(name = "payments")
public class Payment extends ArchivableEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "contract_id", nullable = false)
	private Contract contract;

	@NotNull
	@Positive
	@Column(name = "amount", nullable = false)
	private BigDecimal amount;

	@NotNull
	@Column(name = "date", nullable = false)
	private LocalDate date;

	@Column(name = "note")
	private String note;

	protected Payment() {
		// JPA requires a no-arg constructor.
	}

	public Payment(Contract contract, BigDecimal amount, LocalDate date, String note) {
		this.contract = contract;
		this.amount = amount;
		this.date = date;
		this.note = note;
	}

	/**
	 * Surrogate key, widened to {@code public} so views can pass it to the payment services (the
	 * protected {@code BaseEntity#getId} is reachable only by subclasses, and views are not).
	 */
	@Override
	public Long getId() {
		return super.getId();
	}

	public Contract getContract() {
		return contract;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public LocalDate getDate() {
		return date;
	}

	public String getNote() {
		return note;
	}
}
