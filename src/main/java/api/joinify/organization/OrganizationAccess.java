package api.joinify.organization;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Punto único de autorización por organización. Todo endpoint de gestión (de este y otros módulos)
 * debe pasar por aquí antes de leer o modificar datos de una organización.
 *
 * Quien no pertenece a la organización recibe 404, igual que si no existiera, para no revelar su existencia.
 */
@Service
public class OrganizationAccess {

	private final OrganizationRepository repository;

	OrganizationAccess(OrganizationRepository repository) {
		this.repository = repository;
	}

	/** Exige que el usuario sea miembro y devuelve su rol. */
	public Role requireMember(String organizationId, String userId) {
		return repository.findRole(organizationId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organización no encontrada"));
	}

	/** Exige que el usuario sea OWNER o ADMIN de la organización. */
	public Role requireManager(String organizationId, String userId) {
		Role role = requireMember(organizationId, userId);
		if (!role.canManage()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permiso para esta acción");
		}
		return role;
	}

	/** Exige que el usuario sea OWNER de la organización. */
	public Role requireOwner(String organizationId, String userId) {
		Role role = requireMember(organizationId, userId);
		if (role != Role.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el propietario puede hacer esto");
		}
		return role;
	}

	/** Nombre público de la organización (para páginas públicas de eventos). */
	public String nameOf(String organizationId) {
		return repository.organizationName(organizationId);
	}
}
