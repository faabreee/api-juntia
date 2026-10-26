package api.joinify.ticket;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import api.joinify.event.EventCatalog;
import api.joinify.event.EventCatalog.EventDetails;
import api.joinify.event.EventCatalog.SessionInfo;
import api.joinify.event.EventStatus;
import api.joinify.identity.UserDirectory;
import api.joinify.notification.EmailFormat;
import api.joinify.notification.EmailLayout;
import api.joinify.notification.NotificationRenderer;
import api.joinify.order.OrderService;
import api.joinify.ticket.TicketService.TicketDetail;

/** Plantillas de las notificaciones que dependen de los boletos. Cada una arma el correo con los datos vigentes. */
final class TicketRenderers {

	private TicketRenderers() {
	}

	private static String where(SessionInfo s) {
		if (s.venueName() == null) {
			return "";
		}
		return s.venueName() + (s.venueCity() == null ? "" : ", " + s.venueCity());
	}

	/** "• Día 1 — sábado, 15 de noviembre de 2026, 17:56 · Costa Verde Arena, Lima". */
	private static String sessionLine(SessionInfo s, String timezone) {
		String place = where(s);
		return s.name() + " — " + EmailFormat.dateTime(s.startsAt(), timezone) + (place.isEmpty() ? "" : " · " + place);
	}

	private static String listHtml(List<String> lines) {
		StringBuilder html = new StringBuilder("<ul style=\"margin:0 0 16px;padding-left:20px;line-height:1.7;font-size:15px;color:#111827\">");
		lines.forEach(l -> html.append("<li>").append(EmailLayout.esc(l)).append("</li>"));
		return html.append("</ul>").toString();
	}

	private static String listText(List<String> lines) {
		StringBuilder text = new StringBuilder();
		lines.forEach(l -> text.append("• ").append(l).append('\n'));
		return text.toString();
	}

	// ---------------------------------------------------------------- entrega de boletos

	@Component
	static class Delivery implements NotificationRenderer {

		private final TicketService tickets;
		private final TicketPdfService pdf;
		private final UserDirectory users;
		private final OrderService orders;
		private final String frontendUrl;

		Delivery(TicketService tickets, TicketPdfService pdf, UserDirectory users, OrderService orders, @Value("${app.frontend-url}") String frontendUrl) {
			this.tickets = tickets;
			this.pdf = pdf;
			this.users = users;
			this.orders = orders;
			this.frontendUrl = frontendUrl;
		}

		@Override
		public String type() {
			return TicketNotifications.DELIVERY;
		}

		@Override
		public Rendered render(Map<String, String> params) {
			String orderId = params.get("orderId");
			List<TicketDetail> details = tickets.detailsOfOrder(orderId);
			if (details.isEmpty()) {
				throw new SkipNotificationException("La orden no tiene boletos vigentes");
			}
			String name = orders.buyerOf(orderId).flatMap(users::contactOf).map(UserDirectory.Contact::displayName).orElse("");
			EventDetails event = details.getFirst().event();
			int n = details.size();
			String subject = "Tus boletos para " + event.title();
			String what = n == 1 ? "Aquí está tu boleto" : "Aquí están tus " + n + " boletos";
			List<String> sessions = event.sessions().stream().map(s -> sessionLine(s, event.timezone())).toList();
			String link = frontendUrl + "/tickets";

			String text = "Hola " + name + ",\n\n¡Tu compra está confirmada! " + what + " para " + event.title() + ".\n\n" + listText(sessions)
					+ "\nTambién los encuentras siempre en Mis boletos:\n" + link + "\n\nAdjuntamos un PDF con " + (n == 1 ? "tu boleto" : "todos los boletos")
					+ ". Presenta el código QR en la entrada.\n";
			String html = EmailLayout.wrap(subject, EmailLayout.h1(n == 1 ? "Tu boleto está listo" : "Tus boletos están listos")
					+ EmailLayout.p("Hola " + name + ", " + what.toLowerCase(java.util.Locale.ROOT) + " para " + event.title() + ".") + listHtml(sessions)
					+ EmailLayout.p("Presenta el código QR en la entrada. Adjuntamos un PDF con " + (n == 1 ? "tu boleto" : "todos los boletos") + ".")
					+ EmailLayout.button(link, "Ver mis boletos"));
			return new Rendered(subject, text, html,
					List.of(new Attachment("boletos-" + orderId.substring(0, 8) + ".pdf", "application/pdf", pdf.render(details))));
		}
	}

