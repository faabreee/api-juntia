package api.joinify.checkin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.checkin.CheckInRepository.CheckInRow;
import api.joinify.checkin.CheckInRepository.DeviceRow;
import api.joinify.checkin.CheckInRepository.EntryRow;
import api.joinify.event.AccessPolicy;
import api.joinify.event.AccessPolicy.DenyReason;
import api.joinify.event.AccessRule;
import api.joinify.event.EventCatalog;
import api.joinify.event.EventCatalog.EventDetails;
import api.joinify.event.EventCatalog.SessionInfo;
import api.joinify.event.EventStatus;
import api.joinify.identity.AuthUser;
import api.joinify.organization.OrganizationAccess;
import api.joinify.ticket.ShortCode;
import api.joinify.ticket.TicketDirectory;
import api.joinify.ticket.TicketDirectory.AttendeeMatch;
import api.joinify.ticket.TicketDirectory.TicketInfo;
import api.joinify.ticket.TicketTokenService;

/**
 * Validación en puerta (ADR 0010).
 *
 * Garantía central: un boleto nunca ingresa más veces de las que permite su regla, aunque varias puertas lo lean a
 * la vez. La decisión y el registro del ingreso ocurren en una transacción que primero bloquea la fila del boleto
 * y lee el historial ya confirmado (READ COMMITTED); la puerta que llega segunda espera y ve el ingreso de la primera.
 */
@Service
public class CheckInService {

	public enum Result {
		ACCEPTED, REJECTED
	}

	public enum Method {
		TOKEN, CODE, MANUAL
	}

	/** Motivos de rechazo que no vienen de {@link AccessPolicy}: el boleto ni siquiera llega a evaluarse. */
	public static final class Reasons {
		public static final String INVALID_TOKEN = "INVALID_TOKEN";
		public static final String INVALID_CODE = "INVALID_CODE";
		public static final String WRONG_EVENT = "WRONG_EVENT";
		public static final String TICKET_NOT_FOUND = "TICKET_NOT_FOUND";
		public static final String TICKET_VOID = "TICKET_VOID";
		public static final String EVENT_NOT_ACTIVE = "EVENT_NOT_ACTIVE";

		private Reasons() {
		}
	}

	public record Scan(String scanId, String deviceId, String sessionId, String token, String code, String ticketId) {
	}

	public record PreviousEntry(Instant at, String deviceName) {
	}

	public record Outcome(String checkInId, Result result, String reason, TicketInfo ticket, SessionInfo session,
			int entriesTotal, int entriesInSession, PreviousEntry previousEntry, Integer maxTotalUses, Integer maxUsesPerSession,
			boolean replayed) {
	}

	public record Summary(long validTickets, long ticketsEntered, long entries, long rejected, Map<String, Long> entriesBySession) {
	}

	public record Attendee(AttendeeMatch match, long entries) {
	}

	private static final Pattern SCAN_ID = Pattern.compile("[A-Za-z0-9._:-]{8,64}");
	private static final int MAX_AUDIT = 200;

	private final CheckInRepository repository;
	private final TicketDirectory tickets;
	private final TicketTokenService tokens;
	private final EventCatalog catalog;
	private final OrganizationAccess access;
	private final TransactionTemplate tx;
	private final Clock clock;

	CheckInService(CheckInRepository repository, TicketDirectory tickets, TicketTokenService tokens, EventCatalog catalog,
			OrganizationAccess access, PlatformTransactionManager transactionManager, Clock clock) {
		this.repository = repository;
		this.tickets = tickets;
		this.tokens = tokens;
		this.catalog = catalog;
		this.access = access;
		// READ COMMITTED: tras bloquear el boleto, el historial que se lee es el último confirmado (no una foto antigua).
		this.tx = new TransactionTemplate(transactionManager);
		this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.clock = clock;
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	// ---------- dispositivos ----------

	public DeviceRow registerDevice(AuthUser actor, String organizationId, String name) {
		access.requireMember(organizationId, actor.getId());
		String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
		if (clean.length() < 2 || clean.length() > 60) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre del dispositivo debe tener entre 2 y 60 caracteres");
		}
		String id = repository.insertDevice(organizationId, clean, actor.getId(), now());
		return repository.findDevice(id).orElseThrow();
	}

