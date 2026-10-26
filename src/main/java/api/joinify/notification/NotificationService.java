package api.joinify.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import api.joinify.organization.OrganizationAccess;

/**
 * Cola de notificaciones por correo (ADR 0011). Quien produce un aviso lo encola y se olvida: el envío, los
 * reintentos y el registro son de este módulo.
 */
@Service
public class NotificationService {

	/**
	 * Un aviso por enviar.
	 *
	 * @param type            tipo de plantilla (ver {@link NotificationRenderer#type()})
	 * @param params          datos que necesita la plantilla: identificadores, nunca contenido ni secretos
	 * @param dedupeKey       el mismo aviso no se encola dos veces; si es nula se genera una única (aviso que siempre se envía)
	 * @param sendAfter       cuándo enviarlo; nula = ahora
	 */
	public record Request(String type, String recipientEmail, String recipientUserId, String organizationId, String eventId,
			Map<String, String> params, String dedupeKey, Instant sendAfter) {
	}

	/** Una fila del registro de envíos. */
	public record LogEntry(String id, String type, String recipientEmail, String status, int attempts, String subject, String lastError,
			Instant createdAt, Instant sentAt, Instant nextAttemptAt) {
	}

	private final NotificationRepository repository;
	private final ApplicationEventPublisher events;
	private final OrganizationAccess access;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final ObjectMapper mapper = new ObjectMapper();

	NotificationService(NotificationRepository repository, ApplicationEventPublisher events, OrganizationAccess access,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.repository = repository;
		this.events = events;
		this.access = access;
		// Transacción propia: se encola desde oyentes que corren después del commit de otra transacción.
		this.tx = new TransactionTemplate(transactionManager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	/** Encola el aviso. Devuelve su id, o vacío si ya había uno con la misma clave (no se duplica). */
	public Optional<String> enqueue(Request request) {
		LocalDateTime now = now();
		LocalDateTime sendAfter = request.sendAfter() == null ? now : LocalDateTime.ofInstant(request.sendAfter(), ZoneOffset.UTC);
		String key = request.dedupeKey() != null ? request.dedupeKey() : "uniq:" + java.util.UUID.randomUUID();
		String id;
		try {
			id = tx.execute(status -> repository.insert(request.type(), request.recipientEmail().trim(), request.recipientUserId(),
					request.organizationId(), request.eventId(), json(request.params()), key, sendAfter, now));
		} catch (DuplicateKeyException e) {
			return Optional.empty();
		}
		if (!sendAfter.isAfter(now)) {
			events.publishEvent(new NotificationQueued(id)); // fuera de la transacción: el despachador ve la fila confirmada
		}
		return Optional.of(id);
	}

	/** Cuántas notificaciones de un tipo con esa clave se crearon desde {@code since} (para limitar reenvíos). */
	public long countRecent(String type, String dedupePrefix, Instant since) {
		return repository.countRecent(type, dedupePrefix, LocalDateTime.ofInstant(since, ZoneOffset.UTC));
	}

	/** Registro de envíos de un evento, para su organizador (OWNER o ADMIN). */
	public List<LogEntry> log(String actorUserId, String organizationId, String eventId, String status, int limit) {
		access.requireManager(organizationId, actorUserId);
		String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
		return repository.findByEvent(organizationId, eventId, filter, Math.min(Math.max(limit, 1), 200)).stream().map(NotificationService::entry).toList();
	}

	/** Reactiva un envío que agotó sus intentos. */
	public void retry(String actorUserId, String organizationId, String notificationId) {
		access.requireManager(organizationId, actorUserId);
		var row = repository.find(notificationId).filter(n -> organizationId.equals(n.organizationId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificación no encontrada"));
		if (!repository.requeueFailed(row.id(), now())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se pueden reintentar los envíos fallidos");
		}
		events.publishEvent(new NotificationQueued(row.id()));
	}

	private static LogEntry entry(NotificationRepository.Row r) {
		return new LogEntry(r.id(), r.type(), r.recipientEmail(), r.status(), r.attempts(), r.subject(), r.lastError(),
				r.createdAt().toInstant(ZoneOffset.UTC), r.sentAt() == null ? null : r.sentAt().toInstant(ZoneOffset.UTC),
				r.nextAttemptAt().toInstant(ZoneOffset.UTC));
	}

	private String json(Map<String, String> params) {
		try {
			return mapper.writeValueAsString(params == null ? Map.of() : params);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Parámetros de notificación inválidos", e);
		}
	}
}
