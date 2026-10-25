# 0008. Modelo de dinero, comisión y reembolsos

- Estado: aceptado (pendiente de revisión contable)
- Fecha: 2026-10-25

## Contexto

Hay que decidir por dónde pasa el dinero de una venta y quién paga la comisión de la plataforma. La decisión tiene consecuencias contables, tributarias y regulatorias que deben validarse con un contador antes de operar con dinero real.

## Decisión

- **El dinero pasa por la plataforma.** La pasarela cobra a nombre de Juntia; Juntia retiene su comisión y liquida el resto al organizador (la liquidación es la Etapa 9). El organizador no necesita cuenta en la pasarela.
- **Libro de movimientos (`ledger_entries`).** Cada cobro asienta dos importes cuya suma es lo cobrado: `ORGANIZER_PAYABLE` (lo que la plataforma debe al organizador) y `PLATFORM_FEE` (ingreso de la plataforma). Cada reembolso asienta importes negativos. Los asientos son de solo inserción, con importes con signo y una clave única que impide registrar dos veces el mismo movimiento. Para cada pago, la suma del libro es lo cobrado menos lo devuelto; una verificación diaria (`checkLedger`) lo comprueba.
- **Comisión configurable.** Porcentaje en puntos básicos más un monto fijo por orden, con estas reglas: la comisión de una orden gratuita es 0; el redondeo es mitad hacia arriba; cada organización puede tener su propia configuración (`platform_fee_*` en `organizations`) y, si no la tiene, se usan los valores por defecto de la aplicación (`app.fees.*`, hoy 5 % y 0 fijo, **valores provisionales a definir por el negocio**).
- **Quién la paga** (`PASSED_TO_BUYER` por defecto):
  - `PASSED_TO_BUYER`: total = subtotal + comisión. El organizador recibe el subtotal completo.
  - `ABSORBED`: total = subtotal. El organizador recibe subtotal − comisión.
  - En ambos casos: neto del organizador = total − comisión, y neto + comisión = lo cobrado.
- **La orden guarda su desglose** (subtotal, comisión, modo y total) en el momento de comprar; cambiar la configuración después no altera órdenes existentes.
- **Reembolsos totales y parciales**, pedidos por el organizador (OWNER o ADMIN) sobre sus órdenes:
  - Se reserva el monto reembolsable con un `UPDATE` condicional atómico antes de llamar a la pasarela, de modo que dos reembolsos simultáneos nunca suman más que lo cobrado; si la pasarela rechaza, la reserva se devuelve.
  - Cada reembolso se reparte entre la comisión y la parte del organizador **en proporción a lo que queda de cada una**; un reembolso que agota el saldo devuelve exactamente lo que quede (sin residuos por redondeo).
  - Reembolso total: la orden pasa a `REFUNDED` y sus entradas vuelven al inventario. Reembolso parcial: la orden sigue pagada y conserva su cupo (es un reembolso de dinero, no una cancelación).
  - Al reembolsar la comisión también se devuelve su parte: el comprador recibe de vuelta lo que pagó, no solo el precio de las entradas.
- **Quién configura la comisión.** Todavía no existe el rol de administrador de plataforma, por lo que las comisiones por organización se fijan directamente en la base de datos. Un organizador no puede cambiar su propia comisión.

## Preguntas abiertas para el contador

1. Tratamiento del IGV (18 %) sobre la comisión de la plataforma y sobre el cargo por servicio cobrado al comprador; comprobante que debe emitirse al comprador y al organizador.
2. Si retener el dinero de terceros hasta la liquidación exige alguna autorización o tratamiento especial (por ejemplo, cuenta separada o fideicomiso).
3. Plazo de liquidación al organizador y qué pasa con los reembolsos posteriores a la liquidación (hoy el libro queda en negativo y debe compensarse en la siguiente).
4. Contracargos (disputas de tarjeta): no están modelados todavía.

## Consecuencias

- Mayor control y responsabilidad financiera para la plataforma, a cambio de no depender de que cada organizador tenga cuenta en la pasarela.
- La Etapa 9 construye el reporte de liquidación sobre el libro; no hace falta cambiar el modelo.
- Cambiar a "directo al organizador" más adelante implicaría otro adaptador de cobro con división de pagos, pero el libro y las órdenes seguirían sirviendo.
