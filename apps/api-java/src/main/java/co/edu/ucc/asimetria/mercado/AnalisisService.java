package co.edu.ucc.asimetria.mercado;

import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.comun.estructuras.SerieTemporal;
import co.edu.ucc.asimetria.dominio.calculo.MotorCalculo;
import co.edu.ucc.asimetria.mercado.Vistas.AnalisisVista;
import co.edu.ucc.asimetria.mercado.Vistas.DestacadoVista;
import co.edu.ucc.asimetria.mercado.Vistas.DiaDesglose;
import co.edu.ucc.asimetria.mercado.Vistas.Desglose;
import co.edu.ucc.asimetria.mercado.Vistas.MetricaVista;
import co.edu.ucc.asimetria.mercado.Vistas.PromedioDesglose;
import co.edu.ucc.asimetria.persistencia.Empresa;
import co.edu.ucc.asimetria.persistencia.EmpresaRepositorio;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Vista «NASDAQ 100 vs activo» (HU-03, HU-09): base 100, retornos, drawdown, dispersión y desglose. */
@Service
public class AnalisisService {

    private final EmpresaRepositorio empresas;
    private final PreciosRepositorio precios;
    private final MetricasService metricas;

    public AnalisisService(EmpresaRepositorio empresas, PreciosRepositorio precios, MetricasService metricas) {
        this.empresas = empresas;
        this.precios = precios;
        this.metricas = metricas;
    }

    @Cacheable(value = "analisis", key = "#ticker + ':' + #ventana")
    public AnalisisVista analisis(String ticker, int ventana) {
        return calcular(ticker, ventana);
    }

    @Cacheable("destacado")
    public DestacadoVista destacado() {
        MetricaVista m = metricas.listado().stream().filter(x -> x.ratioAsimetria() != null)
                .max(Comparator.comparingDouble(MetricaVista::ratioAsimetria))
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Métricas aún no disponibles"));
        AnalisisVista a = calcular(m.ticker(), 40);
        double[] ultimos = precios.ultimas(empresa(m.ticker()).getId(), 22).valores();
        double r30 = ultimos.length > 1 ? (ultimos[ultimos.length - 1] / ultimos[0] - 1) * 100 : 0;
        return new DestacadoVista(m.ticker(), m.nombre(), m.beta(), m.ratioAsimetria(), m.nivelVolatilidad(), r30,
                a.base100Activo(), a.base100Indice());
    }

    private Empresa empresa(String ticker) {
        return empresas.findByTickerAndActivaTrue(ticker).orElseThrow(() -> ApiException.noEncontrado("Empresa no encontrada"));
    }

    private AnalisisVista calcular(String ticker, int ventana) {
        Empresa e = empresa(ticker);
        if (e.isEsIndice()) {
            throw ApiException.invalido("Selecciona una empresa, no el índice");
        }
        Empresa indice = empresas.findFirstByEsIndiceTrueAndActivaTrue().orElseThrow();
        SerieTemporal.Alineadas todo = precios.serie(e.getId()).alinear(precios.serie(indice.getId()));
        int n = Math.min(ventana, todo.tamano() - 1);
        if (n < 1) {
            throw ApiException.invalido("No hay suficientes datos para el análisis");
        }
        int desde = todo.tamano() - n - 1;
        List<LocalDate> fechas = todo.fechas().subList(desde, todo.tamano());
        double[] pa = java.util.Arrays.copyOfRange(todo.a(), desde, todo.tamano());
        double[] pi = java.util.Arrays.copyOfRange(todo.b(), desde, todo.tamano());
        double[] ra = escalar(MotorCalculo.retornos(pa), 100), ri = escalar(MotorCalculo.retornos(pi), 100);
        double ret = (pa[pa.length - 1] / pa[0] - 1) * 100, retI = (pi[pi.length - 1] / pi[0] - 1) * 100;

        List<double[]> dispersion = new ArrayList<>(ri.length);
        for (int i = 0; i < ri.length; i++) {
            dispersion.add(new double[]{ri[i], ra[i]});
        }
        double mA = MotorCalculo.media(ra), mI = MotorCalculo.media(ri);
        Desglose desglose = new Desglose(dia(2.0, fechas, ri, ra), dia(-2.0, fechas, ri, ra),
                new PromedioDesglose(mI, mA, mI == 0 ? null : mA / mI));
        MetricaVista metrica = metricas.listado().stream().filter(x -> x.ticker().equals(ticker)).findFirst().orElse(null);
        return new AnalisisVista(e.getTicker(), e.getNombreEmpresa(), n, fechas.stream().map(Objects::toString).toList(),
                base100(pa), base100(pi), ret, retI, ret - retI, MotorCalculo.maxDrawdown(pa), dispersion, desglose, metrica);
    }

    /** Sesión cuyo movimiento del índice es el más cercano a ±2 % (el caso del documento). */
    private static DiaDesglose dia(double objetivo, List<LocalDate> fechas, double[] ri, double[] ra) {
        int mejor = 0;
        for (int i = 1; i < ri.length; i++) {
            if (Math.abs(ri[i] - objetivo) < Math.abs(ri[mejor] - objetivo)) {
                mejor = i;
            }
        }
        return new DiaDesglose(fechas.get(mejor + 1).toString(), ri[mejor], ra[mejor], ri[mejor] == 0 ? null : Math.abs(ra[mejor] / ri[mejor]));
    }

    private static List<Double> base100(double[] p) {
        List<Double> out = new ArrayList<>(p.length);
        for (double v : p) {
            out.add(v / p[0] * 100);
        }
        return out;
    }

    private static double[] escalar(double[] x, double k) {
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            r[i] = x[i] * k;
        }
        return r;
    }
}
