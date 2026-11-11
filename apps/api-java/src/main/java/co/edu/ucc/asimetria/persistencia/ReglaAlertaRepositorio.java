package co.edu.ucc.asimetria.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReglaAlertaRepositorio extends JpaRepository<ReglaAlerta, Long> {

    @Query("select r from ReglaAlerta r join fetch r.empresa where r.usuarioId = :usuarioId order by r.id desc")
    List<ReglaAlerta> listar(Long usuarioId);

    /** Reglas que el evaluador debe revisar (usa el índice parcial ix_reglas_evaluables, V3). */
    @Query("select r from ReglaAlerta r join fetch r.empresa "
            + "where (r.estado = 'ACTIVA' or r.pendienteEnvio = true) and (:usuarioId is null or r.usuarioId = :usuarioId)")
    List<ReglaAlerta> evaluables(Long usuarioId);
}
