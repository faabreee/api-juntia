package api.joinify.event;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.event.EventRepository.EventRow;
import api.joinify.event.EventRepository.SessionRow;
import api.joinify.event.EventRepository.SummaryRow;
import api.joinify.event.EventRepository.VenueRow;
import api.joinify.identity.AuthUser;
import api.joinify.organization.OrganizationAccess;

@Service
class EventService {

	private static final int MAX_COVER_BYTES = 5 * 1024 * 1024;

	record Page<T>(List<T> items, int page, int size, long total) {
	}

	record SessionInput(String name, Instant startsAt, Instant endsAt, String venueId) {
	}

	record CoverFile(org.springframework.core.io.Resource resource, String contentType) {
	}

	private final EventRepository repository;
	private final OrganizationAccess access;
	private final CoverStorage covers;

	EventService(EventRepository repository, OrganizationAccess access, CoverStorage covers) {
		this.repository = repository;
		this.access = access;
		this.covers = covers;
	}

	// ---------- venues ----------

	@Transactional
	VenueRow createVenue(AuthUser user, String orgId, String name, String address, String city, String country,
			Integer capacity) {
		access.requireManager(orgId, user.getId());
		String id = repository.insertVenue(orgId, name.trim(), blankToNull(address), blankToNull(city),
				validCountry(country), capacity);
		return repository.findVenue(orgId, id).orElseThrow();
	}

	List<VenueRow> venues(AuthUser user, String orgId) {
		access.requireMember(orgId, user.getId());
		return repository.findVenues(orgId);
	}

	@Transactional
	VenueRow updateVenue(AuthUser user, String orgId, String venueId, String name, String address, String city,
			String country, Integer capacity) {
		access.requireManager(orgId, user.getId());
		venue(orgId, venueId);
		repository.updateVenue(venueId, name.trim(), blankToNull(address), blankToNull(city), validCountry(country), capacity);
		return venue(orgId, venueId);
	}

	@Transactional
	void deleteVenue(AuthUser user, String orgId, String venueId) {
		access.requireManager(orgId, user.getId());
		venue(orgId, venueId);
		if (repository.venueInUse(venueId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El lugar se usa en sesiones de algún evento");
		}
		repository.deleteVenue(venueId);
	}

	// ---------- events ----------

	@Transactional
	EventRow createEvent(AuthUser user, String orgId, String title, String description, String timezone) {
		access.requireManager(orgId, user.getId());
		String id = repository.insertEvent(orgId, uniqueSlug(title), title.trim(), blankToNull(description),
				validTimezone(timezone));
		return event(orgId, id);
	}

	Page<SummaryRow> list(AuthUser user, String orgId, EventStatus status, String query, Instant from, Instant to,
			int page, int size) {
		access.requireMember(orgId, user.getId());
		int safeSize = Math.min(Math.max(size, 1), 100);
		int safePage = Math.max(page, 0);
		EventRepository.Filter filter = new EventRepository.Filter(status, query, utc(from), utc(to));
		return new Page<>(repository.search(orgId, filter, safeSize, safePage * safeSize), safePage, safeSize,
				repository.count(orgId, filter));
	}

	EventRow get(AuthUser user, String orgId, String eventId) {
		access.requireMember(orgId, user.getId());
		return event(orgId, eventId);
	}

	List<SessionRow> sessions(String eventId) {
		return repository.findSessions(eventId);
	}

	@Transactional
	EventRow update(AuthUser user, String orgId, String eventId, String title, String description, String timezone) {
		access.requireManager(orgId, user.getId());
		editable(event(orgId, eventId));
		repository.updateEvent(eventId, title.trim(), blankToNull(description), validTimezone(timezone));
		return event(orgId, eventId);
	}

	@Transactional
	void delete(AuthUser user, String orgId, String eventId) {
		access.requireManager(orgId, user.getId());
		EventRow event = event(orgId, eventId);
		if (event.status() != EventStatus.DRAFT) {
			throw conflict("Solo se pueden eliminar eventos en borrador; cancela el evento en su lugar");
		}
		repository.deleteEvent(eventId);
		if (event.coverKey() != null) {
			covers.delete(event.coverKey());
		}
	}

	// ---------- state changes ----------

	@Transactional
	EventRow publish(AuthUser user, String orgId, String eventId) {
		access.requireManager(orgId, user.getId());
		EventRow event = event(orgId, eventId);
		if (event.status() != EventStatus.DRAFT) {
			throw conflict("Solo se puede publicar un evento en borrador");
		}
		List<String> problems = new ArrayList<>();
		List<SessionRow> sessions = repository.findSessions(eventId);
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		if (sessions.isEmpty()) {
			problems.add("Agrega al menos una sesión");
		} else if (sessions.stream().noneMatch(s -> s.endsAt().isAfter(now))) {
			problems.add("Al menos una sesión debe terminar en el futuro");
		}
		if (!problems.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"No se puede publicar: " + String.join("; ", problems));
		}
		repository.updateStatus(eventId, EventStatus.PUBLISHED, null);
		return event(orgId, eventId);
	}

