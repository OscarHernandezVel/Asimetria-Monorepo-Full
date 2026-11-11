package co.edu.ucc.asimetria.dominio.calculo;

import co.edu.ucc.asimetria.dominio.perfil.PerfilRiesgo;

import java.util.List;

/**
 * Motor de cálculo financiero (RF-03, HU-03, HU-07). Funciones puras sobre arreglos primitivos:
 * sin base de datos ni red, por lo que se prueban de forma aislada y son reutilizables.
 */
public final class MotorCalculo {

    public static final int MIN_OBSERVACIONES = 20;
    public static final double TASA_LIBRE_RIESGO = 4.0;
    public static final String ERROR_DATOS = "Datos insuficientes para calcular la beta";

    private MotorCalculo() {
    }

    /** Retornos simples entre precios consecutivos: p[i]/p[i-1] - 1. */
    public static double[] retornos(double[] precios) {
        if (precios.length < 2) {
            return new double[0];
        }
        double[] r = new double[precios.length - 1];
        for (int i = 1; i < precios.length; i++) {
            r[i - 1] = precios[i] / precios[i - 1] - 1;
        }
        return r;
    }

    /** Beta por regresión al origen Σ(ra·rm)/Σ(rm²). Con un dato equivale a ra/rm (caso RiTech). */
    public static Double beta(double[] ra, double[] rm) {
        double num = 0, den = 0;
        for (int i = 0; i < rm.length; i++) {
            num += ra[i] * rm[i];
            den += rm[i] * rm[i];
        }
        return den == 0 ? null : num / den;
    }

    /**
     * HU-03: beta alcista (sesiones en que el índice sube) y bajista (en que baja).
     *
     * @throws DatosInsuficientesException si algún régimen tiene menos de {@code minimo} sesiones (CA-4)
     */
    public static ResultadoBeta betaAsimetrica(double[] ra, double[] rm, int minimo) {
        if (ra.length != rm.length) {
            throw new DatosInsuficientesException(ERROR_DATOS);
        }
        int nUp = 0, nDown = 0;
        for (double m : rm) {
            if (m > 0) {
                nUp++;
            } else if (m < 0) {
                nDown++;
            }
        }
        if (nUp < minimo || nDown < minimo) {
            throw new DatosInsuficientesException(ERROR_DATOS);
        }
        double[] aUp = new double[nUp], mUp = new double[nUp], aDown = new double[nDown], mDown = new double[nDown];
        int u = 0, d = 0;
        for (int i = 0; i < rm.length; i++) {
            if (rm[i] > 0) {
                aUp[u] = ra[i];
                mUp[u++] = rm[i];
            } else if (rm[i] < 0) {
                aDown[d] = ra[i];
                mDown[d++] = rm[i];
            }
        }
        double alcista = beta(aUp, mUp);
        double bajista = beta(aDown, mDown);
        return new ResultadoBeta(alcista, bajista, beta(ra, rm), bajista == 0 ? null : alcista / bajista);
    }

    public static double media(double[] x) {
        if (x.length == 0) {
            return 0;
        }
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s / x.length;
    }

    /** Desviación estándar muestral anualizada (√252), en %. */
    public static double volatilidad(double[] r) {
        if (r.length < 2) {
            return 0;
        }
        double m = media(r), s = 0;
        for (double v : r) {
            s += (v - m) * (v - m);
        }
        return Math.sqrt(s / (r.length - 1)) * Math.sqrt(252) * 100;
    }

    /** Correlación de Pearson; {@code null} si no hay variación o datos. */
    public static Double correlacion(double[] a, double[] b) {
        if (a.length < 2 || a.length != b.length) {
            return null;
        }
        double ma = media(a), mb = media(b), n = 0, da = 0, db = 0;
        for (int i = 0; i < a.length; i++) {
            n += (a[i] - ma) * (b[i] - mb);
            da += (a[i] - ma) * (a[i] - ma);
            db += (b[i] - mb) * (b[i] - mb);
        }
        return (da == 0 || db == 0) ? null : n / Math.sqrt(da * db);
    }

    /** Máxima caída desde un pico, en % (valor ≤ 0). */
    public static double maxDrawdown(double[] precios) {
        double pico = Double.NEGATIVE_INFINITY, peor = 0;
        for (double p : precios) {
            pico = Math.max(pico, p);
            peor = Math.min(peor, p / pico - 1);
        }
        return peor * 100;
    }

    public static String nivelVolatilidad(double vol) {
        return vol < 20 ? "Baja" : vol < 35 ? "Media" : "Alta";
    }

    /** Proyección anual (%) por CAPM: rf + β·(E[Rm] − rf), con E[Rm] histórico anualizado del índice. */
    public static Double retornoEsperadoCapm(Double betaGlobal, double[] rm) {
        if (betaGlobal == null || rm.length == 0) {
            return null;
        }
        double mercado = media(rm) * 252 * 100;
        return TASA_LIBRE_RIESGO + betaGlobal * (mercado - TASA_LIBRE_RIESGO);
    }

    /** HU-07 CA-3: promedio de respuestas (1–3) → perfil. */
    public static PerfilRiesgo perfilDesdeQuiz(List<Integer> puntos) {
        if (puntos == null || puntos.isEmpty() || puntos.stream().anyMatch(p -> p == null || p < 1 || p > 3)) {
            throw new IllegalArgumentException("Responde todas las preguntas del quiz");
        }
        double prom = puntos.stream().mapToInt(Integer::intValue).average().orElse(0);
        return prom < 1.67 ? PerfilRiesgo.CONSERVADOR : prom < 2.34 ? PerfilRiesgo.MODERADO : PerfilRiesgo.AGRESIVO;
    }
}
