package api.joinify.payment;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.identity.AuthUser;
import api.joinify.payment.PaymentRepository.PaymentRow;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
@Tag(name = "Pagos")
class PaymentController {

	static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

	record PayRequest(@NotBlank @Size(max = 200) String sourceToken) {
	}

	record PaymentResponse(@NotNull String id, @NotNull String orderId, @NotNull PaymentRepository.PaymentStatus status,
			long amountMinor, @NotNull String currency, String failureCode, String failureMessage) {
		static PaymentResponse of(PaymentRow p) {
			return new PaymentResponse(p.id(), p.orderId(), p.status(), p.amountMinor(), p.currency(), p.failureCode(),
					p.failureMessage());
		}
	}

	record PaymentConfigResponse(@NotNull String provider, String publicKey) {
	}

	private final PaymentService service;

	PaymentController(PaymentService service) {
		this.service = service;
	}

	/** Qué pasarela está activa y la llave pública que el navegador usa para tokenizar la tarjeta. */
	@GetMapping("/payments/config")
	PaymentConfigResponse paymentConfig() {
		var config = service.publicConfig();
		return new PaymentConfigResponse(config.provider(), config.publicKey());
	}

	/**
	 * Cobra una orden pendiente. Requiere Idempotency-Key: repetir la petición devuelve el mismo pago (200). Un cobro
	 * rechazado no es un error de la API: responde 201 con el pago en estado FAILED y el motivo.
	 */
	@PostMapping("/orders/{orderId}/payments")
	ResponseEntity<PaymentResponse> pay(@AuthenticationPrincipal AuthUser user, @PathVariable String orderId,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody PayRequest request) {
		if (!KEY_FORMAT.matcher(idempotencyKey).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Idempotency-Key debe tener entre 8 y 64 caracteres (letras, números, . _ : -)");
		}
		var view = service.pay(user, orderId, idempotencyKey, request.sourceToken());
		return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
				.header("Idempotent-Replayed", String.valueOf(view.replayed())).body(PaymentResponse.of(view.payment()));
	}

	@GetMapping("/orders/{orderId}/payments/{paymentId}")
	PaymentResponse getPayment(@AuthenticationPrincipal AuthUser user, @PathVariable String orderId,
			@PathVariable String paymentId) {
		return PaymentResponse.of(service.get(user, orderId, paymentId));
	}
}
