package api.joinify.notification;

/**
 * Estructura común de los correos: HTML con estilos en línea (los clientes de correo ignoran las hojas de estilo) y
 * una versión de texto. Todo dato variable debe pasar por {@link #esc}: nombres de evento o de personas no pueden
 * inyectar HTML en un correo.
 */
public final class EmailLayout {

	private EmailLayout() {
	}

	public static String esc(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(value.length() + 16);
		for (char c : value.toCharArray()) {
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&#39;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	/** Párrafo de texto. */
	public static String p(String text) {
		return "<p style=\"margin:0 0 16px;line-height:1.55;font-size:16px;color:#1f2937\">" + esc(text) + "</p>";
	}

	/** Título del correo. */
	public static String h1(String text) {
		return "<h1 style=\"margin:0 0 16px;font-size:24px;line-height:1.25;color:#111827\">" + esc(text) + "</h1>";
	}

	/** Botón de acción. La URL se escapa; el llamador solo pasa enlaces propios. */
	public static String button(String url, String label) {
		return "<p style=\"margin:24px 0\"><a href=\"" + esc(url) + "\" style=\"display:inline-block;background:#111827;color:#ffffff;"
				+ "text-decoration:none;font-weight:600;padding:12px 22px;border-radius:8px\">" + esc(label) + "</a></p>";
	}

	/** Filas "etiqueta: valor" en una tabla simple. */
	public static String rows(String... labelAndValue) {
		StringBuilder html = new StringBuilder("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 16px;width:100%;border-collapse:collapse\">");
		for (int i = 0; i + 1 < labelAndValue.length; i += 2) {
			html.append("<tr><td style=\"padding:6px 12px 6px 0;color:#6b7280;font-size:14px;vertical-align:top;white-space:nowrap\">")
					.append(esc(labelAndValue[i])).append("</td><td style=\"padding:6px 0;font-size:15px;color:#111827\">")
					.append(esc(labelAndValue[i + 1])).append("</td></tr>");
		}
		return html.append("</table>").toString();
	}

	/** Envuelve el contenido con la cabecera y el pie de la marca. */
	public static String wrap(String preheader, String content) {
		return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
				+ "<body style=\"margin:0;padding:0;background:#f3f4f6;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif\">"
				+ "<span style=\"display:none;max-height:0;overflow:hidden;opacity:0\">" + esc(preheader) + "</span>"
				+ "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\" style=\"padding:24px 12px\">"
				+ "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;width:100%;background:#ffffff;border-radius:12px;overflow:hidden\">"
				+ "<tr><td style=\"background:#111827;padding:18px 28px;color:#ffffff;font-size:18px;font-weight:700;letter-spacing:.02em\">Juntia</td></tr>"
				+ "<tr><td style=\"padding:28px\">" + content + "</td></tr>"
				+ "<tr><td style=\"padding:16px 28px;background:#f9fafb;color:#6b7280;font-size:12px;line-height:1.5\">"
				+ "Recibes este correo porque tienes una compra o boletos en Juntia. Si crees que es un error, ignóralo."
				+ "</td></tr></table></td></tr></table></body></html>";
	}
}
