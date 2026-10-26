package api.joinify.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Barrido periódico: envía lo que quedó pendiente (reintentos, envíos diferidos, alquileres vencidos). */
@Component
@EnableAsync
class NotificationJobs {

	private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);

	private final NotificationDispatcher dispatcher;

	NotificationJobs(NotificationDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${app.notifications.dispatch-interval-ms}", initialDelayString = "${app.notifications.dispatch-interval-ms}")
	void dispatchPending() {
		try {
			int sent = dispatcher.dispatchDue(100);
			if (sent > 0) {
				log.info("{} notificación(es) enviada(s) en el barrido", sent);
			}
		} catch (RuntimeException e) {
			log.error("Falló el barrido de notificaciones", e);
		}
	}
}
