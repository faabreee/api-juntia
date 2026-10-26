package api.joinify.notification;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import api.joinify.identity.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
@Tag(name = "Notificaciones")
class NotificationController {

	record NotificationResponse(@NotNull String id, @NotNull String type, @NotNull String recipientEmail, @NotNull String status,
			int attempts, String subject, String lastError, @NotNull Instant createdAt, Instant sentAt, @NotNull Instant nextAttemptAt) {
		static NotificationResponse of(NotificationService.LogEntry e) {
			return new NotificationResponse(e.id(), e.type(), e.recipientEmail(), e.status(), e.attempts(), e.subject(), e.lastError(),
					e.createdAt(), e.sentAt(), e.nextAttemptAt());
		}
	}

	private final NotificationService service;

	NotificationController(NotificationService service) {
		this.service = service;
	}

	/** Registro de los correos de un evento (enviados, pendientes, fallidos). Solo OWNER y ADMIN. */
	@GetMapping("/events/{eventId}/notifications")
	List<NotificationResponse> listNotifications(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
		return service.log(user.getId(), organizationId, eventId, status, limit).stream().map(NotificationResponse::of).toList();
	}

	/** Vuelve a la cola un envío que agotó sus intentos. */
	@PostMapping("/notifications/{notificationId}/retry")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void retryNotification(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId, @PathVariable String notificationId) {
		service.retry(user.getId(), organizationId, notificationId);
	}
}
