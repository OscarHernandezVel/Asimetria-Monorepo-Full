package co.edu.ucc.asimetria.dominio.calculo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Resultado de un backtesting (HU-05), inmutable. Se construye con {@link Builder} (patrón
 * Builder): los puntos se añaden uno a uno y {@link Builder#construir()} valida las invariantes
 * (monto positivo, al menos dos sesiones) y deriva los retornos, de modo que nunca existe un
 * resultado a medio calcular.
 */
public record ResultadoSimulacion(double montoInicial, List<Punto> serie, double valorFinal, double valorIndice,
                                  double retorno, double retornoIndice) {

    public record Punto(LocalDate fecha, double portafolio, double indice) {
    }

    public static Builder builder(double montoInicial) {
        return new Builder(montoInicial);
    }

    public static final class Builder {
        private final double monto;
        private final List<Punto> puntos = new ArrayList<>();
        private Double baseActivo;
        private Double baseIndice;

        private Builder(double monto) {
            if (!(monto > 0) || Double.isInfinite(monto)) {
                throw new IllegalArgumentException("El monto inicial debe ser mayor que 0");
            }
            this.monto = monto;
        }

        /** Añade la sesión; el primer precio fija la base de compra (comprar y mantener). */
        public Builder sesion(LocalDate fecha, double precioActivo, double precioIndice) {
            if (baseActivo == null) {
                baseActivo = precioActivo;
                baseIndice = precioIndice;
            }
            puntos.add(new Punto(fecha, redondear(monto * precioActivo / baseActivo), redondear(monto * precioIndice / baseIndice)));
            return this;
        }

        public ResultadoSimulacion construir() {
            if (puntos.size() < 2) {
                throw new IllegalArgumentException("No hay datos históricos para el periodo seleccionado");
            }
            Punto fin = puntos.get(puntos.size() - 1);
            return new ResultadoSimulacion(monto, List.copyOf(puntos), fin.portafolio(), fin.indice(),
                    (fin.portafolio() / monto - 1) * 100, (fin.indice() / monto - 1) * 100);
        }

        private static double redondear(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
