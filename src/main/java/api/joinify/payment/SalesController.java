package api.joinify.payment;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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
import api.joinify.organization.OrganizationAccess;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
@Tag(name = "Ventas y reembolsos")
class SalesController {

	enum RefundReason {
		REQUESTED_BY_BUYER, DUPLICATE, FRAUDULENT, EVENT_CANCELLED, OTHER
	}

	record SaleResponse(@NotNull String orderId, @NotNull String eventTitle, @NotNull String buyerEmail,
			@NotNull String buyerName, @NotNull String status, @NotNull String currency, long subtotalMinor, long feeMinor,
			@NotNull String feeMode, long totalMinor, @NotNull Instant createdAt, Instant paidAt, long refundedMinor,
			long refundableMinor) {
	}

	/** {@code amountMinor} vacío = reembolso total de lo que quede. */
	record RefundRequest(@Min(1) Long amountMinor, @NotNull RefundReason reason, @Size(max = 255) String note) {
	}

	record RefundResponse(@NotNull String id, @NotNull String orderId, long amountMinor,
			@NotNull PaymentRepository.RefundStatus status, @NotNull String reason, String failureMessage) {
	}

	private final SalesRepository sales;
	private final RefundService refunds;
	private final OrganizationAccess access;

	SalesController(SalesRepository sales, RefundService refunds, OrganizationAccess access) {
		this.sales = sales;
		this.refunds = refunds;
		this.access = access;
	}

	/** Órdenes de la organización con su desglose y lo que aún se puede reembolsar. Solo OWNER y ADMIN. */
	@GetMapping("/sales")
	List<SaleResponse> listSales(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId) {
		access.requireManager(organizationId, user.getId());
		return sales.findByOrganization(organizationId, 200).stream()
				.map(s -> new SaleResponse(s.orderId(), s.eventTitle(), s.buyerEmail(), s.buyerName(), s.status(), s.currency(),
						s.subtotalMinor(), s.feeMinor(), s.feeMode(), s.totalMinor(), s.createdAt().toInstant(ZoneOffset.UTC),
						s.paidAt() == null ? null : s.paidAt().toInstant(ZoneOffset.UTC), s.refundedMinor(), s.refundableMinor()))
				.toList();
	}

	/** Reembolso total o parcial. Idempotente por Idempotency-Key. Si la pasarela lo rechaza responde 502. */
	@PostMapping("/orders/{orderId}/refunds")
	ResponseEntity<RefundResponse> refundOrder(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String orderId, @RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody RefundRequest request) {
		if (!PaymentController.KEY_FORMAT.matcher(idempotencyKey).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Idempotency-Key debe tener entre 8 y 64 caracteres (letras, números, . _ : -)");
		}
		var view = refunds.refund(user, organizationId, orderId, request.amountMinor(), request.reason().name(),
				request.note(), idempotencyKey);
		var refund = view.refund();
		if (refund.status() == PaymentRepository.RefundStatus.FAILED) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					"La pasarela no pudo procesar el reembolso: " + refund.failureMessage());
		}
		return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
				.header("Idempotent-Replayed", String.valueOf(view.replayed()))
				.body(new RefundResponse(refund.id(), refund.orderId(), refund.amountMinor(), refund.status(), refund.reason(),
						refund.failureMessage()));
	}
}
