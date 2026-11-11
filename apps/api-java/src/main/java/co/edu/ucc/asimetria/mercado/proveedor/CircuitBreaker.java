package co.edu.ucc.asimetria.mercado.proveedor;

import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;

import java.time.Clock;
import java.time.Duration;

/**
 * Circuit breaker (RNF-06) como máquina de estados, con estado en {@link AlmacenCompartido} para que
 * todas las instancias de la API lo compartan:
 *
 * <pre>
 *  CERRADO ──(umbral de fallos)──▶ ABIERTO ──(pasa la espera)──▶ SEMIABIERTO
 *     ▲                                ▲                              │
 *     └──────────── éxito ─────────────┼────────── fallo ─────────────┘
 * </pre>
 * En ABIERTO no se llama al proveedor (se protege su cuota y nuestra latencia); en SEMIABIERTO se
 * permite una prueba: un fallo lo reabre de inmediato y un éxito lo cierra.
 */
public final class CircuitBreaker {

    public enum Estado { CERRADO, ABIERTO, SEMIABIERTO }

    private final AlmacenCompartido almacen;
    private final String claveFallos;
    private final String claveAbierto;
    private final int umbral;
    private final Duration espera;
    private final Clock reloj;

    public CircuitBreaker(AlmacenCompartido almacen, String nombre, int umbral, Duration espera, Clock reloj) {
        this.almacen = almacen;
        this.claveFallos = "cb:" + nombre + ":fallos";
        this.claveAbierto = "cb:" + nombre + ":abierto";
        this.umbral = umbral;
        this.espera = espera;
        this.reloj = reloj;
    }

    public Estado estado() {
        if (almacen.obtener(claveAbierto).isPresent()) {
            return Estado.ABIERTO;
        }
        long fallos = almacen.obtener(claveFallos).map(Long::parseLong).orElse(0L);
        return fallos >= umbral ? Estado.SEMIABIERTO : Estado.CERRADO;
    }

    public boolean permiteLlamar() {
        return estado() != Estado.ABIERTO;
    }

    public void registrarExito() {
        almacen.eliminar(claveFallos);
    }

    public void registrarFallo() {
        long fallos = almacen.incrementar(claveFallos, Duration.ofMinutes(10));
        if (fallos >= umbral) {
            almacen.guardar(claveAbierto, String.valueOf(reloj.millis()), espera);
        }
    }
}
