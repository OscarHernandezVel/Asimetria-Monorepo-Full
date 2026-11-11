package co.edu.ucc.asimetria.dominio.perfil;

import co.edu.ucc.asimetria.comun.estructuras.TopK;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Motor de recomendación (RF-04, HU-04): filtra por el perfil (Strategy) y por los filtros del
 * usuario, y prioriza con un montículo {@link TopK} según el puntaje propio del perfil.
 */
public final class MotorRecomendacion {

    public record Filtros(Double volatilidadMaxima, Double retornoMinimo, Double ratioMinimo, int limite) {
        public static Filtros ninguno() {
            return new Filtros(null, null, null, 50);
        }
    }

    public record Recomendacion(MetricaActivo activo, double ratioRiesgoBeneficio, double puntaje) {
    }

    private MotorRecomendacion() {
    }

    /**
     * @param perfil {@code null} si el usuario no completó el quiz
     * @throws PerfilRequeridoException HU-04 CA-5
     * @return lista ordenada por puntaje; vacía si nada cumple (CA-4)
     */
    public static List<Recomendacion> recomendar(List<MetricaActivo> activos, PerfilRiesgo perfil, Filtros f) {
        if (perfil == null) {
            throw new PerfilRequeridoException("Completa el quiz de perfil de riesgo para ver recomendaciones");
        }
        Predicate<MetricaActivo> cumple = perfil::admite;
        if (f.volatilidadMaxima() != null) {
            cumple = cumple.and(m -> m.volatilidad() <= f.volatilidadMaxima());
        }
        if (f.retornoMinimo() != null) {
            cumple = cumple.and(m -> m.retornoEsperado() >= f.retornoMinimo());
        }
        if (f.ratioMinimo() != null) {
            cumple = cumple.and(m -> m.ratioRiesgoBeneficio() >= f.ratioMinimo());
        }
        List<Recomendacion> candidatos = activos.stream()
                .filter(m -> m.volatilidad() > 0)
                .filter(cumple)
                .map(m -> new Recomendacion(m, m.ratioRiesgoBeneficio(), perfil.puntuar(m)))
                .toList();
        return TopK.de(candidatos, Math.max(1, f.limite()), Comparator.comparingDouble(Recomendacion::puntaje));
    }

    /** HU-04 CA-5: el usuario debe definir su perfil antes de recibir recomendaciones. */
    public static class PerfilRequeridoException extends RuntimeException {
        public PerfilRequeridoException(String mensaje) {
            super(mensaje);
        }
    }
}
