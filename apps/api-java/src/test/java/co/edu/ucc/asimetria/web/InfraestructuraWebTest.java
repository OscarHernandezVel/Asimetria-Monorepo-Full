package co.edu.ucc.asimetria.web;

import co.edu.ucc.asimetria.comun.almacen.AlmacenMemoria;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import co.edu.ucc.asimetria.dominio.mercado.Cotizacion;
import co.edu.ucc.asimetria.mercado.CotizacionService;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio.UltimoCierre;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** IP real, limitador de peticiones, almacén en memoria y regla de variación de cotizaciones. */
class InfraestructuraWebTest {

    private static PropiedadesAsimetria props(boolean confiarProxy, boolean limite) {
        return new PropiedadesAsimetria(new PropiedadesAsimetria.Jwt("x".repeat(32), 120), 4, "simulado",
                new PropiedadesAsimetria.AlphaVantage("", 25), new PropiedadesAsimetria.SendGrid("", "a@b.c"),
                Duration.ofSeconds(5), "memoria", true, true, false, confiarProxy, 0,
                new PropiedadesAsimetria.LimiteTasa(limite, 1, 2, 60, 1));
    }

    @Test
    void ipRealDesdeCloudFrontNginxOConexion() {
        IpCliente ip = new IpCliente(props(true, false));
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.5");
        assertThat(ip.de(r)).isEqualTo("10.0.0.5");
        r.addHeader("X-Real-IP", "203.0.113.7");
        assertThat(ip.de(r)).isEqualTo("203.0.113.7");
        r.addHeader("CloudFront-Viewer-Address", "198.51.100.10:46532");
        assertThat(ip.de(r)).isEqualTo("198.51.100.10");
        assertThat(IpCliente.sinPuerto("2001:db8::1:443")).isEqualTo("2001:db8::1");
        assertThat(new IpCliente(props(false, false)).de(r)).isEqualTo("10.0.0.5");   // sin proxy de confianza no se leen cabeceras
    }

    @Test
    void limitadorDevuelve429AlAgotarLaRafagaPorIp() throws Exception {
        PropiedadesAsimetria p = props(true, true);
        LimiteTasaFiltro f = new LimiteTasaFiltro(p, new IpCliente(p));
        int[] codigos = new int[3];
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/metrics/");
            req.setRemoteAddr("192.0.2.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(req, res, new MockFilterChain());
            codigos[i] = res.getStatus();
        }
        assertThat(codigos).containsExactly(200, 200, 429);   // ráfaga de 2
        MockHttpServletRequest otraIp = new MockHttpServletRequest("GET", "/api/v1/metrics/");
        otraIp.setRemoteAddr("192.0.2.2");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(otraIp, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(200);           // cada IP tiene su propia cubeta
        MockHttpServletRequest auth = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        auth.setRemoteAddr("192.0.2.3");
        f.doFilter(auth, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse bloqueada = new MockHttpServletResponse();
        f.doFilter(auth, bloqueada, new MockFilterChain());
        assertThat(bloqueada.getStatus()).isEqualTo(429);
        assertThat(bloqueada.getHeader("Retry-After")).isEqualTo("60");
        MockHttpServletRequest web = new MockHttpServletRequest("GET", "/index.html");
        assertThat(f.shouldNotFilter(web)).isTrue();
    }

    @Test
    void almacenMemoriaTtlContadoresYListasAcotadas() {
        Instant[] ahora = {Instant.parse("2026-11-09T12:00:00Z")};
        Clock reloj = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return ahora[0]; }
        };
        AlmacenMemoria a = new AlmacenMemoria(reloj);
        a.guardar("k", "v", Duration.ofSeconds(10));
        assertThat(a.obtener("k")).contains("v");
        assertThat(a.incrementar("n", Duration.ofSeconds(10))).isEqualTo(1);
        assertThat(a.incrementar("n", Duration.ofSeconds(10))).isEqualTo(2);
        ahora[0] = ahora[0].plusSeconds(11);
        assertThat(a.obtener("k")).isEmpty();
        assertThat(a.incrementar("n", Duration.ofSeconds(10))).isEqualTo(1);   // ventana nueva
        for (int i = 0; i < 5; i++) {
            a.agregarAcotado("l", String.valueOf(i), 3);
        }
        assertThat(a.listar("l")).containsExactly("2", "3", "4");
        a.eliminar("l");
        assertThat(a.listar("l")).isEmpty();
        assertThat(a.disponible()).isTrue();
        assertThat(a.tipo()).isEqualTo("memoria");
    }

    @Test
    void variacionIntradiaFrenteAlCierrePrevio() {
        LocalDate hoy = LocalDate.of(2026, 11, 9);
        UltimoCierre ayer = new UltimoCierre(100, 98, hoy.minusDays(1));
        UltimoCierre conCierreDeHoy = new UltimoCierre(101, 100, hoy);
        Cotizacion vivo = CotizacionService.calcular(Optional.of(102.0), ayer, hoy);
        assertThat(vivo.variacion()).isCloseTo(2.0, within(1e-9));                         // vs. último cierre
        assertThat(CotizacionService.calcular(Optional.of(102.0), conCierreDeHoy, hoy).cierreAnterior()).isEqualTo(100);
        Cotizacion sinVivo = CotizacionService.calcular(Optional.empty(), ayer, hoy);
        assertThat(sinVivo.precio()).isEqualTo(100);                                       // fallback: último cierre
        assertThat(sinVivo.variacion()).isCloseTo((100 / 98.0 - 1) * 100, within(1e-9));
    }
}
