package co.edu.ucc.asimetria.dominio.alerta;

import co.edu.ucc.asimetria.dominio.mercado.Cotizacion;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Implementaciones de {@link CondicionAlerta} y su <b>Factory</b>.
 *
 * <p>La fábrica es un registro {@link EnumMap} (búsqueda O(1) por tipo): el evaluador de alertas
 * pide {@code Condiciones.de(tipo)} sin conocer las clases concretas, y agregar un tipo nuevo no
 * toca el evaluador (principio abierto/cerrado).
 */
public final class Condiciones {

    /** |variación diaria| &gt; umbral %: detecta movimientos bruscos al alza o a la baja. */
    static final class VariacionPorcentual implements CondicionAlerta {
        @Override
        public TipoCondicion tipo() {
            return TipoCondicion.VARIACION_PORCENTUAL;
        }

        @Override
        public boolean seCumple(Cotizacion c, double umbral) {
            return Math.abs(c.variacion()) > umbral;
        }

        @Override
        public String describir(String activo, Cotizacion c, double umbral) {
            return String.format(Locale.US, "%s varió %+.2f%% (umbral %s%%)", activo, c.variacion(), sinCeros(umbral));
        }
    }

    /** precio ≥ límite. */
    static final class PrecioLimite implements CondicionAlerta {
        @Override
        public TipoCondicion tipo() {
            return TipoCondicion.PRECIO_LIMITE;
        }

        @Override
        public boolean seCumple(Cotizacion c, double umbral) {
            return c.precio() >= umbral;
        }

        @Override
        public String describir(String activo, Cotizacion c, double umbral) {
            return String.format(Locale.US, "%s alcanzó $%,.2f", activo, c.precio());
        }
    }

    private static final Map<TipoCondicion, CondicionAlerta> REGISTRO;

    static {
        Map<TipoCondicion, CondicionAlerta> m = new EnumMap<>(TipoCondicion.class);
        for (CondicionAlerta c : new CondicionAlerta[]{new VariacionPorcentual(), new PrecioLimite()}) {
            m.put(c.tipo(), c);
        }
        REGISTRO = Collections.unmodifiableMap(m);
    }

    private Condiciones() {
    }

    public static CondicionAlerta de(TipoCondicion tipo) {
        CondicionAlerta c = REGISTRO.get(tipo);
        if (c == null) {
            throw new IllegalArgumentException("Tipo de condición no soportado: " + tipo);
        }
        return c;
    }

    public static CondicionAlerta de(String tipo) {
        return de(TipoCondicion.valueOf(tipo));
    }

    private static String sinCeros(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
