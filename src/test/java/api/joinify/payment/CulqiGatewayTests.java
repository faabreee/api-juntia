package api.joinify.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import api.joinify.payment.PaymentGateway.ChargeRequest;
import api.joinify.payment.PaymentGateway.GatewayException;
import api.joinify.payment.PaymentGateway.InvalidWebhookException;
import api.joinify.payment.PaymentGateway.RefundRequest;
import api.joinify.payment.PaymentGateway.Status;

/**
 * Verifica que el adaptador de Culqi arme las peticiones y lea las respuestas como describe la documentación pública.
 * NO sustituye una prueba contra el sandbox real (ver ADR 0007): usa un servidor simulado.
 */
class CulqiGatewayTests {

	private static final String BASE = "https://api.culqi.test/v2";
	private MockRestServiceServer server;
	private CulqiGateway gateway;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		gateway = new CulqiGateway(builder, BASE, "pk_test_abc", "sk_test_secret", "whsec");
	}

	private static ChargeRequest charge() {
		return new ChargeRequest("pay-1", "ord-1", 10_500, "PEN", "ana@example.com", "tkn_test_123", "Orden Concierto");
	}

	@Test
	void charge_sendsTokenAndAmount_andReadsApproval() {
		server.expect(requestTo(BASE + "/charges")).andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer sk_test_secret"))
				.andExpect(jsonPath("$.amount").value(10_500)).andExpect(jsonPath("$.currency_code").value("PEN"))
				.andExpect(jsonPath("$.email").value("ana@example.com")).andExpect(jsonPath("$.source_id").value("tkn_test_123"))
				.andExpect(jsonPath("$.metadata.order_id").value("ord-1"))
				.andRespond(withSuccess("{\"object\":\"charge\",\"id\":\"chr_test_1\",\"capture\":true}", MediaType.APPLICATION_JSON));

		var result = gateway.charge(charge());

		assertThat(result.status()).isEqualTo(Status.SUCCEEDED);
		assertThat(result.providerPaymentId()).isEqualTo("chr_test_1");
		server.verify();
	}

	@Test
	void charge_withoutCapture_isPending() {
		server.expect(requestTo(BASE + "/charges"))
				.andRespond(withSuccess("{\"object\":\"charge\",\"id\":\"chr_test_2\",\"capture\":false}", MediaType.APPLICATION_JSON));

		assertThat(gateway.charge(charge()).status()).isEqualTo(Status.PENDING);
	}

	@Test
	void declinedCard_isAFailureNotAnException() {
		server.expect(requestTo(BASE + "/charges")).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"object\":\"error\",\"type\":\"card_error\",\"charge_id\":\"chr_test_3\",\"code\":\"card_declined\",\"decline_code\":\"insufficient_funds\",\"merchant_message\":\"Fondos insuficientes.\",\"user_message\":\"Su tarjeta no tiene fondos suficientes.\"}"));

		var result = gateway.charge(charge());

		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.failureCode()).isEqualTo("insufficient_funds");
		assertThat(result.failureMessage()).contains("fondos");
		assertThat(result.providerPaymentId()).isEqualTo("chr_test_3");
	}

	@Test
	void serverError_isAnUnknownOutcome() {
		server.expect(requestTo(BASE + "/charges")).andRespond(withServerError());

		assertThatThrownBy(() -> gateway.charge(charge())).isInstanceOf(GatewayException.class);
	}

	@Test
	void fetchCharge_readsTheCurrentState() {
		server.expect(requestTo(BASE + "/charges/chr_test_1")).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"object\":\"charge\",\"id\":\"chr_test_1\",\"capture\":true}", MediaType.APPLICATION_JSON));

		assertThat(gateway.fetchCharge("chr_test_1").status()).isEqualTo(Status.SUCCEEDED);
	}

	@Test
	void refund_usesChargeIdAndAmount() {
		server.expect(requestTo(BASE + "/refunds")).andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.charge_id").value("chr_test_1")).andExpect(jsonPath("$.amount").value(5_000))
				.andExpect(jsonPath("$.reason").value("solicitud_comprador"))
				.andRespond(withSuccess("{\"object\":\"refund\",\"id\":\"ref_test_1\"}", MediaType.APPLICATION_JSON));

		var result = gateway.refund(new RefundRequest("r-1", "chr_test_1", 5_000, "solicitud_comprador"));

		assertThat(result.succeeded()).isTrue();
		assertThat(result.providerRefundId()).isEqualTo("ref_test_1");
	}

	@Test
	void refundRejected_isAFailure() {
		server.expect(requestTo(BASE + "/refunds")).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"object\":\"error\",\"merchant_message\":\"El monto excede el cargo.\"}"));

		var result = gateway.refund(new RefundRequest("r-1", "chr_test_1", 99_999, "solicitud_comprador"));

		assertThat(result.succeeded()).isFalse();
		assertThat(result.failureMessage()).contains("excede");
	}

	@Test
	void webhook_requiresTheSecretInTheUrl() {
		String body = "{\"object\":\"event\",\"id\":\"evt_1\",\"type\":\"charge.creation.succeeded\",\"data\":{\"id\":\"chr_test_1\"}}";

		assertThatThrownBy(() -> gateway.parseWebhook(Map.of(), body, "otro")).isInstanceOf(InvalidWebhookException.class);
		assertThatThrownBy(() -> gateway.parseWebhook(Map.of(), body, null)).isInstanceOf(InvalidWebhookException.class);

		var event = gateway.parseWebhook(Map.of(), body, "whsec");
		assertThat(event.eventId()).isEqualTo("evt_1");
		assertThat(event.type()).isEqualTo("charge.creation.succeeded");
		assertThat(event.providerPaymentId()).isEqualTo("chr_test_1");
	}

	@Test
	void webhook_acceptsDataAsJsonText_andRefundEvents() {
		String charge = "{\"id\":\"evt_2\",\"type\":\"charge.failed\",\"data\":\"{\\\"id\\\":\\\"chr_test_9\\\"}\"}";
		assertThat(gateway.parseWebhook(Map.of(), charge, "whsec").providerPaymentId()).isEqualTo("chr_test_9");

		String refund = "{\"id\":\"evt_3\",\"type\":\"refund.creation.succeeded\",\"data\":{\"id\":\"ref_1\",\"charge_id\":\"chr_test_9\"}}";
		assertThat(gateway.parseWebhook(Map.of(), refund, "whsec").providerPaymentId()).isEqualTo("chr_test_9");
	}

	@Test
	void requiresAllCredentialsAtStartup() {
		assertThatThrownBy(() -> new CulqiGateway(RestClient.builder(), BASE, "", "sk", "w")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new CulqiGateway(RestClient.builder(), BASE, "pk", "", "w")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new CulqiGateway(RestClient.builder(), BASE, "pk", "sk", "")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void publicConfig_neverExposesTheSecretKey() {
		var config = gateway.publicConfig();

		assertThat(config.publicKey()).isEqualTo("pk_test_abc");
		assertThat(config.toString()).doesNotContain("sk_test_secret");
	}
}
