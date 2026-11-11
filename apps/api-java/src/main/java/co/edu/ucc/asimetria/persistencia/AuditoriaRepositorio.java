package co.edu.ucc.asimetria.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AuditoriaRepositorio extends JpaRepository<Auditoria, Long> {

    List<Auditoria> findTop100ByUsuarioIdOrderByIdDesc(Long usuarioId);

    /** Retención de 90 días (RNF-04): borrado en bloque, sin cargar entidades en memoria. */
    @Modifying
    @Query("delete from Auditoria a where a.ts < :limite")
    int purgarAnteriores(Instant limite);
}
