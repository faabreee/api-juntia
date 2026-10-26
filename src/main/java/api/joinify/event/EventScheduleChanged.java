package api.joinify.event;

/** Evento de dominio: cambió algo que afecta a quien tiene boletos de un evento publicado (sesiones, lugar, título, zona horaria). */
public record EventScheduleChanged(String eventId) {
}
