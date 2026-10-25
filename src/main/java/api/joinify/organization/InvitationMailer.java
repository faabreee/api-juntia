package api.joinify.organization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class InvitationMailer {

	private static final Logger log = LoggerFactory.getLogger(InvitationMailer.class);

	private final JavaMailSender mailSender;
	private final String from;
	private final String frontendUrl;

	InvitationMailer(JavaMailSender mailSender, @Value("${app.mail.from}") String from,
			@Value("${app.frontend-url}") String frontendUrl) {
		this.mailSender = mailSender;
		this.from = from;
		this.frontendUrl = frontendUrl;
	}

	void sendInvitation(String to, String organizationName, Role role, String inviterName, String token) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(inviterName + " te invitó a " + organizationName + " en Juntia");
		message.setText(inviterName + " te invitó a unirte a \"" + organizationName + "\" como " + role.name()
				+ ".\n\nAcepta la invitación (válida 7 días) iniciando sesión con este correo:\n"
				+ frontendUrl + "/accept-invitation?token=" + token + "\n");
		try {
			mailSender.send(message);
		} catch (MailException e) {
			log.error("No se pudo enviar la invitación a la organización {}", organizationName, e);
		}
	}
}
