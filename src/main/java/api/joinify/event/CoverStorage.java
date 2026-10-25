package api.joinify.event;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.Resource;

/** Almacén de portadas. La implementación actual es local; se puede reemplazar por S3 sin tocar el servicio. */
interface CoverStorage {

	/** Guarda el contenido y devuelve la clave con la que se recupera. */
	String store(InputStream content, String extension) throws IOException;

	Resource load(String key);

	void delete(String key);
}
