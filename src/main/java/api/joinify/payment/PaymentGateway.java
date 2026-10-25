package api.joinify.payment;

import java.util.Map;

/**
 * Puerto hacia la pasarela de pago (ADR 0007). El resto del módulo no conoce a ningún proveedor: cambiar de
 * pasarela es escribir otro adaptador.
 */
public interface PaymentGateway {

	enum Status {
		SUCCEEDED, FAILED, PENDING
	}

	/** Cobro: {@code sourceToken} es el token que el navegador obtuvo directamente de la pasarela (nunca datos de tarjeta). */
	record ChargeRequest(String paymentId, String orderId, long amountMinor, String currency, String email,
			String sourceToken, String description) {
	}

	record ChargeResult(Status status, String providerPaymentId, String failureCode, String failureMessage) {
		static ChargeResult succeeded(String providerPaymentId) {
			return new ChargeResult(Status.SUCCEEDED, providerPaymentId, null, null);
		}

		static ChargeResult pending(String providerPaymentId) {
			return new ChargeResult(Status.PENDING, providerPaymentId, null, null);
		}

		static ChargeResult failed(String providerPaymentId, String code, String message) {
			return new ChargeResult(Status.FAILED, providerPaymentId, code, message);
		}
	}

	record RefundRequest(String refundId, String providerPaymentId, long amountMinor, String reason) {
	}

	record RefundResult(boolean succeeded, String providerRefundId, String failureMessage) {
	}

	/** Aviso de la pasarela. Es solo un aviso: el estado real siempre se consulta con {@link #fetchCharge}. */
	record WebhookEvent(String eventId, String type, String providerPaymentId) {
	}

	/** Datos que el navegador necesita para tokenizar la tarjeta (nunca la llave secreta). */
	record PublicConfig(String provider, String publicKey) {
	}

	/** El webhook no es auténtico o está mal formado. */
	class InvalidWebhookException extends RuntimeException {
		public InvalidWebhookException(String message) {
			super(message);
		}
	}

	/** La pasarela no respondió o respondió algo inesperado: el resultado del cobro/reembolso es desconocido. */
	class GatewayException extends RuntimeException {
		public GatewayException(String message, Throwable cause) {
			super(message, cause);
		}

		public GatewayException(String message) {
			super(message);
		}
	}

	String name();

	/** Un rechazo de la tarjeta NO es una excepción: devuelve FAILED. Las excepciones son fallos de comunicación. */
	ChargeResult charge(ChargeRequest request);

	/** Estado vigente de un cobro en la pasarela. */
	ChargeResult fetchCharge(String providerPaymentId);

	RefundResult refund(RefundRequest request);

	/**
	 * Valida la autenticidad del webhook y lo traduce. {@code pathSecret} es el segmento secreto de la URL, si la
	 * pasarela no firma sus avisos.
	 */
	WebhookEvent parseWebhook(Map<String, String> headers, String rawBody, String pathSecret);

	PublicConfig publicConfig();
}
