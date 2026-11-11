package co.edu.ucc.asimetria.comun.estructuras;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Serie de precios de cierre indexada por fecha sobre un árbol rojo-negro ({@link TreeMap}).
 *
 * <ul>
 *   <li>{@link #rango}: sesiones entre dos fechas en O(log n + r) con {@code subMap}, sin recorrer
 *       toda la historia (backtesting, RF-05).</li>
 *   <li>{@link #alinear}: une activo e índice en las fechas comunes con un recorrido de mezcla
 *       sobre claves ya ordenadas, O(n + m). Un activo que empezó a cotizar después (salida a
 *       bolsa reciente) queda alineado solo en sus sesiones reales (RF-03).</li>
 * </ul>
 */
public final class SerieTemporal {

    private final NavigableMap<LocalDate, Double> puntos;

    public SerieTemporal() {
        this(new TreeMap<>());
    }

    private SerieTemporal(NavigableMap<LocalDate, Double> puntos) {
        this.puntos = puntos;
    }

    public SerieTemporal agregar(LocalDate fecha, double valor) {
        puntos.put(fecha, valor);
        return this;
    }

    public int tamano() {
        return puntos.size();
    }

    public boolean vacia() {
        return puntos.isEmpty();
    }

    public List<LocalDate> fechas() {
        return new ArrayList<>(puntos.keySet());
    }

    public double[] valores() {
        return puntos.values().stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Sesiones dentro de [desde, hasta], ambos inclusive. O(log n + r). */
    public SerieTemporal rango(LocalDate desde, LocalDate hasta) {
        if (hasta.isBefore(desde)) {
            return new SerieTemporal();
        }
        return new SerieTemporal(new TreeMap<>(puntos.subMap(desde, true, hasta, true)));
    }

    /** Las últimas {@code n} sesiones. O(n). */
    public SerieTemporal ultimas(int n) {
        TreeMap<LocalDate, Double> copia = new TreeMap<>();
        Iterator<Map.Entry<LocalDate, Double>> it = puntos.descendingMap().entrySet().iterator();
        for (int i = 0; i < n && it.hasNext(); i++) {
            Map.Entry<LocalDate, Double> e = it.next();
            copia.put(e.getKey(), e.getValue());
        }
        return new SerieTemporal(copia);
    }

    /** Resultado de alinear dos series: mismas fechas, mismo largo. */
    public record Alineadas(List<LocalDate> fechas, double[] a, double[] b) {
        public int tamano() {
            return fechas.size();
        }
    }

    /** Intersección por fecha mediante mezcla de dos secuencias ordenadas. O(n + m). */
    public Alineadas alinear(SerieTemporal otra) {
        List<LocalDate> fechas = new ArrayList<>();
        List<Double> va = new ArrayList<>();
        List<Double> vb = new ArrayList<>();
        Iterator<Map.Entry<LocalDate, Double>> ia = puntos.entrySet().iterator();
        Iterator<Map.Entry<LocalDate, Double>> ib = otra.puntos.entrySet().iterator();
        Map.Entry<LocalDate, Double> ea = ia.hasNext() ? ia.next() : null;
        Map.Entry<LocalDate, Double> eb = ib.hasNext() ? ib.next() : null;
        while (ea != null && eb != null) {
            int cmp = ea.getKey().compareTo(eb.getKey());
            if (cmp == 0) {
                fechas.add(ea.getKey());
                va.add(ea.getValue());
                vb.add(eb.getValue());
                ea = ia.hasNext() ? ia.next() : null;
                eb = ib.hasNext() ? ib.next() : null;
            } else if (cmp < 0) {
                ea = ia.hasNext() ? ia.next() : null;
            } else {
                eb = ib.hasNext() ? ib.next() : null;
            }
        }
        return new Alineadas(fechas, va.stream().mapToDouble(Double::doubleValue).toArray(),
                vb.stream().mapToDouble(Double::doubleValue).toArray());
    }
}
