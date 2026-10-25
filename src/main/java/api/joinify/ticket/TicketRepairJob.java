package api.joinify.ticket;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Red de seguridad: completa emisiones interrumpidas y anula boletos de órdenes reembolsadas que quedaron vigentes. */
@Component
class TicketRepairJob {

	private static final Logger log = LoggerFactory.getLogger(TicketRepairJob.class);
	private static final int GRACE_SECONDS = 30;

	private final TicketRepository repository;
	private final TicketIssuer issuer;
	private final Clock clock;

	TicketRepairJob(TicketRepository repository, TicketIssuer issuer, Clock clock) {
		this.repository = repository;
		this.issuer = issuer;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${app.tickets.repair-interval-ms}", initialDelayString = "${app.tickets.repair-interval-ms}")
	void run() {
		try {
			repair();
		} catch (RuntimeException e) {
			log.error("Falló la reparación de boletos", e);
		}
	}

	/** Devuelve cuántas órdenes se corrigieron. */
	int repair() {
		int fixed = 0;
		LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusSeconds(GRACE_SECONDS);
		for (String orderId : repository.findPaidOrdersMissingTickets(cutoff, 100)) {
			if (issuer.issue(orderId) > 0) {
				fixed++;
			}
		}
		for (String orderId : repository.findRefundedOrdersWithValidTickets(100)) {
			if (issuer.voidTickets(orderId, "REFUNDED") > 0) {
				fixed++;
			}
		}
		if (fixed > 0) {
			log.warn("Reparación de boletos: {} orden(es) corregida(s)", fixed);
		}
		return fixed;
	}
}