	@Transactional
	EventRow unpublish(AuthUser user, String orgId, String eventId) {
		access.requireManager(orgId, user.getId());
		if (event(orgId, eventId).status() != EventStatus.PUBLISHED) {
			throw conflict("Solo se puede despublicar un evento publicado");
		}
		// Las etapas de ventas añadirán aquí la regla "no despublicar con entradas vendidas".
		repository.updateStatus(eventId, EventStatus.DRAFT, null);
		return event(orgId, eventId);
	}

	@Transactional
	EventRow cancel(AuthUser user, String orgId, String eventId, String reason) {
		access.requireManager(orgId, user.getId());
		if (event(orgId, eventId).status() == EventStatus.CANCELLED) {
			throw conflict("El evento ya está cancelado");
		}
		repository.updateStatus(eventId, EventStatus.CANCELLED, blankToNull(reason));
		return event(orgId, eventId);
	}

	// ---------- sessions ----------

	@Transactional
	SessionRow addSession(AuthUser user, String orgId, String eventId, SessionInput in) {
		access.requireManager(orgId, user.getId());
		editable(event(orgId, eventId));
		validateSession(orgId, in);
		String id = repository.insertSession(eventId, in.name().trim(), utc(in.startsAt()), utc(in.endsAt()), in.venueId());
		return repository.findSession(eventId, id).orElseThrow();
	}

	@Transactional
	SessionRow updateSession(AuthUser user, String orgId, String eventId, String sessionId, SessionInput in) {
		access.requireManager(orgId, user.getId());
		editable(event(orgId, eventId));
		session(eventId, sessionId);
		validateSession(orgId, in);
		repository.updateSession(sessionId, in.name().trim(), utc(in.startsAt()), utc(in.endsAt()), in.venueId());
		return session(eventId, sessionId);
	}

	@Transactional
	void deleteSession(AuthUser user, String orgId, String eventId, String sessionId) {
		access.requireManager(orgId, user.getId());
		EventRow event = event(orgId, eventId);
		editable(event);
		session(eventId, sessionId);
		if (event.status() == EventStatus.PUBLISHED && repository.findSessions(eventId).size() == 1) {
			throw conflict("Un evento publicado necesita al menos una sesión; despublícalo primero");
		}
		repository.deleteSession(sessionId);
	}

	// ---------- cover ----------

	@Transactional
	EventRow setCover(AuthUser user, String orgId, String eventId, MultipartFile file) {
		access.requireManager(orgId, user.getId());
		EventRow event = event(orgId, eventId);
		editable(event);
		if (file.isEmpty() || file.getSize() > MAX_COVER_BYTES) {
			throw badRequest("La portada debe pesar entre 1 byte y 5 MB");
		}
		try {
			byte[] head = file.getInputStream().readNBytes(12);
			String[] type = sniffImage(head);
			if (type == null) {
				throw badRequest("La portada debe ser una imagen JPEG, PNG o WebP");
			}
			String key;
			try (var in = file.getInputStream()) {
				key = covers.store(in, type[1]);
			}
			repository.updateCover(eventId, key, type[0]);
			if (event.coverKey() != null) {
				covers.delete(event.coverKey());
			}
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar la portada");
		}
		return event(orgId, eventId);
	}

