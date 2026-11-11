package co.edu.ucc.asimetria.web;

import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.comun.estructuras.SerieTemporal;
import co.edu.ucc.asimetria.dominio.perfil.MotorRecomendacion.Filtros;
import co.edu.ucc.asimetria.dominio.perfil.PerfilRiesgo;
import co.edu.ucc.asimetria.mercado.AnalisisService;
import co.edu.ucc.asimetria.mercado.BusquedaService;
import co.edu.ucc.asimetria.mercado.CotizacionService;
import co.edu.ucc.asimetria.mercado.MetricasService;
import co.edu.ucc.asimetria.mercado.RecomendacionService;
import co.edu.ucc.asimetria.mercado.Vistas.AnalisisVista;
import co.edu.ucc.asimetria.mercado.Vistas.CotizacionesVista;
import co.edu.ucc.asimetria.mercado.Vistas.DestacadoVista;
import co.edu.ucc.asimetria.mercado.Vistas.EmpresaVista;
import co.edu.ucc.asimetria.mercado.Vistas.MetricaVista;
import co.edu.ucc.asimetria.mercado.Vistas.RecomendacionVista;
import co.edu.ucc.asimetria.mercado.Vistas.SerieVista;
import co.edu.ucc.asimetria.mercado.Vistas.Tick;
import co.edu.ucc.asimetria.persistencia.Empresa;
import co.edu.ucc.asimetria.persistencia.EmpresaRepositorio;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio;
import co.edu.ucc.asimetria.persistencia.Usuario;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Mercado, métricas y recomendaciones (RF-01, RF-03, RF-04, RF-09, RF-10). */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Mercado y métricas")
public class MercadoController {

    private final EmpresaRepositorio empresas;
    private final PreciosRepositorio precios;
    private final CotizacionService cotizaciones;
    private final MetricasService metricas;
    private final AnalisisService analisis;
    private final RecomendacionService recomendaciones;
    private final BusquedaService busqueda;

    public MercadoController(EmpresaRepositorio empresas, PreciosRepositorio precios, CotizacionService cotizaciones,
                             MetricasService metricas, AnalisisService analisis, RecomendacionService recomendaciones,
                             BusquedaService busqueda) {
        this.empresas = empresas;
        this.precios = precios;
        this.cotizaciones = cotizaciones;
        this.metricas = metricas;
        this.analisis = analisis;
        this.recomendaciones = recomendaciones;
        this.busqueda = busqueda;
    }

    private Empresa empresa(String ticker) {
        return empresas.findByTickerAndActivaTrue(ticker.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.noEncontrado("Empresa no encontrada"));
    }

    @GetMapping("/mercado/empresas")
    public List<EmpresaVista> empresas() {
        return empresas.findByActivaTrueOrderById().stream()
                .map(e -> new EmpresaVista(e.getTicker(), e.getNombreEmpresa(), e.getSector(), e.isEsIndice())).toList();
    }

    @GetMapping("/mercado/precios/{ticker}")
    public SerieVista precios(@PathVariable String ticker, @RequestParam(defaultValue = "252") @Min(1) @Max(5000) int dias) {
        Empresa e = empresa(ticker);
        SerieTemporal s = precios.ultimas(e.getId(), dias + 1);
        return new SerieVista(e.getTicker(), s.fechas(), Arrays.stream(s.valores()).boxed().toList());
    }

    @GetMapping("/mercado/cotizaciones")
    @Operation(summary = "Últimos precios; si el proveedor no responde, estado.desactualizado = true (nunca 500)")
    public CotizacionesVista cotizaciones() {
        return cotizaciones.actuales();
    }

    @GetMapping("/mercado/intradia/{ticker}")
    @Operation(summary = "Últimos ticks en vivo (buffer circular de 120 posiciones)")
    public List<Tick> intradia(@PathVariable String ticker) {
        return cotizaciones.intradia(empresa(ticker).getTicker());
    }

    @GetMapping("/mercado/buscar")
    @Operation(summary = "Búsqueda por prefijo de ticker, nombre o sector (trie)")
    public List<EmpresaVista> buscar(@RequestParam String q, @RequestParam(defaultValue = "8") @Min(1) @Max(50) int limite) {
        return busqueda.buscar(q, limite);
    }

    @GetMapping({"/metrics", "/metrics/"})
    public List<MetricaVista> metricas() {
        return metricas.listado();
    }

    @GetMapping("/metrics/{ticker}")
    public MetricaVista metrica(@PathVariable String ticker) {
        String t = ticker.toUpperCase(Locale.ROOT);
        return metricas.listado().stream().filter(m -> m.ticker().equals(t)).findFirst()
                .orElseThrow(() -> ApiException.noEncontrado("Empresa no encontrada"));
    }

    @GetMapping("/metrics/{ticker}/analisis")
    public AnalisisVista analisis(@PathVariable String ticker, @RequestParam(defaultValue = "90") @Min(10) @Max(1260) int ventana) {
        return analisis.analisis(empresa(ticker).getTicker(), ventana);
    }

    @GetMapping({"/recommendations", "/recommendations/"})
    @Operation(summary = "HU-04: 409 si falta el quiz; lista vacía si nada cumple los filtros")
    public List<RecomendacionVista> recomendaciones(Usuario u,
                                                    @RequestParam(name = "vol_max", required = false) Double volMax,
                                                    @RequestParam(name = "retorno_min", required = false) Double retornoMin,
                                                    @RequestParam(name = "ratio_min", required = false) Double ratioMin) {
        return recomendaciones.recomendar(PerfilRiesgo.desde(u.getPerfilRiesgo()), new Filtros(volMax, retornoMin, ratioMin, 50));
    }

    @GetMapping("/destacado")
    @Operation(summary = "Público: activo con mayor asimetría favorable (tarjeta de la landing)")
    public DestacadoVista destacado() {
        return analisis.destacado();
    }
}
