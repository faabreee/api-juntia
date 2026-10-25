# 0006. Reserva de inventario y órdenes

- Estado: aceptado
- Fecha: 2026-10-25

## Contexto

Varios compradores pueden pedir a la vez los últimos cupos de un tipo de entrada. No se puede vender más cupo del que existe, ni siquiera con peticiones simultáneas o con varias instancias del backend. Además, un pago tarda: el cupo debe reservarse mientras tanto y liberarse si no se paga.

## Decisión

- **Contador por tipo de entrada.** `ticket_types.taken` cuenta las entradas reservadas (órdenes pendientes) más las vendidas (pagadas).
- **Reserva atómica.** `UPDATE ticket_types SET taken = taken + :n WHERE id = :id AND (quota IS NULL OR taken + :n <= quota)`. Si no actualiza una fila, no hay cupo. La fila queda bloqueada hasta el commit, así que no hay lectura-y-escritura separadas ni sobreventa. No se usan bloqueos en memoria: funciona con varias instancias.
- **Una transacción por orden.** La orden y todas sus líneas se reservan juntas; si una línea falla se revierte todo. Las líneas se procesan en orden estable por tipo de entrada para evitar interbloqueos.
- **Estados.** `PENDING` → `PAID` | `EXPIRED` | `CANCELLED`. Una orden de total 0 nace `PAID` (se completa sin pago). Las de pago nacen `PENDING` con `expires_at` (15 min por defecto, `app.orders.hold-minutes`).
- **Liberación.** Cancelar o expirar cierra la orden con `UPDATE ... WHERE status = 'PENDING'` y solo quien logra cerrarla devuelve el cupo, por lo que doble cancelación, doble barrido o varias instancias nunca liberan de más. Un barrido programado (`OrderExpiryJob`, cada 15 s) expira las vencidas.
- **Idempotencia.** `POST /api/orders` exige `Idempotency-Key`. La restricción única `(comprador, clave)` reclama la clave al insertar: peticiones repetidas o simultáneas con la misma clave y el mismo contenido devuelven la misma orden (200 en lugar de 201); la misma clave con otro contenido es 409.
- **Precio y nombre.** Cada línea guarda una foto del precio y del nombre al comprar.
- **Compradores.** Se requiere una cuenta con sesión; las órdenes son privadas (una orden ajena responde 404).

## Consecuencias

- Una orden pendiente retiene cupo hasta 15 minutos más el intervalo del barrido; ese cupo no se ve disponible en ese lapso.
- Con cupo tomado, el tipo de entrada no puede cambiar su regla de acceso ni bajar el cupo por debajo de lo tomado, no se puede eliminar (se oculta) y el evento no se puede despublicar. Se puede cambiar precio, nombre, visibilidad y subir el cupo.
- La etapa de pagos usará `OrderService.confirmPayment`. Si un pago llega con la orden ya expirada, la confirmación se rechaza y esa etapa decide (reembolso o nueva reserva).
- Probado: 50 compradores simultáneos por los últimos 10 cupos producen exactamente 10 órdenes, y la prueba falla (50 órdenes) si la reserva se reemplaza por lectura-y-escritura.
