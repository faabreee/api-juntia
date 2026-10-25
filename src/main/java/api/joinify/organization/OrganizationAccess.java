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
	private final FeeSettings defaultFees;

	OrganizationAccess(OrganizationRepository repository, @org.springframework.beans.factory.annotation.Value("${app.fees.mode}") String mode,
			@org.springframework.beans.factory.annotation.Value("${app.fees.bps}") int bps,
			@org.springframework.beans.factory.annotation.Value("${app.fees.fixed-minor}") long fixedMinor) {
		this.repository = repository;
		this.defaultFees = new FeeSettings(FeeSettings.Mode.valueOf(mode), bps, fixedMinor);
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

	/** Moneda (ISO 4217) de la organización; los precios de sus entradas se expresan en ella. */
	public String currencyOf(String organizationId) {
		return repository.organizationCurrency(organizationId);
	}

	/** Comisión vigente de la organización: su configuración propia o, si no la tiene, la de la plataforma. */
	public FeeSettings feeSettingsOf(String organizationId) {
		OrganizationRepository.FeeOverride o = repository.feeOverride(organizationId);
		return new FeeSettings(o.mode() == null ? defaultFees.mode() : FeeSettings.Mode.valueOf(o.mode()),
				o.bps() == null ? defaultFees.bps() : o.bps(), o.fixedMinor() == null ? defaultFees.fixedMinor() : o.fixedMinor());
	}
}
