package co.edu.ucc.asimetria.dominio.perfil;

/**
 * Perfil de riesgo del inversionista (HU-07) implementado como <b>Strategy</b>: cada perfil
 * decide qué activos admite (techo de volatilidad, HU-04 CA-1) y cómo los prioriza (CA-3).
 *
 * <ul>
 *   <li>Conservador: protege a la baja; premia la beta bajista pequeña.</li>
 *   <li>Moderado: equilibrio; premia la asimetría β↑/β↓.</li>
 *   <li>Agresivo: busca captura al alza; premia la beta alcista grande.</li>
 * </ul>
 * En todos los casos la base es el retorno ajustado a riesgo (retorno esperado / volatilidad).
 */
public enum PerfilRiesgo {

    CONSERVADOR(20.0) {
        @Override
        public double puntuar(MetricaActivo m) {
            return m.ratioRiesgoBeneficio() / Math.max(m.betaBajista(), 0.05);
        }
    },
    MODERADO(35.0) {
        @Override
        public double puntuar(MetricaActivo m) {
            return m.ratioRiesgoBeneficio() * (m.betaAlcista() / Math.max(m.betaBajista(), 0.05));
        }
    },
    AGRESIVO(Double.POSITIVE_INFINITY) {
        @Override
        public double puntuar(MetricaActivo m) {
            return m.ratioRiesgoBeneficio() * m.betaAlcista();
        }
    };

    private final double volatilidadMaxima;

    PerfilRiesgo(double volatilidadMaxima) {
        this.volatilidadMaxima = volatilidadMaxima;
    }

    public double volatilidadMaxima() {
        return volatilidadMaxima;
    }

    public boolean admite(MetricaActivo m) {
        return m.volatilidad() <= volatilidadMaxima;
    }

    /** Mayor puntaje = mejor oportunidad para este perfil. */
    public abstract double puntuar(MetricaActivo m);

    /** Conversión segura desde texto; {@code null} si el usuario aún no tiene perfil. */
    public static PerfilRiesgo desde(String valor) {
        return valor == null ? null : PerfilRiesgo.valueOf(valor);
    }
}
