package co.edu.ucc.asimetria;

import co.edu.ucc.asimetria.mercado.CotizacionService;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HU-02, HU-03, HU-04, HU-09, HU-10 con 5 años de datos simulados en PostgreSQL + TimescaleDB. */
class MercadoApiTest extends PruebaIntegracion {

    @Autowired
    private CotizacionService cotizaciones;
    @Autowired
    private PreciosRepositorio precios;

    @Test
    void hu02Ca1CincoAniosDeHistoria() throws Exception {
        Cuenta c = registrar();
        getCon(c, "/mercado/empresas").andExpect(jsonPath("$[*].ticker", hasItems("^NDX", "RTC", "HLX")));
        mvc.perform(get(V1 + "/mercado/precios/{t}?dias=5000", "^NDX").header("Authorization", c.bearer()))
                .andExpect(jsonPath("$.cierres", hasSize(greaterThanOrEqualTo(1260))));
        getCon(c, "/mercado/precios/RTC?dias=20").andExpect(jsonPath("$.cierres", hasSize(21)));
        getCon(c, "/mercado/precios/NOPE").andExpect(status().isNotFound());
    }

    @Test
    void hu03MetricasRitechYDatosInsuficientes() throws Exception {
        Cuenta c = registrar();
        String r = cuerpo(getCon(c, "/metrics/").andExpect(status().isOk()));
        assertThat(read(r, "RTC", "beta_alcista")).isCloseTo(2.0, within(0.1));
        assertThat(read(r, "RTC", "beta_bajista")).isCloseTo(1.25, within(0.1));
        assertThat(read(r, "RTC", "ratio_asimetria")).isGreaterThan(1);
        List<Object> errores = JsonPath.read(r, "$[?(@.ticker=='HLX')].error");
        assertThat(errores).containsExactly("Datos insuficientes para calcular la beta");   // HU-03 CA-4
        List<Object> tendencia = JsonPath.read(r, "$[?(@.ticker=='RTC')].tendencia[*]");
        assertThat(tendencia).hasSize(21);
        getCon(c, "/metrics/").andExpect(jsonPath("$[*].ticker", not(hasItems("^NDX"))));
        getCon(c, "/metrics/RTC").andExpect(jsonPath("$.ticker").value("RTC"));
        getCon(c, "/metrics/NOPE").andExpect(status().isNotFound());
    }

    private static double read(String json, String ticker, String campo) {
        List<Number> v = JsonPath.read(json, "$[?(@.ticker=='" + ticker + "')]." + campo);
        return v.get(0).doubleValue();
    }

    @Test
    void hu09Ca2DesgloseDiaAlcistaYBajista() throws Exception {
        Cuenta c = registrar();
        String r = cuerpo(getCon(c, "/metrics/RTC/analisis").andExpect(status().isOk()));
        assertThat(JsonPath.<Number>read(r, "$.desglose.alcista.indice").doubleValue()).isCloseTo(2.0, within(0.01));
        assertThat(JsonPath.<Number>read(r, "$.desglose.alcista.activo").doubleValue()).isCloseTo(4.0, within(0.01));
        assertThat(JsonPath.<Number>read(r, "$.desglose.alcista.ratio").doubleValue()).isCloseTo(2.0, within(0.01));
        assertThat(JsonPath.<Number>read(r, "$.desglose.bajista.indice").doubleValue()).isCloseTo(-2.0, within(0.01));
        assertThat(JsonPath.<Number>read(r, "$.desglose.bajista.activo").doubleValue()).isCloseTo(-2.5, within(0.01));
        assertThat(JsonPath.<Number>read(r, "$.desglose.bajista.ratio").doubleValue()).isCloseTo(1.25, within(0.01));
        assertThat(JsonPath.<Integer>read(r, "$.ventana")).isEqualTo(90);
        assertThat(JsonPath.<List<Object>>read(r, "$.base100_activo")).hasSize(91);
        assertThat(JsonPath.<Number>read(r, "$.base100_indice[0]").doubleValue()).isCloseTo(100, within(1e-9));
        assertThat(JsonPath.<Number>read(r, "$.max_drawdown").doubleValue()).isLessThanOrEqualTo(0);
        assertThat(JsonPath.<String>read(r, "$.metricas.ticker")).isEqualTo("RTC");
        mvc.perform(get(V1 + "/metrics/{t}/analisis", "^NDX").header("Authorization", c.bearer()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void hu04Ca5SinPerfilPideQuiz() throws Exception {
        getCon(registrar(null), "/recommendations/").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("quiz")));
    }

    @Test
    void hu04Ca1Ca3ConservadorDentroDeSuMargenYOrdenado() throws Exception {
        String r = cuerpo(getCon(registrar("CONSERVADOR"), "/recommendations/").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].volatilidad", everyItem(lessThanOrEqualTo(20.0)))));
        List<Double> puntajes = JsonPath.read(r, "$[*].puntaje");
        assertThat(puntajes).isNotEmpty().isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(JsonPath.<List<Object>>read(r, "$[*].beta_alcista")).doesNotContainNull();
    }

    @Test
    void hu04Ca2Ca4FiltrosYSinResultados() throws Exception {
        Cuenta c = registrar("AGRESIVO");
        int todos = JsonPath.<List<Object>>read(cuerpo(getCon(c, "/recommendations/")), "$").size();
        String f = cuerpo(getCon(c, "/recommendations/?vol_max=25&retorno_min=5"));
        assertThat(JsonPath.<List<Object>>read(f, "$").size()).isLessThan(todos);
        getCon(c, "/recommendations/?vol_max=25&retorno_min=5").andExpect(jsonPath("$[*].volatilidad", everyItem(lessThanOrEqualTo(25.0))));
        getCon(c, "/recommendations/?retorno_min=999").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void destacadoPublicoParaLaLanding() throws Exception {
        mvc.perform(get(V1 + "/destacado")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("RTC")).andExpect(jsonPath("$.base100_activo", hasSize(41)))
                .andExpect(jsonPath("$.retorno_30d").isNumber());
    }

    @Test
    void hu09Ca4SinDatoEnVivoUsaElUltimoCierreYLuegoSincroniza() throws Exception {
        Cuenta c = registrar();
        double ultimo = precios.ultimosCierres().get("RTC").ultimo();
        String antes = cuerpo(getCon(c, "/mercado/cotizaciones"));
        if (Boolean.TRUE.equals(JsonPath.read(antes, "$.estado.desactualizado"))) {
            assertThat(JsonPath.<Number>read(antes, "$.cotizaciones.RTC.precio").doubleValue()).isCloseTo(ultimo, within(1e-6));
        }
        assertThat(cotizaciones.sincronizar().desactualizado()).isFalse();
        getCon(c, "/mercado/cotizaciones").andExpect(jsonPath("$.estado.desactualizado").value(false))
                .andExpect(jsonPath("$.cotizaciones['^NDX'].precio").isNumber());
        getCon(c, "/mercado/intradia/RTC").andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void buscadorPorPrefijoConTrie() throws Exception {
        Cuenta c = registrar();
        getCon(c, "/mercado/buscar?q=ri").andExpect(jsonPath("$[0].ticker").value("RTC"));
        getCon(c, "/mercado/buscar?q=tecnologia").andExpect(jsonPath("$[*].ticker", hasItems("RTC")));
        getCon(c, "/mercado/buscar?q=zzzz").andExpect(jsonPath("$", hasSize(0)));
    }
}
