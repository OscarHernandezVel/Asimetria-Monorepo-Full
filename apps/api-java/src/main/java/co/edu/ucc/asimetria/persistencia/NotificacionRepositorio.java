package co.edu.ucc.asimetria.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificacionRepositorio extends JpaRepository<Notificacion, Long> {

    List<Notificacion> findTop20ByUsuarioIdOrderByIdDesc(Long usuarioId);
}
