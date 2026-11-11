package co.edu.ucc.asimetria.mercado;

import co.edu.ucc.asimetria.dominio.mercado.Barra;
import co.edu.ucc.asimetria.mercado.Vistas.CatalogoActualizado;
import co.edu.ucc.asimetria.mercado.proveedor.ErrorProveedor;
import co.edu.ucc.asimetria.mercado.proveedor.ProveedorMercado;
import co.edu.ucc.asimetria.mercado.proveedor.ProveedorSimulado;
import co.edu.ucc.asimetria.persistencia.Empresa;
import co.edu.ucc.asimetria.persistencia.EmpresaRepositorio;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ingesta de datos (RF-02, HU-02): carga inicial de 5 años y cierre diario sin duplicados. */
@Service
public class IngestaService {

    private static final Logger log = LoggerFactory.getLogger(IngestaService.class);
    public static final String FUENTE_VIVO = "COTIZACION_VIVO";

    private final ProveedorMercado proveedor;
    private final EmpresaRepositorio empresas;
    private final PreciosRepositorio precios;
    private final MetricasService metricas;
    private final CotizacionService cotizaciones;
    private final ApplicationEventPublisher eventos;

    public IngestaService(ProveedorMercado proveedor, EmpresaRepositorio empresas, PreciosRepositorio precios,
                          MetricasService metricas, CotizacionService cotizaciones, ApplicationEventPublisher eventos) {
        this.proveedor = proveedor;
        this.empresas = empresas;
        this.precios = precios;
        this.metricas = metricas;
        this.cotizaciones = cotizaciones;
        this.eventos = eventos;
    }

    /** HU-02 CA-1: catálogo + 5 años de historia si la base está vacía. Idempotente. */
    public boolean cargaInicial() {
        if (empresas.count() > 0) {
            return false;
        }
        Map<String, Double> dividendos = new HashMap<>();
        for (ProveedorMercado.EntradaCatalogo c : proveedor.catalogo()) {
            Empresa e = empresas.save(new Empresa(c.ticker(), c.nombre(), c.sector(), "^NDX".equals(c.ticker())));
            precios.insertarLote(e.getId(), proveedor.historico(c.ticker(), ProveedorSimulado.DIAS_HISTORIA), proveedor.nombre());
            dividendos.put(c.ticker(), proveedor.dividendos(c.ticker()));
        }
        metricas.recalcular(dividendos);
        eventos.publishEvent(new CatalogoActualizado());
        log.info("Carga inicial completada: {} activos", dividendos.size());
        return true;
    }

    /** HU-02 CA-2/CA-3: guarda el cierre del día (sin duplicar) y recalcula métricas. CA-4: si falla, no daña lo guardado. */
    public int cierreDiario(LocalDate hoy) {
        if (hoy.getDayOfWeek() == DayOfWeek.SATURDAY || hoy.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return 0;
        }
        int nuevas = 0;
        for (Empresa e : empresas.findByActivaTrueOrderById()) {
            List<Barra> barras;
            String fuente;
            if (ProveedorSimulado.NOMBRE.equals(proveedor.nombre())) {
                Optional<Double> vivo = cotizaciones.precioVivo(e.getTicker());
                if (vivo.isEmpty()) {
                    continue;
                }
                double p = vivo.get();
                barras = List.of(new Barra(hoy, p, p, p, p, 0));
                fuente = FUENTE_VIVO;
            } else {
                try {
                    barras = proveedor.historico(e.getTicker(), 5);
                    fuente = proveedor.nombre();
                } catch (ErrorProveedor err) {
                    log.warn("Cierre diario de {} omitido: {}", e.getTicker(), err.getMessage());
                    continue;
                }
            }
            nuevas += precios.insertarLote(e.getId(), barras, fuente);
        }
        metricas.recalcular(null);
        return nuevas;
    }
}
