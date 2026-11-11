package co.edu.ucc.asimetria.comun.estructuras;

import java.util.function.LongSupplier;

/**
 * Algoritmo de cubeta de fichas (token bucket) para limitar peticiones.
 *
 * <p>La cubeta tiene {@code capacidad} fichas (ráfaga permitida) y se rellena a {@code porSegundo}
 * fichas/s. Cada petición consume una; sin fichas, se rechaza (HTTP 429). A diferencia de una
 * ventana fija, no permite el doble de tráfico en el borde entre dos ventanas. O(1) por petición.
 */
public final class TokenBucket {

    private final double capacidad;
    private final double porNanosegundo;
    private final LongSupplier reloj;
    private double fichas;
    private long ultimaRecarga;

    public TokenBucket(double capacidad, double porSegundo, LongSupplier relojNanos) {
        if (capacidad <= 0 || porSegundo <= 0) {
            throw new IllegalArgumentException("Capacidad y tasa deben ser positivas");
        }
        this.capacidad = capacidad;
        this.porNanosegundo = porSegundo / 1_000_000_000d;
        this.reloj = relojNanos;
        this.fichas = capacidad;
        this.ultimaRecarga = relojNanos.getAsLong();
    }

    public TokenBucket(double capacidad, double porSegundo) {
        this(capacidad, porSegundo, System::nanoTime);
    }

    public synchronized boolean intentarConsumir() {
        long ahora = reloj.getAsLong();
        fichas = Math.min(capacidad, fichas + (ahora - ultimaRecarga) * porNanosegundo);
        ultimaRecarga = ahora;
        if (fichas >= 1) {
            fichas -= 1;
            return true;
        }
        return false;
    }
}
