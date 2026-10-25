package api.joinify.order;

/** Evento de dominio: la orden se reembolsó por completo; sus boletos dejan de valer. */
public record OrderRefunded(String orderId) {
}
