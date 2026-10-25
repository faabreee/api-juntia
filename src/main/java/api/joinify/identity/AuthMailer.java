package api.joinify.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class AuthMailer {

	private static final Logger log = LoggerFactory.getLogger(AuthMailer.class);

	private final JavaMailSender mailSender;
	private final String from;
	private final String frontendUrl;

	AuthMailer(JavaMailSender mailSender, @Value("${app.mail.from}") String from,
			@Value("${app.frontend-url}") String frontendUrl) {
		this.mailSender = mailSender;
		this.from = from;
		this.frontendUrl = frontendUrl;
	}

	void sendVerification(String to, String name, String token) {
		send(to, "Verifica tu correo en Juntia",
				"Hola " + name + ",\n\nConfirma tu correo aquí (válido 24 horas):\n"
						+ frontendUrl + "/verify-email?token=" + token + "\n");
	}

	void sendPasswordReset(String to, String name, String token) {
		send(to, "Recupera tu contraseña de Juntia",
				"Hola " + name + ",\n\nElige una nueva contraseña aquí (válido 1 hora):\n"
						+ frontendUrl + "/reset-password?token=" + token
						+ "\n\nSi no lo pediste, ignora este mensaje.\n");
	}

	private void send(String to, String subject, String text) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(text);
		try {
			mailSender.send(message);
		} catch (MailException e) {
			// No se revela al cliente; puede reintentar (reenvío / recuperación).
			log.error("No se pudo enviar el correo '{}'", subject, e);
		}
	}
}