	public List<DeviceRow> devices(AuthUser actor, String organizationId) {
		access.requireManager(organizationId, actor.getId());
		return repository.findDevices(organizationId);
	}

	public void revokeDevice(AuthUser actor, String organizationId, String deviceId) {
		access.requireManager(organizationId, actor.getId());
		repository.findDevice(deviceId).filter(d -> d.organizationId().equals(organizationId))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispositivo no encontrado"));
		if (!repository.revokeDevice(deviceId, organizationId, actor.getId(), now())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El dispositivo ya estaba revocado");
		}
	}

	// ---------- ingreso ----------

	/** Procesa una lectura. Idempotente por {@code scanId}: reenviar la misma lectura devuelve el mismo resultado. */
	public Outcome scan(AuthUser validator, String organizationId, String eventId, Scan scan) {
		access.requireMember(organizationId, validator.getId());
		EventDetails event = eventOf(organizationId, eventId);
		DeviceRow device = repository.findDevice(scan.deviceId()).filter(d -> d.organizationId().equals(organizationId))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Dispositivo no registrado en esta organización"));
		if (device.revoked()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este dispositivo fue revocado");
		}
		if (scan.scanId() == null || !SCAN_ID.matcher(scan.scanId()).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scanId debe tener entre 8 y 64 caracteres (letras, números, . _ : -)");
		}
		int given = (scan.token() != null ? 1 : 0) + (scan.code() != null ? 1 : 0) + (scan.ticketId() != null ? 1 : 0);
		if (given != 1) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica exactamente uno: token, code o ticketId");
		}
		Optional<Outcome> replay = replay(scan.scanId(), device, eventId, event);
		if (replay.isPresent()) {
			return replay.get();
		}
		try {
			return process(validator, organizationId, event, device, scan);
		} catch (DuplicateKeyException e) {
			// Otra copia de esta misma lectura ganó la carrera: se devuelve su resultado.
			return replay(scan.scanId(), device, eventId, event).orElseThrow(() -> e);
		}
	}

	private Outcome process(AuthUser validator, String organizationId, EventDetails event, DeviceRow device, Scan scan) {
		Method method = scan.token() != null ? Method.TOKEN : scan.code() != null ? Method.CODE : Method.MANUAL;

		// 1) ¿Qué boleto es? Los rechazos de esta fase no dependen del historial: no hace falta bloquear nada.
		String rejection = null;
		TicketInfo ticket = null;
		switch (method) {
			case TOKEN -> {
				var verified = tokens.verify(scan.token());
				if (verified.isEmpty()) {
					rejection = Reasons.INVALID_TOKEN;
				} else if (!verified.get().eventId().equals(event.id())) {
					rejection = Reasons.WRONG_EVENT;
				} else {
					ticket = tickets.find(verified.get().ticketId()).orElse(null);
				}
			}
			case CODE -> {
				var code = ShortCode.normalize(scan.code());
				if (code.isEmpty()) {
					rejection = Reasons.INVALID_CODE;
				} else {
					ticket = tickets.findByCode(event.id(), code.get()).orElse(null);
				}
			}
			default -> ticket = tickets.find(scan.ticketId()).filter(t -> t.eventId().equals(event.id())).orElse(null);
		}
		if (rejection == null && (ticket == null || !ticket.eventId().equals(event.id()))) {
			rejection = Reasons.TICKET_NOT_FOUND;
		}
		if (rejection != null) {
			return recordRejection(validator, organizationId, event, device, scan, method, null, null, rejection);
		}

		// 2) Decisión y registro, atómicos y serializados por boleto.
		final TicketInfo found = ticket;
		Outcome outcome = tx.execute(status -> decide(validator, organizationId, event, device, scan, method, found));
		touch(device);
		return outcome;
	}

	/** Fuera de la transacción: es información auxiliar y no debe retener bloqueos ni poder tumbar un ingreso. */
	private void touch(DeviceRow device) {
		try {
			repository.touchDevice(device.id(), now());
		} catch (RuntimeException e) {
			// la lectura ya quedó registrada; la marca de actividad se actualizará en la siguiente
		}
	}

	private Outcome decide(AuthUser validator, String organizationId, EventDetails event, DeviceRow device, Scan scan, Method method,
			TicketInfo ticketRef) {
		tickets.lockForCheckIn(ticketRef.id());
		TicketInfo ticket = tickets.find(ticketRef.id()).orElse(ticketRef); // estado actual, ya con el bloqueo
		LocalDateTime now = now();
		Instant nowInstant = now.toInstant(ZoneOffset.UTC);
		AccessRule rule = catalog.ticketType(ticket.ticketTypeId()).rule();

		SessionInfo session = chooseSession(event, rule, scan.sessionId(), nowInstant);
		String rejection = null;
		if (event.status() != EventStatus.PUBLISHED) {
			rejection = Reasons.EVENT_NOT_ACTIVE;
		} else if (!ticket.valid()) {
			rejection = Reasons.TICKET_VOID;
		} else if (session == null) {
			rejection = DenyReason.SESSION_NOT_INCLUDED.name();
		} else {
			List<EntryRow> history = repository.acceptedEntries(ticket.id());
			AccessPolicy.Decision decision = AccessPolicy.evaluate(rule,
					new AccessPolicy.SessionSlot(session.id(), session.startsAt(), session.endsAt()),
					history.stream().map(e -> new AccessPolicy.Entry(e.sessionId(), e.at().toInstant(ZoneOffset.UTC))).toList(), nowInstant);
			if (!decision.allowed()) {
				rejection = decision.reason().name();
			}
		}

		String checkInId = repository.insertCheckIn(scan.scanId(), organizationId, event.id(), ticket.id(),
				session == null ? null : session.id(), device.id(), validator.getId(),
				rejection == null ? Result.ACCEPTED.name() : Result.REJECTED.name(), rejection, method.name(), now);
		if (rejection == null) {
			tickets.markEntered(ticket.id(), now);
		}
		return outcome(checkInId, rejection == null ? Result.ACCEPTED : Result.REJECTED, rejection, ticket, session, rule, false);
	}

	private Outcome recordRejection(AuthUser validator, String organizationId, EventDetails event, DeviceRow device, Scan scan,
			Method method, TicketInfo ticket, SessionInfo session, String reason) {
		LocalDateTime now = now();
		String id = tx.execute(status -> {
			return repository.insertCheckIn(scan.scanId(), organizationId, event.id(), ticket == null ? null : ticket.id(),
					session == null ? null : session.id(), device.id(), validator.getId(), Result.REJECTED.name(), reason, method.name(), now);
		});
		touch(device);
		return new Outcome(id, Result.REJECTED, reason, ticket, session, 0, 0, null, null, null, false);
	}

	private Optional<Outcome> replay(String scanId, DeviceRow device, String eventId, EventDetails event) {
		return repository.findByScanId(scanId).map(row -> {
			if (!row.deviceId().equals(device.id()) || !row.eventId().equals(eventId)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "El scanId ya se usó en otra lectura");
			}
			TicketInfo ticket = row.ticketId() == null ? null : tickets.find(row.ticketId()).orElse(null);
			SessionInfo session = row.sessionId() == null ? null
					: event.sessions().stream().filter(s -> s.id().equals(row.sessionId())).findFirst().orElse(null);
			AccessRule rule = ticket == null ? null : catalog.ticketType(ticket.ticketTypeId()).rule();
			return outcome(row.id(), Result.valueOf(row.result()), row.reason(), ticket, session, rule, true);
		});
	}

	/** Arma la respuesta con los contadores y el último ingreso previo (para explicar un rechazo). */
	private Outcome outcome(String checkInId, Result result, String reason, TicketInfo ticket, SessionInfo session, AccessRule rule,
			boolean replayed) {
		if (ticket == null) {
			return new Outcome(checkInId, result, reason, null, session, 0, 0, null, null, null, replayed);
		}
		List<EntryRow> entries = repository.acceptedEntries(ticket.id());
		int inSession = session == null ? 0 : (int) entries.stream().filter(e -> session.id().equals(e.sessionId())).count();
		PreviousEntry previous = null;
		if (result == Result.REJECTED && !entries.isEmpty()
				&& (DenyReason.ALREADY_USED_IN_SESSION.name().equals(reason) || DenyReason.TOTAL_USES_EXHAUSTED.name().equals(reason))) {
			EntryRow last = entries.getLast();
			previous = new PreviousEntry(last.at().toInstant(ZoneOffset.UTC), last.deviceName());
		}
		return new Outcome(checkInId, result, reason, ticket, session, entries.size(), inSession, previous,
				rule == null ? null : rule.maxTotalUses(), rule == null ? null : rule.maxUsesPerSession(), replayed);
	}

	/**
	 * Sesión contra la que se valida: la indicada por la puerta o, si no se indica, la que está en curso entre las que
	 * cubre el boleto; si ninguna lo está, la próxima, o la última que terminó (para dar un motivo útil).
	 */
	private SessionInfo chooseSession(EventDetails event, AccessRule rule, String sessionId, Instant now) {
		if (sessionId != null) {
			return event.sessions().stream().filter(s -> s.id().equals(sessionId)).findFirst()
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "La sesión no pertenece al evento"));
		}
		List<SessionInfo> covered = event.sessions().stream().filter(s -> rule.covers(s.id())).toList();
		Duration early = Duration.ofMinutes(rule.earlyEntryMinutes());
		return covered.stream().filter(s -> !now.isBefore(s.startsAt().minus(early)) && !now.isAfter(s.endsAt()))
				.min(Comparator.comparing(SessionInfo::startsAt))
				.or(() -> covered.stream().filter(s -> s.startsAt().isAfter(now)).min(Comparator.comparing(SessionInfo::startsAt)))
				.or(() -> covered.stream().max(Comparator.comparing(SessionInfo::endsAt))).orElse(null);
	}

	// ---------- consulta, reversión y búsqueda ----------

	public List<CheckInRepository.AuditRow> audit(AuthUser actor, String organizationId, String eventId, int limit) {
		access.requireManager(organizationId, actor.getId());
		eventOf(organizationId, eventId);
		return repository.audit(eventId, Math.min(Math.max(limit, 1), MAX_AUDIT));
	}

	/** El organizador anula un ingreso aceptado por error: el boleto puede volver a entrar. Queda en la auditoría. */
	public void revert(AuthUser actor, String organizationId, String eventId, String checkInId, String reason) {
		access.requireManager(organizationId, actor.getId());
		eventOf(organizationId, eventId);
		CheckInRow row = repository.findCheckIn(checkInId).filter(c -> c.eventId().equals(eventId))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingreso no encontrado"));
		if (!Result.ACCEPTED.name().equals(row.result())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se pueden revertir ingresos aceptados");
		}
		String clean = reason == null ? "" : reason.trim();
		if (clean.length() < 3 || clean.length() > 255) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el motivo (3 a 255 caracteres)");
		}
		Boolean reverted = tx.execute(status -> {
			// Se serializa con las lecturas del mismo boleto para que la reversión no se cruce con un ingreso en curso.
			if (row.ticketId() != null) {
				tickets.lockForCheckIn(row.ticketId());
			}
			return repository.revert(checkInId, eventId, actor.getId(), clean, now());
		});
		if (!Boolean.TRUE.equals(reverted)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Este ingreso ya fue revertido");
		}
	}

	public Summary summary(AuthUser actor, String organizationId, String eventId) {
		access.requireMember(organizationId, actor.getId());
		eventOf(organizationId, eventId);
		Map<String, Long> bySession = repository.entriesBySession(eventId);
		return new Summary(tickets.countValidTickets(eventId), repository.distinctTicketsEntered(eventId),
				bySession.values().stream().mapToLong(Long::longValue).sum(), repository.rejectedCount(eventId), bySession);
	}

	public List<Attendee> searchAttendees(AuthUser actor, String organizationId, String eventId, String query) {
		access.requireMember(organizationId, actor.getId());
		eventOf(organizationId, eventId);
		List<AttendeeMatch> matches = tickets.search(eventId, query, 20);
		Map<String, Long> counts = repository.entryCounts(matches.stream().map(AttendeeMatch::ticketId).toList());
		return matches.stream().map(m -> new Attendee(m, counts.getOrDefault(m.ticketId(), 0L))).toList();
	}

	private EventDetails eventOf(String organizationId, String eventId) {
		EventDetails event = catalog.event(eventId);
		if (!event.organizationId().equals(organizationId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento no encontrado");
		}
		return event;
	}
}
