package api.joinify.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cada pocos minutos encola los recordatorios de las sesiones que empiezan en las próximas 24 horas. */
@Component
class TicketReminderJob {

	private static final Logger log = LoggerFactory.getLogger(TicketReminderJob.class);

	private final TicketNotifications notifications;

	TicketReminderJob(TicketNotifications notifications) {
		this.notifications = notifications;
	}

	@Scheduled(fixedDelayString = "${app.notifications.reminder-interval-ms}", initialDelayString = "${app.notifications.reminder-interval-ms}")
	void run() {
		try {
			int queued = notifications.enqueueReminders();
			if (queued > 0) {
				log.info("{} recordatorio(s) encolado(s)", queued);
			}
		} catch (RuntimeException e) {
			log.error("Falló la generación de recordatorios", e);
		}
	}
}
