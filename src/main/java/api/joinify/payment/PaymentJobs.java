package api.joinify.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Conciliación periódica: resuelve pagos que quedaron abiertos y vigila que el libro cuadre. */
@Component
class PaymentJobs {

	private static final Logger log = LoggerFactory.getLogger(PaymentJobs.class);

	private final PaymentService payments;

	PaymentJobs(PaymentService payments) {
		this.payments = payments;
	}

	@Scheduled(fixedDelayString = "${app.payments.reconcile-interval-ms}", initialDelayString = "${app.payments.reconcile-interval-ms}")
	void reconcilePendingPayments() {
		try {
			int changed = payments.reconcilePending();
			if (changed > 0) {
				log.info("Conciliación: {} pago(s) resuelto(s)", changed);
			}
		} catch (RuntimeException e) {
			log.error("Falló la conciliación de pagos", e);
		}
	}

	@Scheduled(cron = "0 0 4 * * *")
	void verifyLedger() {
		try {
			var problems = payments.checkLedger();
			if (!problems.isEmpty()) {
				log.error("El libro de movimientos no cuadra: {}", problems);
			}
		} catch (RuntimeException e) {
			log.error("Falló la verificación del libro", e);
		}
	}
}
