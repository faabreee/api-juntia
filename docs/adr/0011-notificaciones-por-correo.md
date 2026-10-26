# 0011. Notificaciones por correo

- Estado: aceptado
- Fecha: 2026-10-25

## Contexto

Juntia envía correos de compra, boletos, recordatorios y avisos de cambios o cancelaciones. Hasta ahora los correos se enviaban directamente al producirse un evento: si el servidor de correo fallaba, el aviso se perdía sin dejar rastro, y el organizador no podía saber si un cliente lo recibió ni reenviarlo.

## Decisión

- **Cola con registro (patrón "outbox").** Cada notificación es una fila en `notifications` (tipo, destinatario, parámetros, estado, intentos). La misma tabla es la cola con reintentos y el **registro de envíos** que ve el organizador. Quien produce un aviso lo encola y se despreocupa.
- **El correo se arma al enviarse, no al encolarse.** La fila guarda el tipo y **identificadores** (orden, evento, persona), no el contenido. Cada módulo registra sus plantillas (`NotificationRenderer`) y la cola no conoce el contenido. Consecuencias buenas: el correo refleja los datos vigentes (un cambio de horario ya está en el aviso), los PDF y los correos no se guardan en la base, y reenviar es volver a armar.
- **Plantilla que se omite.** Una plantilla puede decidir que ya no corresponde enviar (el evento se canceló antes del recordatorio, la orden se reembolsó antes de la confirmación): el envío queda `SKIPPED` con el motivo.
- **Envío inmediato y barrido.** Al encolar, un intento inmediato en segundo plano; además un barrido cada 15 s toma lo pendiente, los reintentos y los envíos diferidos.
- **Reintentos con espera creciente.** Tras cada fallo la espera es 1 min, 5 min, 30 min, 2 h y 6 h; a los 5 intentos queda `FAILED` con el error. El organizador puede reactivarlo.
- **Concurrencia.** Cada envío se toma con un `UPDATE` condicional (solo un despachador gana) y se "alquila" 2 minutos: si quien lo tomó cae, otro lo retoma al vencer. La entrega es **al menos una vez**: si el proceso cae justo después de enviar y antes de anotarlo, el correo puede repetirse.
- **Sin duplicados.** Cada aviso lleva una clave única (`dedupe_key`): una confirmación por orden, una entrega por orden emitida, un recordatorio por persona, evento y día, un aviso de cancelación por persona. Los reenvíos usan claves únicas a propósito.
- **Tipos.** `ORDER_CONFIRMATION` (al quedar pagada la orden, también las gratuitas); `TICKETS_DELIVERY` (boletos con PDF, tras la emisión; reenviable); `EVENT_REMINDER` (24 h antes de cada sesión, una vez por persona y día del evento, solo si tiene boletos vigentes que la cubran, y no a quien compró hace menos de 3 h); `EVENT_CANCELLED` (a quien tenga boletos vigentes, con el motivo); `EVENT_CHANGED` (cambios de sesiones, lugar, título o zona horaria de un evento publicado; descripción no).
- **Los cambios se agrupan.** Un cambio sale 10 minutos después y se agrupa por ventana de 10 minutos y persona: si el organizador edita varias cosas seguidas, cada titular recibe un solo correo con el estado final.
- **Reenvío.** El comprador puede reenviarse sus boletos (3 por hora y orden) y el organizador reenviarlos desde su panel (10 por hora y orden). Sin boletos vigentes no hay nada que reenviar.
- **Seguridad del contenido.** Todo dato variable se escapa antes de entrar al HTML (un título de evento no puede inyectar HTML en un correo); cada correo lleva texto plano y HTML.
- **Lo que no pasa por la cola.** Los correos de verificación de cuenta, recuperación de contraseña e invitaciones siguen enviándose directamente: llevan enlaces con tokens de un solo uso y no se guardan en claro en la base de datos. Quedan sin reintentos ni registro (si fallan, la persona puede pedirlos de nuevo).

## Consecuencias

- Un fallo del servidor de correo ya no pierde avisos de negocio: se reintentan y quedan visibles.
- El correo puede llegar unos segundos o minutos después del hecho; el boleto siempre está de inmediato en "Mis boletos".
- No hay preferencias de baja: los recordatorios y avisos son transaccionales (de una compra hecha). Si más adelante se envían correos de promoción, necesitarán consentimiento y baja.
- La tabla crece con cada aviso; habrá que archivar los enviados antiguos.
- El aviso de cancelación no devuelve dinero: el organizador gestiona los reembolsos y el correo lo dice así.