	@Transactional
	void removeCover(AuthUser user, String orgId, String eventId) {
		access.requireManager(orgId, user.getId());
		EventRow event = event(orgId, eventId);
		editable(event);
		if (event.coverKey() != null) {
			repository.updateCover(eventId, null, null);
			covers.delete(event.coverKey());
		}
	}

	CoverFile cover(AuthUser user, String orgId, String eventId) {
		access.requireMember(orgId, user.getId());
		return coverOf(event(orgId, eventId));
	}

	CoverFile publicCover(String slug) {
		return coverOf(publicEvent(slug));
	}

	// ---------- public ----------

	EventRow publicEvent(String slug) {
		return repository.findPublicBySlug(slug)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento no encontrado"));
	}

	String organizationName(String orgId) {
		return access.nameOf(orgId);
	}

	// ---------- helpers ----------

	private CoverFile coverOf(EventRow event) {
		if (event.coverKey() == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El evento no tiene portada");
		}
		return new CoverFile(covers.load(event.coverKey()), event.coverContentType());
	}

	private EventRow event(String orgId, String eventId) {
		return repository.findEvent(orgId, eventId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento no encontrado"));
	}

	private VenueRow venue(String orgId, String venueId) {
		return repository.findVenue(orgId, venueId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lugar no encontrado"));
	}

	private SessionRow session(String eventId, String sessionId) {
		return repository.findSession(eventId, sessionId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sesión no encontrada"));
	}

	private void editable(EventRow event) {
		if (event.status() == EventStatus.CANCELLED) {
			throw conflict("Un evento cancelado no se puede modificar");
		}
	}

	private void validateSession(String orgId, SessionInput in) {
		if (!in.endsAt().isAfter(in.startsAt())) {
			throw badRequest("La sesión debe terminar después de empezar");
		}
		if (in.venueId() != null) {
			venue(orgId, in.venueId()); // el lugar debe ser de la misma organización
		}
	}

	private String uniqueSlug(String title) {
		String base = slugify(title);
		String slug = base;
		while (repository.slugExists(slug)) {
			slug = base + "-" + Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36 * 36 * 36), 36);
		}
		return slug;
	}

	static String slugify(String text) {
		String slug = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (slug.length() > 60) {
			slug = slug.substring(0, 60).replaceAll("-+$", "");
		}
		return slug.isEmpty() ? "evento" : slug;
	}

	/** Reconoce JPEG, PNG y WebP por su firma, sin fiarse del tipo declarado por el cliente. */
	private static String[] sniffImage(byte[] h) {
		if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
			return new String[] { "image/jpeg", "jpg" };
		}
		if (h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
			return new String[] { "image/png", "png" };
		}
		if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E'
				&& h[10] == 'B' && h[11] == 'P') {
			return new String[] { "image/webp", "webp" };
		}
		return null;
	}

	private static String validCountry(String country) {
		String code = country.trim().toUpperCase(Locale.ROOT);
		if (!Set.of(Locale.getISOCountries()).contains(code)) {
			throw badRequest("País inválido");
		}
		return code;
	}

	private static String validTimezone(String timezone) {
		String tz = timezone.trim();
		if (!ZoneId.getAvailableZoneIds().contains(tz)) {
			throw badRequest("Zona horaria inválida");
		}
		return tz;
	}

	private static LocalDateTime utc(Instant instant) {
		return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static ResponseStatusException badRequest(String m) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
	}

	private static ResponseStatusException conflict(String m) {
		return new ResponseStatusException(HttpStatus.CONFLICT, m);
	}
}
