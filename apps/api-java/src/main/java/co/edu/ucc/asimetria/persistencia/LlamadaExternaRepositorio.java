package co.edu.ucc.asimetria.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface LlamadaExternaRepositorio extends JpaRepository<LlamadaExterna, Long> {

    List<LlamadaExterna> findTop30ByOrderByIdDesc();

    List<LlamadaExterna> findByProveedorAndTsAfterOrderByTs(String proveedor, Instant desde);

    @Modifying
    @Query("delete from LlamadaExterna l where l.ts < :limite")
    int purgarAnteriores(Instant limite);
}
