package co.edu.ucc.asimetria.dominio.mercado;

import java.io.Serializable;

/** Precio actual de un activo y su variación frente al cierre anterior (en %). */
public record Cotizacion(double precio, double cierreAnterior, double variacion) implements Serializable {

    public static Cotizacion de(double precio, double cierreAnterior) {
        return new Cotizacion(precio, cierreAnterior, cierreAnterior == 0 ? 0 : (precio / cierreAnterior - 1) * 100);
    }
}
