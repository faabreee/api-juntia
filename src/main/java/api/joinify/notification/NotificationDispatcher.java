package api.joinify.notification;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import api.joinify.notification.NotificationRenderer.Rendered;
import api.joinify.notification.NotificationRenderer.SkipNotificationException;
import jakarta.mail.internet.MimeMessage;

/**
 * Envía las notificaciones de la cola (ADR 0011).
 *
 * - Cada envío se toma con un UPDATE condicional: varios despachadores (o varias instancias) nunca envían el mismo.
 * - Quien toma un envío lo "alquila" por unos minutos; si cae antes de terminar, otro lo retoma al vencer el alquiler.
 * - Un fallo reprograma el envío con espera creciente; agotados los intentos queda FAILED y el organizador puede reactivarlo.
 * - Es "al menos una vez": si el proceso cae justo después de enviar y antes de anotarlo, el correo puede repetirse.
 */
@Component
public class NotificationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
	/** Espera tras el intento n (1, 2, …): 1 min, 5 min, 30 min, 2 h, 6 h. */
	static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2),
			Duration.ofHours(6));
	private static final Duration LEASE = Duration.ofMinutes(2);

	private final NotificationRepository repository;
	private final JavaMailSender mailSender;
	private final Map<String, NotificationRenderer> renderers;
	private final Clock clock;
	private final String from;
	private final int maxAttempts;
	private final ObjectMapper mapper = new ObjectMapper();

	NotificationDispatcher(NotificationRepository repository, JavaMailSender mailSender, List<NotificationRenderer> renderers, Clock clock,
			@Value("${app.mail.from}") String from, @Value("${app.notifications.max-attempts}") int maxAttempts) {
		this.repository = repository;
		this.mailSender = mailSender;
		this.renderers = renderers.stream().collect(Collectors.toMap(NotificationRenderer::type, Function.identity()));
		this.clock = clock;
		this.from = from;
		this.maxAttempts = maxAttempts;
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	/** Intento inmediato de lo recién encolado, fuera del hilo de quien lo encoló. */
	@Async
	@EventListener
	void onQueued(NotificationQueued event) {
		try {
			deliver(event.id());
		} catch (RuntimeException e) {
			log.error("Falló el envío inmediato de la notificación {}", event.id(), e);
		}
	}

	/** Envía los pendientes cuya hora llegó. Devuelve cuántos se enviaron con éxito. */
	public int dispatchDue(int limit) {
		int sent = 0;
		for (String id : repository.findDueIds(now(), limit)) {
			if (deliver(id)) {
				sent++;
			}
		}
		return sent;
	}

	/** Toma y envía una notificación. Devuelve true solo si salió el correo. */
	boolean deliver(String id) {
		LocalDateTime now = now();
		if (!repository.claim(id, now, now.plus(LEASE))) {
			return false; // otro despachador la tomó, o todavía no es su hora
		}
		var row = repository.find(id).orElseThrow();
		try {
			NotificationRenderer renderer = renderers.get(row.type());
			if (renderer == null) {
				repository.markFailed(id, "No hay plantilla para el tipo " + row.type());
				return false;
			}
			Map<String, String> params = mapper.readValue(row.params(), new TypeReference<Map<String, String>>() {
			});
			Rendered rendered = renderer.render(params);
			send(row.recipientEmail(), rendered);
			repository.markSent(id, rendered.subject(), now());
			return true;
		} catch (SkipNotificationException e) {
			repository.markSkipped(id, e.getMessage());
			return false;
		} catch (Exception e) {
			fail(id, row.attempts(), e);
			return false;
		}
	}

	private void fail(String id, int attempts, Exception e) {
		String error = e.getClass().getSimpleName() + ": " + e.getMessage();
		if (attempts >= maxAttempts) {
			repository.markFailed(id, error);
			log.error("Notificación {} fallida tras {} intentos: {}", id, attempts, error);
		} else {
			Duration wait = BACKOFF.get(Math.min(attempts, BACKOFF.size()) - 1);
			repository.markRetry(id, now().plus(wait), error);
			log.warn("Notificación {}: intento {} fallido; se reintenta en {}", id, attempts, wait);
		}
	}

	private void send(String to, Rendered rendered) throws Exception {
		MimeMessage message = mailSender.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
		helper.setFrom(from);
		helper.setTo(to);
		helper.setSubject(rendered.subject());
		helper.setText(rendered.text(), rendered.html()); // texto plano + HTML: cada cliente de correo usa el que prefiere
		for (var attachment : rendered.attachments()) {
			helper.addAttachment(attachment.filename(), new ByteArrayResource(attachment.content()), attachment.contentType());
		}
		mailSender.send(message);
	}
}
