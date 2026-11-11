package co.edu.ucc.asimetria.mercado;

import co.edu.ucc.asimetria.comun.estructuras.SerieTemporal;
import co.edu.ucc.asimetria.dominio.calculo.DatosInsuficientesException;
import co.edu.ucc.asimetria.dominio.calculo.MotorCalculo;
import co.edu.ucc.asimetria.dominio.calculo.ResultadoBeta;
import co.edu.ucc.asimetria.mercado.Vistas.MetricaVista;
import co.edu.ucc.asimetria.persistencia.Empresa;
import co.edu.ucc.asimetria.persistencia.EmpresaRepositorio;
import co.edu.ucc.asimetria.persistencia.MetricaEmpresa;
import co.edu.ucc.asimetria.persistencia.MetricaRepositorio;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Métricas por empresa (RF-03, RF-10): beta alcista/bajista, beta global, correlación,
 * volatilidad y retorno esperado (CAPM). Se recalculan en lote y el listado se sirve desde la
 * caché ({@code metricas}, Redis) hasta el siguiente recálculo, que la invalida.
 */
@Service
public class MetricasService {

    private final EmpresaRepositorio empresas;
    private final MetricaRepositorio metricas;
    private final PreciosRepositorio precios;
    private final Clock reloj;

    public MetricasService(EmpresaRepositorio empresas, MetricaRepositorio metricas, PreciosRepositorio precios, Clock reloj) {
        this.empresas = empresas;
        this.metricas = metricas;
        this.precios = precios;
        this.reloj = reloj;
    }

    @Transactional
    @CacheEvict(cacheNames = {"metricas", "recomendaciones", "analisis", "destacado"}, allEntries = true)
    public int recalcular(Map<String, Double> dividendos) {
        Optional<Empresa> indice = empresas.findFirstByEsIndiceTrueAndActivaTrue();
        if (indice.isEmpty()) {
            return 0;
        }
        SerieTemporal serieIndice = precios.serie(indice.get().getId());
        double[] rmHistorico = MotorCalculo.retornos(serieIndice.valores());
        Instant ahora = reloj.instant();
        int n = 0;
        for (Empresa e : empresas.findByActivaTrueOrderById()) {
            if (e.isEsIndice()) {
                continue;
            }
            SerieTemporal.Alineadas al = precios.serie(e.getId()).alinear(serieIndice);
            double[] ra = MotorCalculo.retornos(al.a());
            double[] rm = MotorCalculo.retornos(al.b());
            Double alcista = null, bajista = null, beta = null, corr = null, capm = null;
            try {
                ResultadoBeta b = MotorCalculo.betaAsimetrica(ra, rm, MotorCalculo.MIN_OBSERVACIONES);
                alcista = b.alcista();
                bajista = b.bajista();
                beta = b.global();
                corr = MotorCalculo.correlacion(ra, rm);
                capm = MotorCalculo.retornoEsperadoCapm(b.global(), rmHistorico);
            } catch (DatosInsuficientesException ignorada) {
                // HU-03 CA-4: se guarda la volatilidad, las betas quedan nulas (no se inventa un valor)
            }
            MetricaEmpresa m = metricas.findByEmpresaId(e.getId()).orElseGet(() -> new MetricaEmpresa(e.getId()));
            m.actualizar(alcista, bajista, beta, corr, MotorCalculo.volatilidad(ra), capm,
                    dividendos == null ? null : dividendos.get(e.getTicker()), ahora);
            metricas.save(m);
            n++;
        }
        return n;
    }

    @Cacheable("metricas")
    @Transactional(readOnly = true)
    public List<MetricaVista> listado() {
        List<MetricaVista> out = new ArrayList<>();
        for (Empresa e : empresas.findByActivaTrueOrderById()) {
            if (e.isEsIndice()) {
                continue;
            }
            metricas.findByEmpresaId(e.getId()).ifPresent(m -> out.add(aVista(e, m)));
        }
        return out;
    }

    private MetricaVista aVista(Empresa e, MetricaEmpresa m) {
        Double ratio = m.getBetaAlcista() != null && m.getBetaBajista() != null && m.getBetaBajista() != 0
                ? m.getBetaAlcista() / m.getBetaBajista() : null;
        List<Double> tendencia = new ArrayList<>();
        for (double v : precios.ultimas(e.getId(), 21).valores()) {
            tendencia.add(v);
        }
        return new MetricaVista(e.getTicker(), e.getNombreEmpresa(), e.getSector(), m.getBetaAlcista(), m.getBetaBajista(),
                m.getBeta(), ratio, m.getCorrelacion(), m.getVolatilidad(), MotorCalculo.nivelVolatilidad(m.getVolatilidad()),
                m.getRetornoEsperado(), m.getRendimientoDividendos(), m.getBetaAlcista() == null ? MotorCalculo.ERROR_DATOS : null,
                tendencia, m.getFechaActualizacion().toString());
    }
}
