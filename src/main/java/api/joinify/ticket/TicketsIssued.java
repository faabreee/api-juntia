package api.joinify.ticket;

/** Evento de dominio: se emitieron boletos de una orden (dispara el correo de entrega). */
public record TicketsIssued(String orderId) {
}
