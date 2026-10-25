package api.joinify.organization;

/**
 * Comisión de la plataforma sobre una venta (ADR 0008): porcentaje en puntos básicos más un monto fijo.
 *
 * @param mode        quién la paga: el comprador (se suma al precio) o el organizador (se descuenta de lo que recibe)
 * @param bps         porcentaje en puntos básicos (500 = 5 %)
 * @param fixedMinor  monto fijo por orden, en la unidad menor de la moneda
 */
public record FeeSettings(Mode mode, int bps, long fixedMinor) {

	public enum Mode {
		PASSED_TO_BUYER, ABSORBED
	}

	/** Comisión de una orden con ese subtotal. Las órdenes gratuitas no pagan comisión. Redondeo: mitad hacia arriba. */
	public long feeFor(long subtotalMinor) {
		if (subtotalMinor <= 0) {
			return 0;
		}
		long percent = (subtotalMinor * bps + 5_000) / 10_000;
		return percent + fixedMinor;
	}
}
