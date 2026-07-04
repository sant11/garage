package com.example.garageops.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;
import com.example.garageops.garages.Garage;
import com.example.garageops.locations.Location;
import com.example.garageops.tenants.Tenant;

import jakarta.persistence.EntityNotFoundException;

/**
 * Verifies {@link PaymentService} business logic with mocked repositories — no Spring context, no
 * database, mirroring {@code ContractServiceTests}.
 *
 * <p>Two oracles: <b>record</b> persists a valid payment and rejects the invalid cases (archived
 * contract, non-positive amount, missing date, unknown contract) saving nothing; and the
 * <b>archive cascade</b> stamps a contract's live payments and <em>never</em> deletes (FR-021 / R4).
 */
class PaymentServiceTests {

	private static final BigDecimal RENT = new BigDecimal("250.00");
	private static final LocalDate WHEN = LocalDate.of(2026, 6, 12);

	private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
	private final ContractRepository contractRepository = mock(ContractRepository.class);
	private final PaymentService service =
		new PaymentService(providerOf(paymentRepository), providerOf(contractRepository));

	// Wrap a mock repository in a mocked ObjectProvider, mirroring the production ObjectProvider
	// wiring exercised by the sibling service tests.
	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		given(provider.getObject()).willReturn(bean);
		return provider;
	}

	@Test
	void recordSavesAPaymentAgainstTheContract() {
		Contract contract = contract();
		given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
		given(paymentRepository.save(any(Payment.class))).willAnswer(call -> call.getArgument(0));

		service.record(1L, new BigDecimal("100.00"), WHEN, "June part 1");

		ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
		verify(paymentRepository).save(saved.capture());
		Payment payment = saved.getValue();
		assertThat(payment.getContract()).isSameAs(contract);
		assertThat(payment.getAmount()).isEqualByComparingTo("100.00");
		assertThat(payment.getDate()).isEqualTo(WHEN);
		assertThat(payment.getNote()).isEqualTo("June part 1");
	}

	@Test
	void recordRejectsAnUnknownContract() {
		given(contractRepository.findById(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.record(1L, new BigDecimal("100.00"), WHEN, null))
			.isInstanceOf(EntityNotFoundException.class);

		verify(paymentRepository, never()).save(any());
	}

	@Test
	void recordRejectsAnArchivedContractAndSavesNothing() {
		Contract contract = contract();
		contract.archive();
		given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

		assertThatThrownBy(() -> service.record(1L, new BigDecimal("100.00"), WHEN, null))
			.isInstanceOf(IllegalStateException.class);

		verify(paymentRepository, never()).save(any());
	}

	@Test
	void recordRejectsANonPositiveAmountAndSavesNothing() {
		Contract contract = contract();
		given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

		assertThatThrownBy(() -> service.record(1L, BigDecimal.ZERO, WHEN, null))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> service.record(1L, new BigDecimal("-10.00"), WHEN, null))
			.isInstanceOf(IllegalStateException.class);

		verify(paymentRepository, never()).save(any());
	}

	@Test
	void recordRejectsAMissingDateAndSavesNothing() {
		Contract contract = contract();
		given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

		assertThatThrownBy(() -> service.record(1L, new BigDecimal("100.00"), null, null))
			.isInstanceOf(IllegalStateException.class);

		verify(paymentRepository, never()).save(any());
	}

	@Test
	void archivePaymentsForContractsStampsLivePaymentsWithoutDeleting() {
		Contract contract = contract();
		Payment p1 = new Payment(contract, new BigDecimal("100.00"), WHEN, null);
		Payment p2 = new Payment(contract, new BigDecimal("150.00"), WHEN, null);
		given(paymentRepository.findByContractIdInAndArchivedAtIsNull(List.of(7L)))
			.willReturn(List.of(p1, p2));

		service.archivePaymentsForContracts(List.of(7L));

		// Each payment is stamped — retained, never deleted (FR-021 / R4).
		assertThat(p1.isArchived()).isTrue();
		assertThat(p2.isArchived()).isTrue();
		verify(paymentRepository).saveAll(List.of(p1, p2));
		verify(paymentRepository, never()).delete(any());
		verify(paymentRepository, never()).deleteById(anyLong());
		verify(paymentRepository, never()).deleteAll();
		verify(paymentRepository, never()).deleteAll(any());
	}

	@Test
	void archivePaymentsForContractsIsANoOpForNoContractsWithoutQuerying() {
		service.archivePaymentsForContracts(List.of());

		verify(paymentRepository, never()).findByContractIdInAndArchivedAtIsNull(any());
		verify(paymentRepository, never()).saveAll(any());
	}

	private static Contract contract() {
		Garage garage = new Garage(new Location("Downtown"), "A-1", RENT);
		return new Contract(new Tenant("Acme Co", null), garage,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), RENT, 10);
	}
}
