package api.joinify.ticket;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import api.joinify.order.OrderService;
import api.joinify.order.OrderService.IssuanceData;

/**
 * Emisión de boletos de una orden pagada (ADR 0009). Idempotente: se puede llamar cuantas veces haga falta (el evento
 * de dominio, el reintento y la reparación convergen aquí) y nunca duplica boletos, porque cada boleto es la pareja
 * única (línea de la orden, número) y se crea solo si falta.
 */
@Service
class TicketIssuer {

	private static final Logger log = LoggerFactory.getLogger(TicketIssuer.class);
	private static final int MAX_CODE_ATTEMPTS = 8;

	private final TicketRepository repository;
	private final OrderService orders;
	private final ApplicationEventPublisher events;
	private final TransactionTemplate tx;
	private final Clock clock;

	TicketIssuer(TicketRepository repository, OrderService orders, ApplicationEventPublisher events,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.repository = repository;
		this.orders = orders;
		this.events = events;
		// Transacción propia: la emisión se dispara después del commit de la orden, cuando la transacción original ya cerró.
		this.tx = new TransactionTemplate(transactionManager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	/** Emite los boletos que falten de la orden. Devuelve cuántos creó (0 si no está pagada o ya estaban todos). */
	int issue(String orderId) {
		AtomicInteger created = new AtomicInteger();
		tx.executeWithoutResult(status -> {
			IssuanceData data = orders.issuanceDataOf(orderId).orElse(null);
			if (data == null) {
				return; // no está pagada (o no existe): nada que emitir
			}
			LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
			for (IssuanceData.Line line : data.lines()) {
				for (int seq = 1; seq <= line.quantity(); seq++) {
					if (!repository.exists(line.orderItemId(), seq) && create(data, line, seq, now)) {
						created.incrementAndGet();
					}
				}
			}
		});
		if (created.get() > 0) {
			log.info("Orden {}: {} boleto(s) emitido(s)", orderId, created.get());
			events.publishEvent(new TicketsIssued(orderId)); // fuera de la transacción: el correo ve los boletos ya guardados
		}
		return created.get();
	}

	private boolean create(IssuanceData data, IssuanceData.Line line, int seq, LocalDateTime now) {
		for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
			try {
				repository.insert(UUID.randomUUID().toString(), data.orderId(), line.orderItemId(), seq, data.eventId(),
						line.ticketTypeId(), data.buyerId(), ShortCode.generate(), now);
				return true;
			} catch (DuplicateKeyException e) {
				// Otro emisor creó este mismo boleto a la vez: no es un error, ya existe.
				if (!String.valueOf(e.getMessage()).contains("uq_tickets_code")) {
					return false;
				}
				// Si no, el código corto coincidió con otro boleto (muy improbable): se reintenta con otro código.
			}
		}
		throw new IllegalStateException("No se pudo generar un código corto único para la orden " + data.orderId());
	}

	/** Anula los boletos vigentes de una orden (reembolso total). Devuelve cuántos anuló. */
	int voidTickets(String orderId, String reason) {
		Integer voided = tx.execute(status -> repository.voidByOrder(orderId, reason, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
		return voided == null ? 0 : voided;
	}
}
