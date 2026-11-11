package co.edu.ucc.asimetria.mercado;

import co.edu.ucc.asimetria.dominio.perfil.MetricaActivo;
import co.edu.ucc.asimetria.dominio.perfil.MotorRecomendacion;
import co.edu.ucc.asimetria.dominio.perfil.MotorRecomendacion.Filtros;
import co.edu.ucc.asimetria.dominio.perfil.PerfilRiesgo;
import co.edu.ucc.asimetria.mercado.Vistas.MetricaVista;
import co.edu.ucc.asimetria.mercado.Vistas.RecomendacionVista;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** RF-04: adapta las métricas al motor de recomendación del dominio y cachea por perfil + filtros. */
@Service
public class RecomendacionService {

    private final MetricasService metricas;

    public RecomendacionService(MetricasService metricas) {
        this.metricas = metricas;
    }

    /** La excepción por perfil nulo (HU-04 CA-5) no se cachea: Spring solo guarda resultados. */
    @Cacheable(value = "recomendaciones", key = "#perfil + ':' + #filtros")
    public List<RecomendacionVista> recomendar(PerfilRiesgo perfil, Filtros filtros) {
        List<MetricaVista> vistas = metricas.listado();
        Map<String, MetricaVista> porTicker = vistas.stream().collect(Collectors.toMap(MetricaVista::ticker, Function.identity()));
        List<MetricaActivo> activos = vistas.stream()
                .filter(v -> v.betaAlcista() != null && v.betaBajista() != null && v.retornoEsperado() != null)   // HU-03 CA-4
                .map(v -> new MetricaActivo(v.ticker(), v.betaAlcista(), v.betaBajista(), v.volatilidad(), v.retornoEsperado()))
                .toList();
        return MotorRecomendacion.recomendar(activos, perfil, filtros).stream()
                .map(r -> new RecomendacionVista(porTicker.get(r.activo().ticker()), r.ratioRiesgoBeneficio(), r.puntaje()))
                .toList();
    }
}
