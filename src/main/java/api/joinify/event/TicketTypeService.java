package api.joinify.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.event.EventRepository.EventRow;
import api.joinify.event.TicketTypeDtos.TicketTypeResponse;
import api.joinify.identity.AuthUser;
import api.joinify.organization.OrganizationAccess;

@Service
class TicketTypeService {

	static final int DEFAULT_MAX_PER_ORDER = 10;
	private static final long MAX_PRICE_MINOR = 100_000_000L;

	/** Entrada del cliente ya traducida a tipos de dominio. */
	record Input(String name, String description, String template, long priceMinor, Integer quota,
			Integer maxPerOrder, Instant salesStart, Instant salesEnd, AccessRule.Scope scope, Set<String> sessionIds,
			Integer maxTotalUses, Integer maxUsesPerSession, Integer earlyEntryMinutes, Boolean active) {
	}

	private final TicketTypeRepository repository;
	private final EventRepository events;
	private final OrganizationAccess access;

	TicketTypeService(TicketTypeRepository repository, EventRepository events, OrganizationAccess access) {
		this.repository = repository;
		this.events = events;
		this.access = access;
	}

	List<TicketTypeResponse> list(AuthUser user, String orgId, String eventId) {
		access.requireMember(orgId, user.getId());
		event(orgId, eventId);
		return respond(repository.findByEvent(eventId, false));
	}

	/** Tipos activos de un evento ya público. */
	List<TicketTypeResponse> publicTypes(String eventId) {
		return respond(repository.findByEvent(eventId, true));
	}

	@Transactional
	TicketTypeResponse create(AuthUser user, String orgId, String eventId, Input in) {
		access.requireManager(orgId, user.getId());
		editable(event(orgId, eventId));
		TicketTypeRepository.Data data = toData(eventId, in);
		String id = repository.insert(eventId, access.currencyOf(orgId), data);
		return one(eventId, id);
	}

	@Transactional
	TicketTypeResponse update(AuthUser user, String orgId, String eventId, String ticketTypeId, Input in) {
		access.requireManager(orgId, user.getId());
		editable(event(orgId, eventId));
		find(eventId, ticketTypeId);
		repository.update(ticketTypeId, toData(eventId, in));
		return one(eventId, ticketTypeId);
	}

	@Transactional
	void delete(AuthUser user, String orgId, String eventId, String ticketTypeId) {
		access.requireManager(orgId, user.getId());
		editable(event(orgId, eventId));
		find(eventId, ticketTypeId);
		// Las etapas de venta añadirán aquí la regla "no eliminar con boletos vendidos" (se desactiva en su lugar).
		repository.delete(ticketTypeId);
	}

	/** Valida y traduce la entrada; acumula todos los problemas para informar de una vez. */
	private TicketTypeRepository.Data toData(String eventId, Input in) {
		int early = in.earlyEntryMinutes() == null ? AccessRule.DEFAULT_EARLY_ENTRY_MINUTES : in.earlyEntryMinutes();
		AccessRule rule = new AccessRule(in.scope(), in.sessionIds(), in.maxTotalUses(), in.maxUsesPerSession(), early);
		int maxPerOrder = in.maxPerOrder() == null ? DEFAULT_MAX_PER_ORDER : in.maxPerOrder();

		Set<String> eventSessionIds = events.findSessions(eventId).stream().map(EventRepository.SessionRow::id)
				.collect(Collectors.toSet());
		List<String> problems = new ArrayList<>(AccessPolicy.validate(rule, eventSessionIds));
		if (in.priceMinor() < 0 || in.priceMinor() > MAX_PRICE_MINOR) {
			problems.add("Precio fuera de rango");
		}
		if (in.quota() != null && in.quota() < 1) {
			problems.add("El cupo debe ser al menos 1");
		}
		if (maxPerOrder < 1) {
			problems.add("El máximo por orden debe ser al menos 1");
		}
		if (in.quota() != null && maxPerOrder > in.quota()) {
			problems.add("El máximo por orden no puede superar el cupo");
		}
		if (in.salesStart() != null && in.salesEnd() != null && !in.salesEnd().isAfter(in.salesStart())) {
			problems.add("El periodo de venta debe terminar después de empezar");
		}
		if (!problems.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", problems));
		}
		return new TicketTypeRepository.Data(in.name().trim(), blankToNull(in.description()), in.template(),
				in.priceMinor(), in.quota(), maxPerOrder, utc(in.salesStart()), utc(in.salesEnd()), rule,
				in.active() == null || in.active());
	}

	private TicketTypeResponse one(String eventId, String id) {
		return respond(List.of(find(eventId, id))).getFirst();
	}

	private List<TicketTypeResponse> respond(List<TicketTypeRepository.Row> rows) {
		Instant now = Instant.now();
		return rows.stream().map(r -> TicketTypeResponse.of(r, repository.sessionIds(r.id()), now)).toList();
	}

	private TicketTypeRepository.Row find(String eventId, String id) {
		return repository.find(eventId, id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo de entrada no encontrado"));
	}

	private EventRow event(String orgId, String eventId) {
		return events.findEvent(orgId, eventId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento no encontrado"));
	}

	private static void editable(EventRow event) {
		if (event.status() == EventStatus.CANCELLED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Un evento cancelado no se puede modificar");
		}
	}

	private static LocalDateTime utc(Instant instant) {
		return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
