package co.edu.ucc.asimetria.dominio.mercado;

import java.time.LocalDate;

/** Vela diaria (OHLCV) en el formato interno, independiente del proveedor (ver adaptadores). */
public record Barra(LocalDate fecha, double apertura, double maximo, double minimo, double cierre, long volumen) {
}
