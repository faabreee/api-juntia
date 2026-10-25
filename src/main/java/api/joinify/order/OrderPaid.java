package api.joinify.order;

/** Evento de dominio: la orden quedó pagada (cobrada, gratuita o reabierta por un pago tardío). */
public record OrderPaid(String orderId) {
}