	// ---------------------------------------------------------------- recordatorio

	@Component
	static class Reminder implements NotificationRenderer {

		private final EventCatalog catalog;
		private final TicketRepository repository;
		private final UserDirectory users;
		private final Clock clock;
		private final String frontendUrl;

		Reminder(EventCatalog catalog, TicketRepository repository, UserDirectory users, Clock clock, @Value("${app.frontend-url}") String frontendUrl) {
			this.catalog = catalog;
			this.repository = repository;
			this.users = users;
			this.clock = clock;
			this.frontendUrl = frontendUrl;
		}

		@Override
		public String type() {
			return TicketNotifications.REMINDER;
		}

		@Override
		public Rendered render(Map<String, String> params) {
			EventDetails event = catalog.event(params.get("eventId"));
			if (event.status() != EventStatus.PUBLISHED) {
				throw new SkipNotificationException("El evento ya no está activo");
			}
			Map<String, Integer> counts = repository.validCountsByType(params.get("userId"), event.id());
			Instant now = clock.instant();
			ZoneId zone = ZoneId.of(event.timezone());
			// Las sesiones de ese día, todavía por empezar, que cubre algún boleto vigente de la persona.
			List<SessionInfo> sessions = event.sessions().stream()
					.filter(s -> s.startsAt().isAfter(now) && s.startsAt().atZone(zone).toLocalDate().toString().equals(params.get("date")))
					.filter(s -> counts.keySet().stream().anyMatch(typeId -> catalog.ticketType(typeId).rule().covers(s.id()))).toList();
			if (sessions.isEmpty()) {
				throw new SkipNotificationException("Ya no hay sesiones por empezar que cubran sus boletos");
			}
			int tickets = counts.values().stream().mapToInt(Integer::intValue).sum();
			String name = users.contactOf(params.get("userId")).map(UserDirectory.Contact::displayName).orElse("");
			String subject = "Recordatorio: " + event.title() + " es pronto";
			List<String> lines = sessions.stream().map(s -> sessionLine(s, event.timezone())).toList();
			String link = frontendUrl + "/tickets";

			String text = "Hola " + name + ",\n\nTe recordamos que " + event.title() + " empieza pronto.\n\n" + listText(lines) + "\nTienes " + tickets
					+ (tickets == 1 ? " boleto" : " boletos") + ". Ten a la mano el código QR (también en el PDF que te enviamos):\n" + link
					+ "\n\nLos horarios son de " + event.timezone() + ".\n";
			String html = EmailLayout.wrap(subject, EmailLayout.h1("¡Ya casi es hora!") + EmailLayout.p("Hola " + name + ", " + event.title() + " empieza pronto.")
					+ listHtml(lines) + EmailLayout.p("Tienes " + tickets + (tickets == 1 ? " boleto" : " boletos") + ". Ten a la mano tu código QR.")
					+ EmailLayout.p("Los horarios son de " + event.timezone() + ".") + EmailLayout.button(link, "Ver mis boletos"));
			return new Rendered(subject, text, html);
		}
	}

	// ---------------------------------------------------------------- evento cancelado

	@Component
	static class Cancelled implements NotificationRenderer {

		private final EventCatalog catalog;
		private final UserDirectory users;
		private final String frontendUrl;

		Cancelled(EventCatalog catalog, UserDirectory users, @Value("${app.frontend-url}") String frontendUrl) {
			this.catalog = catalog;
			this.users = users;
			this.frontendUrl = frontendUrl;
		}

		@Override
		public String type() {
			return TicketNotifications.CANCELLED;
		}

