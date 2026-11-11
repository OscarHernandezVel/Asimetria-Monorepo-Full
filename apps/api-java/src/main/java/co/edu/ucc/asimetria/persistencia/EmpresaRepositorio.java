package co.edu.ucc.asimetria.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmpresaRepositorio extends JpaRepository<Empresa, Long> {

    Optional<Empresa> findByTickerAndActivaTrue(String ticker);

    List<Empresa> findByActivaTrueOrderById();

    Optional<Empresa> findFirstByEsIndiceTrueAndActivaTrue();
}
