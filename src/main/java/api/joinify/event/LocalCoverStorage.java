package api.joinify.event;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
class LocalCoverStorage implements CoverStorage {

	private final Path root;

	LocalCoverStorage(@Value("${app.storage.dir}") String dir) {
		this.root = Path.of(dir).toAbsolutePath().normalize().resolve("covers");
	}

	@Override
	public String store(InputStream content, String extension) throws IOException {
		Files.createDirectories(root);
		String key = UUID.randomUUID() + "." + extension;
		Files.copy(content, root.resolve(key), StandardCopyOption.REPLACE_EXISTING);
		return key;
	}

	@Override
	public Resource load(String key) {
		return new FileSystemResource(resolve(key));
	}

	@Override
	public void delete(String key) {
		try {
			Files.deleteIfExists(resolve(key));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Evita salir del directorio de portadas aunque la clave estuviera corrupta. */
	private Path resolve(String key) {
		Path path = root.resolve(key).normalize();
		if (!path.startsWith(root)) {
			throw new IllegalArgumentException("Clave de portada inválida");
		}
		return path;
	}
}
