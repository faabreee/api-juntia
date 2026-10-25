package api.joinify.payment;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * Avisos de la pasarela. Es público y sin CSRF: su autenticidad la comprueba el adaptador de cada proveedor
 * (firma o secreto en la URL). Siempre responde 200 a un aviso auténtico, aunque sea repetido, para que la
 * pasarela deje de reintentarlo.
 */
@Hidden
@RestController
@RequestMapping("/api/webhooks")
class WebhookController {

	record WebhookAck(String status) {
	}

	private final PaymentService service;

	WebhookController(PaymentService service) {
		this.service = service;
	}

	@PostMapping({ "/{provider}", "/{provider}/{secret}" })
	WebhookAck receive(@PathVariable String provider, @PathVariable(required = false) String secret,
			@RequestHeader HttpHeaders headers, @RequestBody String rawBody) {
		Map<String, String> lower = new HashMap<>();
		headers.forEach((name, values) -> lower.put(name.toLowerCase(java.util.Locale.ROOT), values.isEmpty() ? "" : values.getFirst()));
		var outcome = service.handleWebhook(provider, lower, rawBody, secret);
		return new WebhookAck(outcome == PaymentService.WebhookOutcome.DUPLICATE ? "duplicate" : "ok");
	}
}
