package api.joinify.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Adaptador de Culqi (ADR 0007).
 *
 * IMPORTANTE: escrito a partir de la documentación pública de Culqi y SIN haberse probado contra su sandbox
 * (hacen falta llaves de prueba). Lo que la documentación confirma: el token va en {@code source_id}; un rechazo
 * llega como objeto {@code error} con {@code code}, {@code decline_code} y {@code merchant_message}; las
 * devoluciones usan {@code charge_id} y pueden ser totales o parciales. Lo que NO documenta y debe validarse en
 * el sandbox antes de vender: los nombres exactos de los campos de petición ({@code amount} en céntimos,
 * {@code currency_code}, {@code email}, {@code reason}), el código HTTP de un rechazo, la firma de los webhooks y
 * cualquier mecanismo de idempotencia. Por eso el webhook se autentica con un segmento secreto en la URL y nunca
 * se confía en su contenido: el estado se consulta siempre con {@code GET /charges/{id}}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "culqi")
public class CulqiGateway implements PaymentGateway {

	public static final String NAME = "culqi";

	private final RestClient http;
	private final String publicKey;
	private final byte[] webhookSecret;
	private final ObjectMapper mapper = new ObjectMapper();

	CulqiGateway(RestClient.Builder builder, @Value("${app.payments.culqi.base-url}") String baseUrl,
			@Value("${app.payments.culqi.public-key}") String publicKey,
			@Value("${app.payments.culqi.secret-key}") String secretKey,
			@Value("${app.payments.culqi.webhook-secret:}") String webhookSecret) {
		if (secretKey.isBlank() || publicKey.isBlank() || webhookSecret.isBlank()) {
			throw new IllegalStateException("Culqi requiere CULQI_PUBLIC_KEY, CULQI_SECRET_KEY y CULQI_WEBHOOK_SECRET");
		}
		this.http = builder.baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + secretKey)
				.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).build();
		this.publicKey = publicKey;
		this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public ChargeResult charge(ChargeRequest request) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("amount", request.amountMinor());
		body.put("currency_code", request.currency());
		body.put("email", request.email());
		body.put("source_id", request.sourceToken());
		body.put("description", request.description());
		body.put("metadata", Map.of("payment_id", request.paymentId(), "order_id", request.orderId()));
		try {
			return http.post().uri("/charges").body(body).exchange((req, res) -> {
				JsonNode json = read(res.getBody());
				if (res.getStatusCode().is2xxSuccessful()) {
					String id = json.path("id").asText(null);
					if (id == null) {
						throw new GatewayException("Respuesta de Culqi sin id de cargo");
					}
					return fromChargeObject(json);
				}
				if (res.getStatusCode().is4xxClientError() && "error".equals(json.path("object").asText())) {
					String code = json.hasNonNull("decline_code") ? json.path("decline_code").asText() : json.path("code").asText("declined");
					return ChargeResult.failed(json.path("charge_id").asText(null), code,
							json.path("user_message").asText(json.path("merchant_message").asText("Pago rechazado")));
				}
				throw new GatewayException("Culqi respondió " + res.getStatusCode());
			});
		} catch (RestClientException e) {
			throw new GatewayException("No se pudo contactar a Culqi", e);
		}
	}

	@Override
	public ChargeResult fetchCharge(String providerPaymentId) {
		try {
			return http.get().uri("/charges/{id}", providerPaymentId).exchange((req, res) -> {
				if (!res.getStatusCode().is2xxSuccessful()) {
					throw new GatewayException("Culqi respondió " + res.getStatusCode());
				}
				return fromChargeObject(read(res.getBody()));
			});
		} catch (RestClientException e) {
			throw new GatewayException("No se pudo contactar a Culqi", e);
		}
	}

	/** Un cargo creado y capturado es un cobro aprobado; sin captura queda pendiente. */
	private static ChargeResult fromChargeObject(JsonNode json) {
		String id = json.path("id").asText();
		boolean captured = !json.has("capture") || json.path("capture").asBoolean(true);
		return captured ? ChargeResult.succeeded(id) : ChargeResult.pending(id);
	}

	@Override
	public RefundResult refund(RefundRequest request) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("amount", request.amountMinor());
		body.put("charge_id", request.providerPaymentId());
		body.put("reason", request.reason());
		try {
			return http.post().uri("/refunds").body(body).exchange((req, res) -> {
				JsonNode json = read(res.getBody());
				if (res.getStatusCode().is2xxSuccessful()) {
					return new RefundResult(true, json.path("id").asText(null), null);
				}
				HttpStatusCode status = res.getStatusCode();
				if (status.is4xxClientError()) {
					return new RefundResult(false, null, json.path("merchant_message").asText("Culqi rechazó la devolución"));
				}
				throw new GatewayException("Culqi respondió " + status);
			});
		} catch (RestClientException e) {
			throw new GatewayException("No se pudo contactar a Culqi", e);
		}
	}

	@Override
	public WebhookEvent parseWebhook(Map<String, String> headers, String rawBody, String pathSecret) {
		if (pathSecret == null || !MessageDigest.isEqual(webhookSecret, pathSecret.getBytes(StandardCharsets.UTF_8))) {
			throw new InvalidWebhookException("Secreto de webhook inválido");
		}
		JsonNode json = read(new java.io.ByteArrayInputStream(rawBody.getBytes(StandardCharsets.UTF_8)));
		String eventId = json.path("id").asText(null);
		String type = json.path("type").asText(null);
		if (eventId == null || type == null) {
			throw new InvalidWebhookException("Webhook incompleto");
		}
		// Según el evento, "data" puede venir como objeto o como texto JSON.
		JsonNode data = json.path("data");
		if (data.isTextual()) {
			data = read(new java.io.ByteArrayInputStream(data.asText().getBytes(StandardCharsets.UTF_8)));
		}
		String chargeId = type.startsWith("refund.") ? data.path("charge_id").asText(null) : data.path("id").asText(null);
		return new WebhookEvent(eventId, type, chargeId);
	}

	@Override
	public PublicConfig publicConfig() {
		return new PublicConfig(NAME, publicKey);
	}

	private JsonNode read(java.io.InputStream in) {
		try {
			return mapper.readTree(in);
		} catch (java.io.IOException e) {
			throw new GatewayException("Respuesta ilegible de Culqi", e);
		}
	}
}
