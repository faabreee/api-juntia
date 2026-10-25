package api.joinify.event;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import api.joinify.event.TicketTypeDtos.Template;
import api.joinify.event.TicketTypeDtos.TicketTypeResponse;
import api.joinify.identity.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/organizations/{organizationId}/events/{eventId}/ticket-types")
@Tag(name = "Tipos de entrada")
class TicketTypeController {

	/** El precio va en la unidad menor de la moneda de la organización (0 = gratis). La regla de acceso sigue el ADR 0004. */
	record TicketTypeRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description,
			@NotNull Template template, @Min(0) @Max(100_000_000) long priceMinor, @Min(1) Integer quota,
			@Min(1) @Max(50) Integer maxPerOrder, Instant salesStart, Instant salesEnd,
			@NotNull AccessRule.Scope scope, Set<String> sessionIds, @Min(1) Integer maxTotalUses,
			@Min(1) Integer maxUsesPerSession, @Min(0) @Max(1440) Integer earlyEntryMinutes, Boolean active) {

		TicketTypeService.Input toInput() {
			return new TicketTypeService.Input(name, description, template.name(), priceMinor, quota, maxPerOrder,
					salesStart, salesEnd, scope, sessionIds, maxTotalUses, maxUsesPerSession, earlyEntryMinutes, active);
		}
	}

	private final TicketTypeService service;

	TicketTypeController(TicketTypeService service) {
		this.service = service;
	}

	@GetMapping
	List<TicketTypeResponse> listTicketTypes(@AuthenticationPrincipal AuthUser user,
			@PathVariable String organizationId, @PathVariable String eventId) {
		return service.list(user, organizationId, eventId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TicketTypeResponse createTicketType(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @Valid @RequestBody TicketTypeRequest r) {
		return service.create(user, organizationId, eventId, r.toInput());
	}

	@PutMapping("/{ticketTypeId}")
	TicketTypeResponse updateTicketType(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @PathVariable String ticketTypeId, @Valid @RequestBody TicketTypeRequest r) {
		return service.update(user, organizationId, eventId, ticketTypeId, r.toInput());
	}

	@DeleteMapping("/{ticketTypeId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteTicketType(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @PathVariable String ticketTypeId) {
		service.delete(user, organizationId, eventId, ticketTypeId);
	}
}
