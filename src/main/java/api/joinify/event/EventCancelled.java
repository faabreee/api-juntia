package api.joinify.event;

/** Evento de dominio: el organizador canceló un evento. Quien tenga boletos debe enterarse. */
public record EventCancelled(String eventId) {
}
