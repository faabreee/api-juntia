package api.joinify.ticket;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import api.joinify.identity.UserDirectory;
import api.joinify.order.OrderService;
import api.joinify.ticket.TicketService.TicketDetail;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/** Envía al comprador sus boletos en PDF. Asíncrono y tolerante a fallos: el boleto ya existe y se ve en "Mis boletos". */
@Component
class TicketMailer {

	private static final Logger log = LoggerFactory.getLogger(TicketMailer.class);

	private final JavaMailSender mailSender;
	private final TicketService tickets;
	private final TicketPdfService pdf;
	private final UserDirectory users;
	private final OrderService orders;
	private final String from;
	private final String frontendUrl;

	TicketMailer(JavaMailSender mailSender, TicketService tickets, TicketPdfService pdf, UserDirectory users, OrderService orders,
			@Value("${app.mail.from}") String from, @Value("${app.frontend-url}") String frontendUrl) {
		this.mailSender = mailSender;
		this.tickets = tickets;
		this.pdf = pdf;
		this.users = users;
		this.orders = orders;
		this.from = from;
		this.frontendUrl = frontendUrl;
	}

	@Async
	@EventListener
	void onTicketsIssued(TicketsIssued event) {
		try {
			send(event.orderId());
		} catch (RuntimeException | MessagingException e) {
			log.error("No se pudo enviar el correo de boletos de la orden {}", event.orderId(), e);
		}
	}

	void send(String orderId) throws MessagingException {
		List<TicketDetail> details = tickets.detailsOfOrder(orderId);
		if (details.isEmpty()) {
			return;
		}
		String buyerId = orders.buyerOf(orderId).orElse(null);
		var contact = buyerId == null ? null : users.contactOf(buyerId).orElse(null);
		if (contact == null) {
			return;
		}
		var event = details.getFirst().event();
		DateTimeFormatter format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.withLocale(Locale.forLanguageTag("es")).withZone(ZoneId.of(event.timezone()));
		String what = details.size() == 1 ? "Aquí está tu boleto" : "Aquí están tus " + details.size() + " boletos";
		StringBuilder body = new StringBuilder("Hola ").append(contact.displayName()).append(",\n\n¡Tu compra está confirmada! ")
				.append(what).append(" para ").append(event.title()).append(".\n\n");
		event.sessions().forEach(s -> body.append("• ").append(s.name()).append(": ").append(format.format(s.startsAt())).append('\n'));
		body.append("\nTambién los encuentras siempre en Mis boletos:\n").append(frontendUrl).append("/tickets\n\n")
				.append("Adjuntamos un PDF con " + (details.size() == 1 ? "tu boleto" : "todos los boletos") + ". Presenta el código QR en la entrada.\n");

		MimeMessage message = mailSender.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
		helper.setFrom(from);
		helper.setTo(contact.email());
		helper.setSubject("Tus boletos para " + event.title());
		helper.setText(body.toString());
		helper.addAttachment("boletos-" + orderId.substring(0, 8) + ".pdf", new ByteArrayResource(pdf.render(details)), "application/pdf");
		mailSender.send(message);
	}
}