		@Override
		public Rendered render(Map<String, String> params) {
			EventDetails event = catalog.event(params.get("eventId"));
			if (event.status() != EventStatus.CANCELLED) {
				throw new SkipNotificationException("El evento ya no está cancelado");
			}
			String name = users.contactOf(params.get("userId")).map(UserDirectory.Contact::displayName).orElse("");
			String subject = "Evento cancelado: " + event.title();
			String reason = event.cancelReason() == null || event.cancelReason().isBlank() ? null : event.cancelReason();
			String link = frontendUrl + "/orders";

			String text = "Hola " + name + ",\n\nLamentamos avisarte que " + event.title() + ", organizado por " + event.organizationName() + ", fue cancelado.\n"
					+ (reason == null ? "" : "\nMotivo: " + reason + "\n")
					+ "\nTus boletos ya no darán acceso. Si pagaste, el organizador se encargará de tu reembolso; puedes seguir el estado de tu orden aquí:\n" + link + "\n";
			String html = EmailLayout.wrap(subject, EmailLayout.h1("Evento cancelado") + EmailLayout.p("Hola " + name + ", lamentamos avisarte que " + event.title()
					+ ", organizado por " + event.organizationName() + ", fue cancelado.") + (reason == null ? "" : EmailLayout.rows("Motivo", reason))
					+ EmailLayout.p("Tus boletos ya no darán acceso. Si pagaste, el organizador se encargará de tu reembolso.") + EmailLayout.button(link, "Ver mis órdenes"));
			return new Rendered(subject, text, html);
		}
	}

	// ---------------------------------------------------------------- evento modificado

	@Component
	static class Changed implements NotificationRenderer {

		private final EventCatalog catalog;
		private final TicketRepository repository;
		private final UserDirectory users;
		private final String frontendUrl;

		Changed(EventCatalog catalog, TicketRepository repository, UserDirectory users, @Value("${app.frontend-url}") String frontendUrl) {
			this.catalog = catalog;
			this.repository = repository;
			this.users = users;
			this.frontendUrl = frontendUrl;
		}

		@Override
		public String type() {
			return TicketNotifications.CHANGED;
		}

		@Override
		public Rendered render(Map<String, String> params) {
			EventDetails event = catalog.event(params.get("eventId"));
			if (event.status() != EventStatus.PUBLISHED) {
				throw new SkipNotificationException("El evento ya no está publicado");
			}
			Map<String, Integer> counts = repository.validCountsByType(params.get("userId"), event.id());
			if (counts.isEmpty()) {
				throw new SkipNotificationException("La persona ya no tiene boletos vigentes");
			}
			// Se muestra el estado actual de lo que cubren sus boletos (no un "antes y después").
			List<SessionInfo> sessions = event.sessions().stream()
					.filter(s -> counts.keySet().stream().anyMatch(typeId -> catalog.ticketType(typeId).rule().covers(s.id()))).toList();
			String name = users.contactOf(params.get("userId")).map(UserDirectory.Contact::displayName).orElse("");
			String subject = "Cambios en " + event.title();
			List<String> lines = sessions.stream().map(s -> sessionLine(s, event.timezone())).toList();
			String link = frontendUrl + "/tickets";

			String text = "Hola " + name + ",\n\nEl organizador actualizó la información de " + event.title() + ". Así quedan tus sesiones:\n\n" + listText(lines)
					+ "\nHorarios de " + event.timezone() + ". Tus boletos siguen siendo válidos. Si no puedes asistir con estos cambios, contacta al organizador ("
					+ event.organizationName() + ").\n" + link + "\n";
			String html = EmailLayout.wrap(subject, EmailLayout.h1("Hubo cambios en tu evento") + EmailLayout.p("Hola " + name + ", el organizador actualizó la información de "
					+ event.title() + ". Así quedan tus sesiones:") + listHtml(lines) + EmailLayout.p("Horarios de " + event.timezone() + ". Tus boletos siguen siendo válidos.")
					+ EmailLayout.p("Si no puedes asistir con estos cambios, contacta al organizador (" + event.organizationName() + ").") + EmailLayout.button(link, "Ver mis boletos"));
			return new Rendered(subject, text, html);
		}
	}
}
