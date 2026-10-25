package api.joinify.checkin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import api.joinify.checkin.CheckInRepository.DeviceRow;
import api.joinify.checkin.CheckInService.Outcome;
import api.joinify.identity.AuthUser;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
@Tag(name = "Control de acceso")
class CheckInController {

	record RegisterDeviceRequest(@NotBlank @Size(min = 2, max = 60) String name) {
	}

	record DeviceResponse(@NotNull String id, @NotNull String name, @NotNull String registeredByName, @NotNull Instant createdAt,
			Instant lastSeenAt, boolean revoked) {
		static DeviceResponse of(DeviceRow d) {
			return new DeviceResponse(d.id(), d.name(), d.registeredByName(), d.createdAt().toInstant(ZoneOffset.UTC),
					d.lastSeenAt() == null ? null : d.lastSeenAt().toInstant(ZoneOffset.UTC), d.revoked());
		}
	}

	/** Una lectura: exactamente uno de token (QR), code (código corto) o ticketId (búsqueda manual). */
	record ScanRequest(@NotBlank @Size(max = 64) String scanId, @NotBlank String deviceId, String sessionId, @Size(max = 400) String token,
			@Size(max = 40) String code, String ticketId) {
	}

	@Schema(name = "ScannedTicket")
	record ScannedTicket(@NotNull String id, @NotNull String code, @NotNull String ticketTypeName, String attendeeName, boolean nominative) {
	}

	@Schema(name = "ScannedSession")
	record ScannedSession(@NotNull String id, @NotNull String name) {
	}

	@Schema(name = "PreviousEntry")
	record PreviousEntryResponse(@NotNull Instant at, @NotNull String deviceName) {
	}

	/** Resultado de una lectura: ACCEPTED (verde) o REJECTED (rojo) con su motivo. */
	record ScanResponse(@NotNull String checkInId, @NotNull CheckInService.Result result, String reason, ScannedTicket ticket,
			ScannedSession session, int entriesTotal, int entriesInSession, PreviousEntryResponse previousEntry,
			Integer maxTotalUses, Integer maxUsesPerSession, boolean replayed) {

		static ScanResponse of(Outcome o) {
			return new ScanResponse(o.checkInId(), o.result(), o.reason(),
					o.ticket() == null ? null : new ScannedTicket(o.ticket().id(), api.joinify.ticket.ShortCode.format(o.ticket().code()),
							o.ticket().ticketTypeName(), o.ticket().attendeeName(), o.ticket().nominative()),
					o.session() == null ? null : new ScannedSession(o.session().id(), o.session().name()), o.entriesTotal(),
					o.entriesInSession(),
					o.previousEntry() == null ? null : new PreviousEntryResponse(o.previousEntry().at(), o.previousEntry().deviceName()),
					o.maxTotalUses(), o.maxUsesPerSession(), o.replayed());
		}
	}

	record AuditEntry(@NotNull String id, @NotNull String result, String reason, @NotNull String method, @NotNull Instant at,
			String ticketId, String ticketCode, String ticketTypeName, String attendeeName, String sessionId, @NotNull String deviceName,
			@NotNull String validatorName, Instant revertedAt, String revertedByName, String revertReason) {
	}

	record RevertRequest(@NotBlank @Size(min = 3, max = 255) String reason) {
	}

	record SummaryResponse(long validTickets, long ticketsEntered, long entries, long rejected, @NotNull Map<String, Long> entriesBySession) {
	}

	record AttendeeResponse(@NotNull String ticketId, @NotNull String code, boolean valid, @NotNull String ticketTypeName,
			String attendeeName, @NotNull String buyerName, @NotNull String buyerEmailMasked, long entries) {
	}

	private final CheckInService service;

	CheckInController(CheckInService service) {
		this.service = service;
	}

	// ---- dispositivos ----

	/** Registra la puerta que va a validar. Cualquier miembro de la organización puede registrar la suya. */
	@PostMapping("/devices")
	@ResponseStatus(HttpStatus.CREATED)
	DeviceResponse registerDevice(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@Valid @RequestBody RegisterDeviceRequest request) {
		return DeviceResponse.of(service.registerDevice(user, organizationId, request.name()));
	}

	@GetMapping("/devices")
	List<DeviceResponse> listDevices(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId) {
		return service.devices(user, organizationId).stream().map(DeviceResponse::of).toList();
	}

	@DeleteMapping("/devices/{deviceId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revokeDevice(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId, @PathVariable String deviceId) {
		service.revokeDevice(user, organizationId, deviceId);
	}

	// ---- ingreso ----

	/**
	 * Valida un boleto en puerta. Un boleto rechazado NO es un error de la API: responde 200 con result=REJECTED y el
	 * motivo. Es idempotente por scanId (200 también al repetir) y atómico entre puertas.
	 */
	@PostMapping("/events/{eventId}/checkins")
	ScanResponse scanTicket(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId, @PathVariable String eventId,
			@Valid @RequestBody ScanRequest request) {
		return ScanResponse.of(service.scan(user, organizationId, eventId, new CheckInService.Scan(request.scanId(), request.deviceId(),
				request.sessionId(), request.token(), request.code(), request.ticketId())));
	}

	@GetMapping("/events/{eventId}/checkins/summary")
	SummaryResponse getCheckInSummary(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId, @PathVariable String eventId) {
		var s = service.summary(user, organizationId, eventId);
		return new SummaryResponse(s.validTickets(), s.ticketsEntered(), s.entries(), s.rejected(), s.entriesBySession());
	}

	/** Búsqueda manual por código, nombre del asistente, o nombre o correo del comprador (mínimo 3 caracteres). */
	@GetMapping("/events/{eventId}/attendees")
	List<AttendeeResponse> searchAttendees(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @RequestParam String q) {
		return service.searchAttendees(user, organizationId, eventId, q).stream()
				.map(a -> new AttendeeResponse(a.match().ticketId(), a.match().code(), a.match().valid(), a.match().ticketTypeName(),
						a.match().attendeeName(), a.match().buyerName(), a.match().buyerEmailMasked(), a.entries()))
				.toList();
	}

	// ---- auditoría ----

	/** Registro de todos los intentos de ingreso (aceptados y rechazados). Solo OWNER y ADMIN. */
	@GetMapping("/events/{eventId}/checkins")
	List<AuditEntry> listCheckIns(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @RequestParam(defaultValue = "100") int limit) {
		return service.audit(user, organizationId, eventId, limit).stream()
				.map(r -> new AuditEntry(r.id(), r.result(), r.reason(), r.method(), r.createdAt().toInstant(ZoneOffset.UTC), r.ticketId(),
						r.ticketCode() == null ? null : api.joinify.ticket.ShortCode.format(r.ticketCode()), r.ticketTypeName(),
						r.attendeeName(), r.sessionId(), r.deviceName(), r.validatorName(),
						r.revertedAt() == null ? null : r.revertedAt().toInstant(ZoneOffset.UTC), r.revertedByName(), r.revertReason()))
				.toList();
	}

	/** Revierte un ingreso aceptado por error: el boleto puede volver a entrar. El motivo queda en la auditoría. */
	@PostMapping("/events/{eventId}/checkins/{checkInId}/revert")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revertCheckIn(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId, @PathVariable String eventId,
			@PathVariable String checkInId, @Valid @RequestBody RevertRequest request) {
		service.revert(user, organizationId, eventId, checkInId, request.reason());
	}
}
