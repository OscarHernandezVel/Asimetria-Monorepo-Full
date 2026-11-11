package co.edu.ucc.asimetria.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ListaSeguimientoRepositorio extends JpaRepository<ListaSeguimiento, Long> {

    /** JOIN FETCH: una sola consulta en vez de una por empresa (evita N+1). */
    @Query("select l from ListaSeguimiento l join fetch l.empresa where l.usuarioId = :usuarioId order by l.id")
    List<ListaSeguimiento> listar(Long usuarioId);

    @Query("select l from ListaSeguimiento l join fetch l.empresa e where l.usuarioId = :usuarioId and e.ticker = :ticker")
    Optional<ListaSeguimiento> buscar(Long usuarioId, String ticker);

    boolean existsByUsuarioIdAndEmpresaId(Long usuarioId, Long empresaId);
}
