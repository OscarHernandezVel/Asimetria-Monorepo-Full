package co.edu.ucc.asimetria.mercado.proveedor;

import co.edu.ucc.asimetria.dominio.mercado.Barra;

import java.util.List;
import java.util.Map;

/**
 * Puerto de datos de mercado (RF-01). Los servicios solo conocen esta interfaz; las
 * implementaciones concretas (simulado, Alpha Vantage) y los decoradores (trazabilidad,
 * resiliencia) se ensamblan en {@link FabricaProveedores}.
 */
public interface ProveedorMercado {

    record EntradaCatalogo(String ticker, String nombre, String sector) {
    }

    /** Identificador de procedencia que se guarda en cada precio (columna {@code fuente}). */
    String nombre();

    List<EntradaCatalogo> catalogo();

    /** Últimas {@code dias} sesiones (más la de referencia inicial), en orden cronológico. */
    List<Barra> historico(String ticker, int dias);

    /** Precio actual de cada ticker, a partir del último conocido. */
    Map<String, Double> cotizaciones(Map<String, Double> ultimos);

    /** Rendimiento por dividendos anual (%), o {@code null} si no aplica (índices). */
    Double dividendos(String ticker);
}
