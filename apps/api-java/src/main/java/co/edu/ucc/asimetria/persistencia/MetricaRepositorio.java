package co.edu.ucc.asimetria.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MetricaRepositorio extends JpaRepository<MetricaEmpresa, Long> {

    Optional<MetricaEmpresa> findByEmpresaId(Long empresaId);
}
