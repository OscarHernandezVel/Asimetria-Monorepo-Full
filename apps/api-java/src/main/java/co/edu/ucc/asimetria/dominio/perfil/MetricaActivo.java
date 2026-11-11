package co.edu.ucc.asimetria.dominio.perfil;

/** Datos mínimos de un activo que necesita el motor de recomendación (sin dependencias de JPA). */
public record MetricaActivo(String ticker, double betaAlcista, double betaBajista, double volatilidad,
                            double retornoEsperado) {

    public double ratioRiesgoBeneficio() {
        return volatilidad == 0 ? 0 : retornoEsperado / volatilidad;
    }
}
