# 0007. Pasarela de pago

- Estado: aceptado
- Fecha: 2026-10-25

## Contexto

Juntia cobra en soles desde Perú y más adelante en otros países. Se evaluaron Mercado Pago, Culqi/Niubiz y Stripe. Stripe no ofrece cuentas de comercio en Perú; Culqi y Niubiz son procesadores locales con tarjetas y Yape. Se eligió **Culqi** para el primer lanzamiento; Niubiz puede añadirse después como otro adaptador.

## Decisión

- **Puerto y adaptadores.** El módulo de pagos solo conoce `PaymentGateway` (cobrar con un token, consultar un cobro, reembolsar, validar un webhook). Hay dos implementaciones: `CulqiGateway` y `SimulatedGateway`. Se elige con `app.payments.provider` (`simulated` por defecto, `culqi` en producción). Cambiar o añadir una pasarela es escribir otro adaptador.
- **Modo de prueba.** `SimulatedGateway` no mueve dinero y permite probar todos los casos (aprobado, rechazado, pendiente, reembolso rechazado, pasarela caída, webhooks firmados). Todas las pruebas automatizadas del flujo de pagos usan este adaptador.
- **Datos de tarjeta.** Nunca pasan por nuestro servidor: el navegador obtiene un token de la pasarela y el backend solo cobra con ese token (`source_id` en Culqi). Así no manejamos datos de tarjeta (alcance PCI mínimo).
- **El webhook es solo un aviso.** Se autentica (firma HMAC en el simulado; secreto en la URL en Culqi, porque su documentación pública no describe una firma), se registra una sola vez (`webhook_events`, único por proveedor y evento) y **el estado real se consulta siempre a la pasarela** antes de actuar. Un aviso falso o repetido no puede cambiar el estado de un pago.
- **Idempotencia en todas las capas.** Cobro por (orden, clave); aviso por (proveedor, evento); toda transición de estado es un `UPDATE ... WHERE status IN (...)`; el libro rechaza asientos repetidos por clave. Repetir cualquier paso no duplica nada.
- **Un rechazo no es un error.** Una tarjeta rechazada es un pago `FAILED` con motivo y la orden sigue pendiente para reintentar. Solo un fallo de comunicación deja el pago abierto (`PENDING` sin id) hasta que la conciliación lo resuelve.
- **Conciliación.** Cada minuto se consultan los pagos abiertos hace más de 2 minutos; sin id de cobro tras 10 minutos se dan por no realizados. Cada día se verifica que el libro cuadre.
- **Pago tardío.** Si el dinero llega con la orden expirada o cancelada, se vuelve a reservar el cupo y la orden se honra; si ya no hay cupo (o la orden ya estaba pagada), se reembolsa solo y completo.

## Lo que falta validar con el sandbox de Culqi (antes de vender)

`CulqiGateway` se escribió con la documentación pública y se probó solo contra un servidor simulado. La documentación confirma el token en `source_id`, la forma del error de rechazo (`code`, `decline_code`, `merchant_message`), las devoluciones con `charge_id` y los eventos `charge.*` y `refund.*`. **No documenta** y debe comprobarse con llaves de prueba:

1. Nombres exactos de los campos de petición (`amount` en céntimos, `currency_code`, `email`, `reason` y sus valores).
2. El código HTTP de un cobro rechazado y qué devuelve `GET /charges/{id}` para un cargo aprobado, pendiente o rechazado.
3. La forma real del webhook (en particular si `data` viene como objeto o como texto) y el nombre exacto de los eventos de rechazo (`charge.failed` frente a `charge.creation.failed`).
4. La integración del navegador (Culqi.js / Checkout) que produce el token; el frontend aún no la incluye.
5. Culqi no documenta idempotencia en `POST /charges`: si una petición de cobro se pierde en la red no hay forma de saber si cobró. El pago queda pendiente y la conciliación lo resuelve; el riesgo de cobro doble en ese caso excepcional debe revisarse con Culqi (por ejemplo, buscar el cargo por `metadata.payment_id`).

## Consecuencias

- El resto del sistema (órdenes, boletos, panel) no depende de la pasarela.
- Antes de producción hay que cumplir la lista de arriba; la aplicación se niega a arrancar con `provider=culqi` si faltan las llaves o el secreto del webhook.
- Cada reembolso en Culqi puede tardar hasta 30 días en reflejarse en la tarjeta del cliente, aunque aquí figure como completado.
