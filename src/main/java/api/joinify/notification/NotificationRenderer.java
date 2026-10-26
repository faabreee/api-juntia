package api.joinify.notification;

import java.util.List;
import java.util.Map;

/**
 * Plantilla de un tipo de notificación. Cada módulo registra las suyas: la cola no conoce el contenido. El correo se
 * arma en el momento de enviarse (no al encolarse), así refleja los datos vigentes y no se guardan cuerpos ni archivos.
 */
public interface NotificationRenderer {

	/** Un archivo adjunto, generado al enviar. */
	record Attachment(String filename, String contentType, byte[] content) {
	}

	record Rendered(String subject, String text, String html, List<Attachment> attachments) {
		public Rendered(String subject, String text, String html) {
			this(subject, text, html, List.of());
		}
	}

	/** El correo ya no tiene sentido (el evento se canceló antes del recordatorio, la orden se reembolsó, etc.). */
	class SkipNotificationException extends RuntimeException {
		public SkipNotificationException(String reason) {
			super(reason);
		}
	}

	String type();

	/** @throws SkipNotificationException si ya no corresponde enviarlo */
	Rendered render(Map<String, String> params);
}
