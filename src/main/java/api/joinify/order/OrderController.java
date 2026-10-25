package api.joinify.order;

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
import api.joinify.order.OrderService.Line;
import api.joinify.order.OrderService.OrderView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Órdenes")
class OrderController {

	static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
	private static final java.util.regex.Pattern KEY_FORMAT = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{8,64}");

	record LineRequest(@NotBlank String ticketTypeId, @Min(1) @Max(50) int quantity) {
	}

	record CreateOrderRequest(@NotBlank String eventSlug, @NotEmpty @Size(max = OrderService.MAX_LINES) List<@Valid LineRequest> items) {
	}

	record ItemResponse(@NotNull String ticketTypeId, @NotNull String name, int quantity, long unitPriceMinor) {
	}

	record OrderResponse(@NotNull String id, @NotNull OrderStatus status, @NotNull String eventSlug, @NotNull String eventTitle,
			@NotNull String currency, long totalMinor, @NotNull Instant createdAt, Instant expiresAt, Instant paidAt,
			@NotNull List<ItemResponse> items) {

		static OrderResponse of(OrderView v) {
			var o = v.order();
			return new OrderResponse(o.id(), o.status(), o.eventSlug(), o.eventTitle(), o.currency(), o.totalMinor(),
					o.createdAt().toInstant(ZoneOffset.UTC),
					o.expiresAt() == null ? null : o.expiresAt().toInstant(ZoneOffset.UTC),
					o.paidAt() == null ? null : o.paidAt().toInstant(ZoneOffset.UTC),
					v.items().stream().map(i -> new ItemResponse(i.ticketTypeId(), i.name(), i.quantity(), i.unitPriceMinor())).toList());
		}
	}

	private final OrderService service;

	OrderController(OrderService service) {
		this.service = service;
	}

	/**
	 * Crea una orden y reserva el cupo. Requiere la cabecera Idempotency-Key (8 a 64 caracteres): repetir la petición
	 * con la misma clave devuelve la misma orden (200 en lugar de 201).
	 */
	@PostMapping
	ResponseEntity<OrderResponse> createOrder(@AuthenticationPrincipal AuthUser user,
			@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey, @Valid @RequestBody CreateOrderRequest request) {
		if (!KEY_FORMAT.matcher(idempotencyKey).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Idempotency-Key debe tener entre 8 y 64 caracteres (letras, números, . _ : -)");
		}
		OrderView view = service.create(user, idempotencyKey, request.eventSlug(),
				request.items().stream().map(i -> new Line(i.ticketTypeId(), i.quantity())).toList());
		return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
				.header("Idempotent-Replayed", String.valueOf(view.replayed())).body(OrderResponse.of(view));
	}

	@GetMapping
	List<OrderResponse> listMyOrders(@AuthenticationPrincipal AuthUser user) {
		return service.mine(user).stream().map(OrderResponse::of).toList();
	}

	@GetMapping("/{orderId}")
	OrderResponse getOrder(@AuthenticationPrincipal AuthUser user, @PathVariable String orderId) {
		return OrderResponse.of(service.get(user, orderId));
	}

	@PostMapping("/{orderId}/cancel")
	OrderResponse cancelOrder(@AuthenticationPrincipal AuthUser user, @PathVariable String orderId) {
		return OrderResponse.of(service.cancel(user, orderId));
	}
}
