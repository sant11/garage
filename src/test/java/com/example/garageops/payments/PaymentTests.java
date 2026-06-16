package com.example.garageops.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.garageops.contracts.Contract;
import com.example.garageops.garages.Garage;
import com.example.garageops.locations.Location;
import com.example.garageops.tenants.Tenant;

/**
 * Locks {@link Payment} object behavior DB-free, mirroring {@code ContractTests} / {@code GarageTests}:
 * the constructor records its fields, the optional note may be absent, and a new payment is not
 * archived. No database, no Spring context.
 */
class PaymentTests {

	private static final LocalDate DATE = LocalDate.of(2026, 6, 10);
	private static final BigDecimal AMOUNT = new BigDecimal("250.00");

	private Contract newContract() {
		Tenant tenant = new Tenant("Acme Co.", null);
		Garage garage = new Garage(new Location("Downtown"), "A-1", new BigDecimal("250.00"));
		return new Contract(tenant, garage, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
			new BigDecimal("250.00"), 10);
	}

	@Test
	void newPaymentHoldsItsFieldsAndIsNotArchived() {
		Contract contract = newContract();

		Payment payment = new Payment(contract, AMOUNT, DATE, "June rent");

		assertThat(payment.getContract()).isSameAs(contract);
		assertThat(payment.getAmount()).isEqualByComparingTo(AMOUNT);
		assertThat(payment.getDate()).isEqualTo(DATE);
		assertThat(payment.getNote()).isEqualTo("June rent");
		assertThat(payment.isArchived()).isFalse();
	}

	@Test
	void noteIsOptional() {
		Payment payment = new Payment(newContract(), AMOUNT, DATE, null);

		assertThat(payment.getNote()).isNull();
	}

	@Test
	void archiveStampsAndRetainsTheRow() {
		Payment payment = new Payment(newContract(), AMOUNT, DATE, null);

		payment.archive();

		assertThat(payment.isArchived()).isTrue();
		assertThat(payment.getArchivedAt()).isNotNull();
	}
}
