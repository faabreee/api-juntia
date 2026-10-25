package api.joinify.ticket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.FormatStyle;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import api.joinify.event.EventCatalog.SessionInfo;
import api.joinify.ticket.TicketService.TicketDetail;

/** Boletos en PDF: una página por boleto con su QR dibujado como vectores (nítido al imprimir). */
@Service
class TicketPdfService {

	private static final float MARGIN = 48;
	private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
	private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

	byte[] render(List<TicketDetail> tickets) {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (TicketDetail ticket : tickets) {
				page(document, ticket);
			}
			if (tickets.isEmpty()) {
				document.addPage(new PDPage(PDRectangle.A4));
			}
			document.save(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void page(PDDocument document, TicketDetail detail) throws IOException {
		PDPage page = new PDPage(PDRectangle.A4);
		document.addPage(page);
		float width = page.getMediaBox().getWidth();
		float y = page.getMediaBox().getHeight() - MARGIN;
		var ticket = detail.view().ticket();
		var event = detail.event();
		DateTimeFormatter format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.withLocale(Locale.forLanguageTag("es")).withZone(ZoneId.of(event.timezone()));

		try (PDPageContentStream content = new PDPageContentStream(document, page)) {
			text(content, BOLD, 11, MARGIN, y, "JUNTIA");
			y -= 34;
			for (String line : wrap(event.title(), BOLD, 24, width - 2 * MARGIN)) {
				text(content, BOLD, 24, MARGIN, y, line);
				y -= 30;
			}
			text(content, REGULAR, 12, MARGIN, y, "Organiza " + event.organizationName());
			y -= 34;

			text(content, BOLD, 14, MARGIN, y, ticket.ticketTypeName());
			y -= 20;
			if (ticket.attendeeName() != null) {
				text(content, REGULAR, 12, MARGIN, y, "Asistente: " + ticket.attendeeName());
				y -= 18;
			}
			y -= 8;
			for (SessionInfo session : detail.coveredSessions()) {
				text(content, BOLD, 12, MARGIN, y, session.name());
				y -= 16;
				text(content, REGULAR, 11, MARGIN, y, format.format(session.startsAt()) + "  (" + event.timezone() + ")");
				y -= 15;
				if (session.venueName() != null) {
					String place = session.venueName() + (session.venueCity() == null ? "" : ", " + session.venueCity());
					text(content, REGULAR, 11, MARGIN, y, place);
					y -= 15;
				}
				y -= 8;
			}

			// QR centrado en la parte inferior
			float qrSize = 260;
			float qrX = (width - qrSize) / 2;
			float qrY = Math.min(y - qrSize - 20, 320);
			qr(content, detail.view().qrToken(), qrX, qrY, qrSize);
			float codeY = qrY - 34;
			String code = ShortCode.format(ticket.code());
			float codeWidth = BOLD.getStringWidth(code) / 1000 * 22;
			text(content, BOLD, 22, (width - codeWidth) / 2, codeY, code);
			String hint = "Presenta este código QR en la entrada. Código de respaldo para búsqueda manual.";
			float hintWidth = REGULAR.getStringWidth(hint) / 1000 * 9;
			text(content, REGULAR, 9, (width - hintWidth) / 2, codeY - 20, hint);
			text(content, REGULAR, 8, MARGIN, 30, "Boleto " + ticket.id() + " · Orden " + ticket.orderId());
		}
	}

	/** Dibuja el QR como cuadros negros sobre fondo blanco, con zona de silencio. */
	private void qr(PDPageContentStream content, String token, float x, float y, float size) throws IOException {
		if (token == null) {
			return;
		}
		BitMatrix matrix;
		try {
			matrix = new QRCodeWriter().encode(token, BarcodeFormat.QR_CODE, 0, 0,
					java.util.Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 0));
		} catch (WriterException e) {
			throw new IllegalStateException("No se pudo generar el QR", e);
		}
		int modules = matrix.getWidth();
		float quiet = size * 0.06f;
		float cell = (size - 2 * quiet) / modules;
		content.setNonStrokingColor(1f, 1f, 1f);
		content.addRect(x, y, size, size);
		content.fill();
		content.setNonStrokingColor(0f, 0f, 0f);
		for (int row = 0; row < modules; row++) {
			for (int col = 0; col < modules; col++) {
				if (matrix.get(col, row)) {
					content.addRect(x + quiet + col * cell, y + size - quiet - (row + 1) * cell, cell + 0.2f, cell + 0.2f);
				}
			}
		}
		content.fill();
		content.setStrokingColor(0f, 0f, 0f);
		content.addRect(x, y, size, size);
		content.stroke();
	}

	private static void text(PDPageContentStream content, PDType1Font font, float size, float x, float y, String value) throws IOException {
		content.beginText();
		content.setFont(font, size);
		content.newLineAtOffset(x, y);
		content.showText(sanitize(font, value));
		content.endText();
	}

	/** Las fuentes estándar del PDF solo codifican Latin-1; cualquier otro carácter se sustituye por '?'. */
	private static String sanitize(PDType1Font font, String value) {
		StringBuilder clean = new StringBuilder();
		for (char c : value.toCharArray()) {
			try {
				font.encode(String.valueOf(c));
				clean.append(c);
			} catch (IOException | IllegalArgumentException e) {
				clean.append('?');
			}
		}
		return clean.toString();
	}

	private static List<String> wrap(String value, PDType1Font font, float size, float maxWidth) throws IOException {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : sanitize(font, value).split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (font.getStringWidth(candidate) / 1000 * size > maxWidth && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}
}
