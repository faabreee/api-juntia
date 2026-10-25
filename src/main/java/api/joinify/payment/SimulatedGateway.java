package api.joinify.payment;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pasarela simulada para desarrollo y pruebas: nunca mueve dinero. El token decide el resultado:
 * <ul>
 * <li>{@code tok_success}: cobro aprobado</li>
 * <li>{@code tok_declined} / {@code tok_insufficient_funds}: cobro rechazado</li>
 * <li>{@code tok_pending}: queda pendiente hasta que {@link #settle} lo resuelva (como un pago en efectivo)</li>
 * <li>{@code tok_success_norefund}: aprobado, pero sus reembolsos fallan</li>
 * <li>{@code tok_error}: simula que la pasarela no responde</li>
 * </ul>
 * Los webhooks llevan la firma HMAC-SHA256 del cuerpo en la cabecera {@code X-Signature}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedGateway implements PaymentGateway {

	public static final String NAME = "simulated";

	private final Map<String, ChargeResult> charges = new ConcurrentHashMap<>();
	private final Map<String, Long> refundedByCharge = new ConcurrentHashMap<>();
	private final Map<String, Long> amountByCharge = new ConcurrentHashMap<>();
	private final byte[] webhookSecret;
	private final ObjectMapper mapper = new ObjectMapper();

	SimulatedGateway(@Value("${app.payments.simulated.webhook-secret}") String webhookSecret) {
		this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public ChargeResult charge(ChargeRequest request) {
		String token = request.sourceToken() == null ? "" : request.sourceToken();
		if (token.equals("tok_error")) {
			throw new GatewayException("La pasarela simulada no responde");
		}
		String id = "sim_" + UUID.randomUUID();
		ChargeResult result = switch (token) {
			case "tok_success", "tok_success_norefund" -> ChargeResult.succeeded(token.endsWith("norefund") ? id + "_norefund" : id);
			case "tok_declined" -> ChargeResult.failed(id, "card_declined", "Tarjeta rechazada por el banco");
			case "tok_insufficient_funds" -> ChargeResult.failed(id, "insufficient_funds", "Fondos insuficientes");
			case "tok_pending" -> ChargeResult.pending(id);
			default -> ChargeResult.failed(id, "invalid_token", "Token de pago inválido");
		};
		charges.put(result.providerPaymentId(), result);
		amountByCharge.put(result.providerPaymentId(), request.amountMinor());
		return result;
	}

	/** Resuelve un cobro pendiente, como haría el banco o el comercio de efectivo más tarde. */
	public void settle(String providerPaymentId, Status status) {
		ChargeResult current = charges.get(providerPaymentId);
		if (current == null) {
			throw new IllegalArgumentException("Cobro desconocido: " + providerPaymentId);
		}
		charges.put(providerPaymentId, switch (status) {
			case SUCCEEDED -> ChargeResult.succeeded(providerPaymentId);
			case FAILED -> ChargeResult.failed(providerPaymentId, "expired", "El pago no se completó");
			case PENDING -> ChargeResult.pending(providerPaymentId);
		});
	}

	@Override
	public ChargeResult fetchCharge(String providerPaymentId) {
		ChargeResult result = charges.get(providerPaymentId);
		if (result == null) {
			throw new GatewayException("Cobro desconocido en la pasarela simulada");
		}
		return result;
	}

	@Override
	public RefundResult refund(RefundRequest request) {
		if (request.providerPaymentId().endsWith("_norefund")) {
			return new RefundResult(false, null, "La pasarela rechazó el reembolso");
		}
		Long amount = amountByCharge.get(request.providerPaymentId());
		if (amount == null) {
			throw new GatewayException("Cobro desconocido en la pasarela simulada");
		}
		long already = refundedByCharge.merge(request.providerPaymentId(), 0L, Long::sum);
		if (already + request.amountMinor() > amount) {
			return new RefundResult(false, null, "El reembolso excede el monto cobrado");
		}
		refundedByCharge.put(request.providerPaymentId(), already + request.amountMinor());
		return new RefundResult(true, "simref_" + UUID.randomUUID(), null);
	}

	/** Firma de un cuerpo de webhook (la usan las pruebas y quien simule avisos). */
	public String sign(String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public WebhookEvent parseWebhook(Map<String, String> headers, String rawBody, String pathSecret) {
		String signature = headers.get("x-signature");
		if (signature == null || !MessageDigest.isEqual(sign(rawBody).getBytes(StandardCharsets.UTF_8),
				signature.getBytes(StandardCharsets.UTF_8))) {
			throw new InvalidWebhookException("Firma inválida");
		}
		try {
			JsonNode json = mapper.readTree(rawBody);
			String eventId = json.path("id").asText(null);
			String type = json.path("type").asText(null);
			if (eventId == null || type == null) {
				throw new InvalidWebhookException("Webhook incompleto");
			}
			return new WebhookEvent(eventId, type, json.path("chargeId").asText(null));
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new InvalidWebhookException("Webhook mal formado");
		}
	}

	@Override
	public PublicConfig publicConfig() {
		return new PublicConfig(NAME, null);
	}
}
