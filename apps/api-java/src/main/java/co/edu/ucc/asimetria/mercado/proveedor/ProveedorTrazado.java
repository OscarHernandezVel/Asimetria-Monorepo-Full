package co.edu.ucc.asimetria.mercado.proveedor;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.dominio.mercado.Barra;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <b>Decorator</b> de trazabilidad: registra cada operación del proveedor envuelto en
 * {@code llamadas_externas} (operación, éxito, latencia, detalle sin secretos) y vuelve a lanzar
 * el error sin alterarlo. El proveedor concreto no sabe que lo están trazando.
 */
public final class ProveedorTrazado implements ProveedorMercado {

    private final ProveedorMercado base;
    private final AuditoriaService auditoria;

    public ProveedorTrazado(ProveedorMercado base, AuditoriaService auditoria) {
        this.base = base;
        this.auditoria = auditoria;
    }

    private <T> T trazar(String operacion, String detalleExito, Supplier<T> llamada) {
        long t0 = System.nanoTime();
        try {
            T r = llamada.get();
            auditoria.registrarLlamada(base.nombre(), operacion, true, 200, (System.nanoTime() - t0) / 1_000_000, detalleExito);
            return r;
        } catch (RuntimeException e) {
            auditoria.registrarLlamada(base.nombre(), operacion, false, null, (System.nanoTime() - t0) / 1_000_000, e.getMessage());
            throw e;
        }
    }

    @Override
    public String nombre() {
        return base.nombre();
    }

    @Override
    public List<EntradaCatalogo> catalogo() {
        return base.catalogo();
    }

    @Override
    public List<Barra> historico(String ticker, int dias) {
        return trazar("historico", ticker, () -> base.historico(ticker, dias));
    }

    @Override
    public Map<String, Double> cotizaciones(Map<String, Double> ultimos) {
        return trazar("cotizaciones", ultimos.size() + " tickers", () -> base.cotizaciones(ultimos));
    }

    @Override
    public Double dividendos(String ticker) {
        return trazar("dividendos", ticker, () -> base.dividendos(ticker));
    }
}
