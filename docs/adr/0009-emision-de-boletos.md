# 0009. Emisión de boletos y QR firmado

- Estado: aceptado
- Fecha: 2026-10-25

## Contexto

Al pagarse una orden, el comprador debe recibir en segundos sus boletos con un QR que la puerta pueda validar, en la web y por correo. El QR tiene que ser infalsificable, no puede depender de una consulta para "existir" y debe seguir valiendo aunque cambie la regla de acceso (ADR 0004). La emisión no debe poder duplicar boletos ni perderlos si algo falla a medias.

## Decisión

- **Un boleto por entrada comprada.** `tickets` guarda una fila por cada unidad de cada línea de la orden. Su identidad natural es (línea de la orden, número), con una restricción única: emitir dos veces la misma línea no puede crear dos boletos.
- **Emisión por evento de dominio.** El módulo de órdenes publica `OrderPaid` cuando una orden queda pagada (cobro, orden gratuita o pago tardío) y `OrderRefunded` en un reembolso total. El módulo de boletos reacciona **después de confirmar** la transacción de la orden, en una transacción propia. Ningún módulo de órdenes ni de pagos conoce a los boletos.
- **Idempotente y con red de seguridad.** `TicketIssuer.issue` crea solo lo que falta. Se llama desde el evento y también desde `TicketRepairJob` (cada minuto), que completa emisiones interrumpidas y anula boletos de órdenes reembolsadas que quedaron vigentes. Si la emisión falla tras el pago, la orden sigue pagada y la reparación termina el trabajo; no se revierte el cobro.
- **QR firmado con Ed25519.** El token es `JT1.<kid>.<boleto>.<evento>.<firma>`: solo identificadores más la firma de los cuatro primeros campos. No lleva datos personales ni la regla de acceso: la puerta consulta la regla vigente, así cambiarla no invalida QR ya emitidos y un QR fotografiado no revela nada. Ed25519 es determinista: el mismo boleto produce siempre el mismo token, por lo que no se guarda.
- **Rotación de claves.** El `kid` del token elige la clave pública con la que verificar. Para rotar se firma con una clave nueva y se conservan las públicas antiguas mientras haya boletos vigentes. La aplicación se niega a arrancar si la clave pública vigente no corresponde a la privada.
- **Clave de desarrollo.** Hay una clave por defecto **pública en el repositorio** (kid `dev1`), solo para desarrollo, con un aviso en el arranque. En producción hay que definir `TICKET_SIGNING_KEY`, `TICKET_KEY_ID` y `TICKET_PUBLIC_KEYS`; la clave privada nunca debe versionarse.
- **Código corto de respaldo.** Ocho caracteres de un alfabeto sin símbolos ambiguos (30^8 combinaciones, `ABCD-EFGH`), único por boleto, para buscar a mano en puerta.
- **Boleto anulado.** Un reembolso total anula sus boletos (`VOID`): dejan de entregar QR y de generar PDF. Un reembolso parcial no los toca.
- **Boletos nominativos opcionales.** El organizador activa `nominative` por tipo de entrada; el comprador registra el nombre de cada asistente desde "Mis boletos". El QR se entrega de inmediato; el nombre es editable mientras el boleto sea válido (la Etapa 7 lo bloqueará tras el primer ingreso).
- **PDF.** Una página por boleto, con el QR dibujado como vectores (nítido al imprimir) y las sesiones que cubre. Se adjunta al correo de confirmación. Las fuentes estándar solo dibujan Latin-1: otros caracteres se sustituyen por `?` en el PDF (la web sí los muestra).
- **Correo.** Se envía en segundo plano al emitirse; si el servidor de correo falla se registra y no afecta ni a la compra ni a los boletos, que siempre están en "Mis boletos". El reenvío desde el panel es de la Etapa 8.
- **Privacidad.** Un boleto solo lo ve su comprador; el de otra persona responde 404.

## Consecuencias

- La puerta (Etapa 7) puede validar un QR sin red para comprobar la firma, y consultar la regla y el historial de ingresos para decidir (`AccessPolicy`).
- El correo puede llegar unos segundos después que el boleto en pantalla.
- Quien comparte una captura del QR comparte el acceso: la validación debe registrar el ingreso (la Etapa 7 lo hace de forma atómica).
- Apple/Google Wallet queda para después; se apoyará en el mismo token.
