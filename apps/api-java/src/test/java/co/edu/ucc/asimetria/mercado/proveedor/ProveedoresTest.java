package co.edu.ucc.asimetria.mercado.proveedor;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.comun.almacen.AlmacenMemoria;
import co.edu.ucc.asimetria.dominio.mercado.Barra;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProveedoresTest {

    /** Reloj que avanza a mano: prueba el paso del tiempo sin esperar. */
    static final class RelojManual extends Clock {
        private Instant ahora = Instant.parse("2026-11-09T15:00:00Z");

        void avanzar(Duration d) {
            ahora = ahora.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    /** Proveedor falso: falla las primeras {@code fallos} llamadas. */
    static final class ProveedorFalso implements ProveedorMercado {
        final AtomicInteger llamadas = new AtomicInteger();
        int fallos;
        RuntimeException error = new ErrorProveedor("caído");

        @Override public String nombre() { return "FALSO"; }
        @Override public List<EntradaCatalogo> catalogo() { return List.of(new EntradaCatalogo("X", "X", "S")); }
        @Override public List<Barra> historico(String t, int d) { return responder(List.of()); }
        @Override public Map<String, Double> cotizaciones(Map<String, Double> u) { return responder(Map.of("X", 1.0)); }
        @Override public Double dividendos(String t) { return responder(2.0); }

        private <T> T responder(T valor) {
            if (llamadas.incrementAndGet() <= fallos) {
                throw error;
            }
            return valor;
        }
    }

    @Test
    void circuitBreakerCerradoAbiertoSemiabiertoYCerrado() {
        RelojManual reloj = new RelojManual();
        AlmacenMemoria almacen = new AlmacenMemoria(reloj);
        CircuitBreaker cb = new CircuitBreaker(almacen, "t", 3, Duration.ofSeconds(30), reloj);
        assertThat(cb.estado()).isEqualTo(CircuitBreaker.Estado.CERRADO);
        cb.registrarFallo();
        cb.registrarFallo();
        assertThat(cb.permiteLlamar()).isTrue();
        cb.registrarFallo();                                        // umbral → ABIERTO
        assertThat(cb.estado()).isEqualTo(CircuitBreaker.Estado.ABIERTO);
        assertThat(cb.permiteLlamar()).isFalse();
        reloj.avanzar(Duration.ofSeconds(31));                      // pasa la espera → SEMIABIERTO
        assertThat(cb.estado()).isEqualTo(CircuitBreaker.Estado.SEMIABIERTO);
        cb.registrarFallo();                                        // un fallo en prueba lo reabre al instante
        assertThat(cb.estado()).isEqualTo(CircuitBreaker.Estado.ABIERTO);
        reloj.avanzar(Duration.ofSeconds(31));
        cb.registrarExito();                                        // un éxito lo cierra
        assertThat(cb.estado()).isEqualTo(CircuitBreaker.Estado.CERRADO);
    }

    @Test
    void resilienteReintentaConBackoffYAbreElCircuito() {
        RelojManual reloj = new RelojManual();
        ProveedorFalso base = new ProveedorFalso();
        base.fallos = 100;
        List<Duration> esperas = new ArrayList<>();
        ProveedorResiliente p = new ProveedorResiliente(base, new CircuitBreaker(new AlmacenMemoria(reloj), "f", 3, Duration.ofSeconds(30), reloj),
                3, Duration.ofMillis(150), esperas::add);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> p.cotizaciones(Map.of("X", 1.0))).isInstanceOf(ErrorProveedor.class).hasMessageContaining("3 intentos");
        }
        assertThat(base.llamadas.get()).isEqualTo(9);                       // 3 intentos × 3 ciclos
        assertThat(esperas.subList(0, 2)).containsExactly(Duration.ofMillis(150), Duration.ofMillis(300));
        assertThatThrownBy(() -> p.historico("X", 1)).isInstanceOf(ErrorProveedor.CircuitoAbierto.class);
        assertThat(base.llamadas.get()).isEqualTo(9);                       // con el circuito abierto no se llama
        assertThat(p.circuito().estado()).isEqualTo(CircuitBreaker.Estado.ABIERTO);
    }

    @Test
    void resilienteRecuperaTrasUnFalloYNoReintentaSiLaCuotaSeAgoto() {
        RelojManual reloj = new RelojManual();
        ProveedorFalso base = new ProveedorFalso();
        base.fallos = 1;
        ProveedorResiliente p = new ProveedorResiliente(base, new CircuitBreaker(new AlmacenMemoria(reloj), "g", 3, Duration.ofSeconds(30), reloj),
                3, Duration.ofMillis(1), d -> { });
        assertThat(p.dividendos("X")).isEqualTo(2.0);
        assertThat(p.nombre()).isEqualTo("FALSO");
        assertThat(p.catalogo()).hasSize(1);
        ProveedorFalso cuota = new ProveedorFalso();
        cuota.fallos = 100;
        cuota.error = new ErrorProveedor.LimiteExcedido("cuota");
        ProveedorResiliente q = new ProveedorResiliente(cuota, new CircuitBreaker(new AlmacenMemoria(reloj), "h", 3, Duration.ofSeconds(30), reloj),
                3, Duration.ofMillis(1), d -> { });
        assertThatThrownBy(() -> q.cotizaciones(Map.of())).isInstanceOf(ErrorProveedor.LimiteExcedido.class);
        assertThat(cuota.llamadas.get()).isEqualTo(1);
    }

    @Test
    void trazadoRegistraExitoYFalloSinAlterarElError() {
        AuditoriaService auditoria = mock(AuditoriaService.class);
        ProveedorFalso base = new ProveedorFalso();
        ProveedorTrazado p = new ProveedorTrazado(base, auditoria);
        assertThat(p.cotizaciones(Map.of("X", 1.0))).containsEntry("X", 1.0);
        verify(auditoria).registrarLlamada(eq("FALSO"), eq("cotizaciones"), eq(true), eq(200), anyLong(), eq("1 tickers"));
        base.fallos = 100;
        assertThatThrownBy(() -> p.dividendos("X")).hasMessage("caído");
        verify(auditoria).registrarLlamada(eq("FALSO"), eq("dividendos"), eq(false), isNull(), anyLong(), eq("caído"));
        assertThatThrownBy(() -> p.historico("X", 1)).isInstanceOf(ErrorProveedor.class);
        assertThat(p.nombre()).isEqualTo("FALSO");
        assertThat(p.catalogo()).hasSize(1);
    }

    private static final String BASE_AV = "https://www.alphavantage.co/query";

    private record Av(ProveedorAlphaVantage p, MockRestServiceServer servidor) {
    }

    private static Av alphaVantage(int limite) {
        RestClient.Builder b = RestClient.builder().baseUrl(BASE_AV);
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        RelojManual reloj = new RelojManual();
        return new Av(new ProveedorAlphaVantage(b.build(), "clave-secreta", limite, new AlmacenMemoria(reloj), reloj), s);
    }

    @Test
    void alphaVantageAdaptaHistoricoCotizacionYDividendos() {
        Av av = alphaVantage(25);
        StringBuilder serie = new StringBuilder();
        for (int d = 2; d <= 7; d++) {
            serie.append(d == 2 ? "" : ",").append("\"2026-01-0%d\":{\"1. open\":\"10\",\"2. high\":\"11\",\"3. low\":\"9\",\"4. close\":\"%d\",\"5. volume\":\"100\"}".formatted(d, d));
        }
        av.servidor().expect(requestTo(containsString("function=TIME_SERIES_DAILY"))).andRespond(withSuccess("{\"Time Series (Daily)\":{" + serie + "}}", MediaType.APPLICATION_JSON));
        av.servidor().expect(requestTo(containsString("symbol=QQQ"))).andRespond(withSuccess("{\"Global Quote\":{\"05. price\":\"512.40\"}}", MediaType.APPLICATION_JSON));
        av.servidor().expect(requestTo(containsString("function=OVERVIEW"))).andRespond(withSuccess("{\"DividendYield\":\"0.0123\"}", MediaType.APPLICATION_JSON));
        List<Barra> barras = av.p().historico("AAPL", 2);
        assertThat(barras).extracting(Barra::cierre).containsExactly(5.0, 6.0, 7.0);
        assertThat(barras.get(0).fecha()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(av.p().cotizaciones(Map.of("^NDX", 0.0))).containsEntry("^NDX", 512.40);   // ^NDX → QQQ
        assertThat(av.p().dividendos("AAPL")).isCloseTo(1.23, within(1e-9));
        assertThat(av.p().dividendos("^NDX")).isNull();
        assertThat(av.p().cuotaUsadaHoy()).isEqualTo(3);
        assertThat(av.p().catalogo().get(0).ticker()).isEqualTo("^NDX");
        assertThat(av.p().nombre()).isEqualTo(ProveedorAlphaVantage.NOMBRE);
        av.servidor().verify();
    }

    @Test
    void alphaVantageLimitesErroresYClaveEnmascarada() {
        Av av = alphaVantage(25);
        av.servidor().expect(requestTo(containsString("GLOBAL_QUOTE"))).andRespond(withSuccess("{\"Note\":\"rate limit\"}", MediaType.APPLICATION_JSON));
        av.servidor().expect(requestTo(containsString("GLOBAL_QUOTE"))).andRespond(withSuccess("{\"Error Message\":\"símbolo inválido clave-secreta\"}", MediaType.APPLICATION_JSON));
        av.servidor().expect(requestTo(containsString("GLOBAL_QUOTE"))).andRespond(withServerError());
        assertThatThrownBy(() -> av.p().cotizaciones(Map.of("AAPL", 1.0))).isInstanceOf(ErrorProveedor.LimiteExcedido.class);
        assertThatThrownBy(() -> av.p().cotizaciones(Map.of("AAPL", 1.0))).hasMessageContaining("***").hasMessageNotContaining("clave-secreta");
        assertThatThrownBy(() -> av.p().cotizaciones(Map.of("AAPL", 1.0))).isInstanceOf(ErrorProveedor.class).hasMessageNotContaining("clave-secreta");
        assertThatThrownBy(() -> new ProveedorAlphaVantage(RestClient.create(), " ", 1, null, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alphaVantageCuotaDiariaLocalCortaAntesDeLlamar() {
        Av av = alphaVantage(1);
        av.servidor().expect(requestTo(containsString("GLOBAL_QUOTE"))).andRespond(withSuccess("{\"Global Quote\":{\"05. price\":\"1\"}}", MediaType.APPLICATION_JSON));
        av.p().cotizaciones(Map.of("AAPL", 1.0));
        assertThatThrownBy(() -> av.p().cotizaciones(Map.of("AAPL", 1.0))).isInstanceOf(ErrorProveedor.LimiteExcedido.class).hasMessageContaining("Cuota diaria");
        av.servidor().verify();   // la segunda petición nunca salió
    }

    @Test
    void simuladoEsDeterministaYReproduceElCasoDelDocumento() {
        RelojManual reloj = new RelojManual();
        AlmacenMemoria almacen = new AlmacenMemoria(reloj);
        ProveedorSimulado p = new ProveedorSimulado(reloj, almacen, 0, ProveedorSimulado.SEMILLA);
        List<Barra> ix = p.historico("^NDX", 5000), rt = p.historico("RTC", 5000);
        assertThat(ix).hasSize(ProveedorSimulado.DIAS_HISTORIA + 1);
        assertThat(rt.get(rt.size() - 1).cierre()).isEqualTo(212.40);
        int n = ix.size();
        double rIx = ix.get(n - 8).cierre() / ix.get(n - 9).cierre() - 1, rRt = rt.get(n - 8).cierre() / rt.get(n - 9).cierre() - 1;
        assertThat(rIx).isCloseTo(0.02, within(1e-4));
        assertThat(rRt).isCloseTo(0.04, within(1e-4));
        assertThat(p.historico("HLX", 5000)).hasSize(13);
        assertThat(ix).noneMatch(b -> b.fecha().getDayOfWeek() == DayOfWeek.SATURDAY || b.fecha().getDayOfWeek() == DayOfWeek.SUNDAY);
        assertThat(p.historico("RTC", 20)).hasSize(21);
        assertThat(p.dividendos("GLX")).isEqualTo(3.1);
        assertThat(p.dividendos("NOPE")).isNull();
        assertThat(p.catalogo()).hasSize(12);
        assertThat(p.cotizaciones(Map.of("RTC", 100.0)).get("RTC")).isCloseTo(100, within(1.0));
        almacen.guardar(ProveedorSimulado.BANDERA_CAIDO, "true", null);
        assertThatThrownBy(() -> p.cotizaciones(Map.of("RTC", 1.0))).isInstanceOf(ErrorProveedor.class);
        assertThat(new ProveedorSimulado(reloj, almacen, 0, ProveedorSimulado.SEMILLA).historico("RTC", 3)).isEqualTo(p.historico("RTC", 3));
    }

    static co.edu.ucc.asimetria.config.PropiedadesAsimetria props(String proveedor, String claveAv) {
        return new co.edu.ucc.asimetria.config.PropiedadesAsimetria(
                new co.edu.ucc.asimetria.config.PropiedadesAsimetria.Jwt("x".repeat(32), 120), 4, proveedor,
                new co.edu.ucc.asimetria.config.PropiedadesAsimetria.AlphaVantage(claveAv, 25),
                new co.edu.ucc.asimetria.config.PropiedadesAsimetria.SendGrid("", "alertas@asimetria.app"),
                Duration.ofSeconds(5), "memoria", true, true, false, true, 0,
                new co.edu.ucc.asimetria.config.PropiedadesAsimetria.LimiteTasa(false, 15, 40, 10, 6));
    }

    @Test
    void fabricaEnsamblaLaCadenaSegunLaConfiguracion() {
        RelojManual reloj = new RelojManual();
        AlmacenMemoria almacen = new AlmacenMemoria(reloj);
        AuditoriaService auditoria = mock(AuditoriaService.class);
        FabricaProveedores f = new FabricaProveedores();
        assertThat(f.proveedorMercado(props("simulado", ""), almacen, auditoria, reloj).nombre()).isEqualTo("SIMULADO");
        assertThat(f.proveedorMercado(props("alphavantage", "k"), almacen, auditoria, reloj).nombre()).isEqualTo("ALPHAVANTAGE");
        assertThatThrownBy(() -> f.proveedorMercado(props("otro", ""), almacen, auditoria, reloj)).isInstanceOf(IllegalStateException.class);
    }
}
