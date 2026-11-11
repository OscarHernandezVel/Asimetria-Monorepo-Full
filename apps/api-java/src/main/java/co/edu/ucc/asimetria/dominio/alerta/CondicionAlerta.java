package co.edu.ucc.asimetria.dominio.alerta;

import co.edu.ucc.asimetria.dominio.mercado.Cotizacion;

/** Strategy: regla que decide si una alerta se dispara con la cotización actual (HU-06). */
public interface CondicionAlerta {

    TipoCondicion tipo();

    boolean seCumple(Cotizacion cotizacion, double umbral);

    /** Texto de la notificación para el usuario. */
    String describir(String activo, Cotizacion cotizacion, double umbral);
}
