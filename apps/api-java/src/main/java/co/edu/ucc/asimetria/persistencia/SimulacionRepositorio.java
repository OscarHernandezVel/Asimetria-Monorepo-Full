package co.edu.ucc.asimetria.persistencia;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SimulacionRepositorio extends JpaRepository<SimulacionPortafolio, Long> {

    @Query("select s from SimulacionPortafolio s join fetch s.empresa where s.usuarioId = :usuarioId order by s.id desc")
    List<SimulacionPortafolio> recientes(Long usuarioId, Limit limite);
}
