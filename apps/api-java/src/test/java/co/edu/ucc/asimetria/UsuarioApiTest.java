package co.edu.ucc.asimetria;

import co.edu.ucc.asimetria.alerta.notificacion.Canales;
import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;
import co.edu.ucc.asimetria.mercado.CotizacionService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HU-05, HU-06 y HU-08 contra la API real. */
class UsuarioApiTest extends PruebaIntegracion {

    @Autowired
    private CotizacionService cotizaciones;
    @Autowired
    private AlmacenCompartido almacen;

    @Test
    void hu08CrudWatchlistYDuplicados() throws Exception {
        Cuenta c = registrar();
        getCon(c, "/watchlist").andExpect(jsonPath("$", hasSize(0)));
        postCon(c, "/watchlist", "{\"ticker\":\"rtc\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.ticker").value("RTC"));
        postCon(c, "/watchlist", "{\"ticker\":\"RTC\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("ya está en tu lista")));                 // CA-4
        patchCon(c, "/watchlist/RTC", "{\"cantidad\":120,\"favorito\":true}")
                .andExpect(jsonPath("$.cantidad").value(120)).andExpect(jsonPath("$.favorito").value(true));  // CA-2
        patchCon(c, "/watchlist/RTC", "{\"cantidad\":-1}").andExpect(status().isUnprocessableEntity());
        deleteCon(c, "/watchlist/RTC").andExpect(status().isNoContent());
        getCon(c, "/watchlist").andExpect(jsonPath("$", hasSize(0)));
        deleteCon(c, "/watchlist/RTC").andExpect(status().isNotFound());
        postCon(c, "/watchlist", "{\"ticker\":\"NOPE\"}").andExpect(status().isNotFound());
    }

    @Test
    void hu08Ca3PrivacidadEntreUsuarios() throws Exception {
        Cuenta a = registrar(), b = registrar();
        postCon(a, "/watchlist", "{\"ticker\":\"NVC\"}").andExpect(status().isCreated());
        getCon(b, "/watchlist").andExpect(jsonPath("$", hasSize(0)));
        deleteCon(b, "/watchlist/NVC").andExpect(status().isNotFound());
        getCon(a, "/watchlist").andExpect(jsonPath("$", hasSize(1)));
    }

    @ParameterizedTest
    @CsvSource({"alcista,1040", "bajista,975"})
    void hu05Ca1Ca2Ritech1000Usd(String caso, double esperado) throws Exception {
        Cuenta c = registrar();
        String a = cuerpo(getCon(c, "/metrics/RTC/analisis"));
        String dia = JsonPath.read(a, "$.desglose." + caso + ".fecha");
        List<String> fechas = JsonPath.read(a, "$.fechas");
        String ini = fechas.get(fechas.indexOf(dia) - 1);
        String r = cuerpo(postCon(c, "/simulaciones", """
                {"ticker":"RTC","monto":1000,"fecha_inicio":"%s","fecha_fin":"%s"}""".formatted(ini, dia)).andExpect(status().isCreated()));
        assertThat(JsonPath.<Number>read(r, "$.valor_final").doubleValue()).isCloseTo(esperado, within(0.05));
        assertThat(JsonPath.<java.util.Map<String, Object>>read(r, "$.serie[0]")).containsOnlyKeys("fecha", "portafolio", "indice"); // CA-3
        getCon(c, "/simulaciones").andExpect(jsonPath("$[0].ticker").value("RTC")).andExpect(jsonPath("$[0].monto").value(1000.0));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|2024-01-02|2026-01-02|mayor que 0",
            "-10|2024-01-02|2026-01-02|mayor que 0",
            "1000|1990-01-01|1990-02-01|No hay datos",
            "1000|2025-02-01|2025-01-01|posterior"})
    void hu05Ca4DatosInvalidos(double monto, String ini, String fin, String mensaje) throws Exception {
        postCon(registrar(), "/simulaciones", """
                {"ticker":"RTC","monto":%s,"fecha_inicio":"%s","fecha_fin":"%s"}""".formatted(monto, ini, fin))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString(mensaje)));
    }

    @Test
    void hu06CrearDispararYNoDisparar() throws Exception {
        cotizaciones.sincronizar();
        Cuenta c = registrar();
        postCon(c, "/alertas", "{\"ticker\":\"RTC\",\"tipo\":\"VARIACION_PORCENTUAL\",\"umbral\":0.0001}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("ACTIVA"));            // CA-1
        postCon(c, "/alertas", "{\"ticker\":\"RTC\",\"tipo\":\"VARIACION_PORCENTUAL\",\"umbral\":90}").andExpect(status().isCreated());
        postCon(c, "/alertas", "{\"ticker\":\"RTC\",\"tipo\":\"OTRO\",\"umbral\":1}").andExpect(status().isUnprocessableEntity());
        postCon(c, "/alertas", "{\"ticker\":\"RTC\",\"tipo\":\"PRECIO_LIMITE\",\"umbral\":0}").andExpect(status().isUnprocessableEntity());
        postCon(c, "/alertas/evaluar", "{}").andExpect(jsonPath("$.disparadas").value(1)).andExpect(jsonPath("$.pendientes").value(0));
        String r = cuerpo(getCon(c, "/alertas"));
        assertThat(JsonPath.<List<String>>read(r, "$[?(@.umbral==90.0)].estado")).containsExactly("ACTIVA");          // CA-3
        assertThat(JsonPath.<List<String>>read(r, "$[?(@.umbral==1.0E-4)].estado")).containsExactly("DISPARADA");     // CA-2
        getCon(c, "/notificaciones").andExpect(jsonPath("$[0].texto").value(containsString("RiTech")))
                .andExpect(jsonPath("$[0].estado_envio").value("EN_APP"));
    }

    @Test
    void hu06Ca4CorreoCaidoNoPierdeLaAlerta() throws Exception {
        Cuenta c = registrar();
        postCon(c, "/alertas", "{\"ticker\":\"^NDX\",\"tipo\":\"PRECIO_LIMITE\",\"umbral\":1}").andExpect(status().isCreated());
        almacen.guardar(Canales.BANDERA_CORREO_CAIDO, "true", null);
        try {
            postCon(c, "/alertas/evaluar", "{}").andExpect(jsonPath("$.disparadas").value(0)).andExpect(jsonPath("$.pendientes").value(1));
            getCon(c, "/alertas").andExpect(jsonPath("$[0].pendiente_envio").value(true));
        } finally {
            almacen.guardar(Canales.BANDERA_CORREO_CAIDO, "false", null);
        }
        postCon(c, "/alertas/evaluar", "{}").andExpect(jsonPath("$.disparadas").value(1));
        getCon(c, "/alertas").andExpect(jsonPath("$[0].pendiente_envio").value(false));
    }

    @Test
    void alertasEstadoBorradoYPrivacidad() throws Exception {
        Cuenta a = registrar(), b = registrar();
        long id = ((Number) JsonPath.read(cuerpo(postCon(a, "/alertas", "{\"ticker\":\"QFN\",\"tipo\":\"PRECIO_LIMITE\",\"umbral\":999}")), "$.id")).longValue();
        patchCon(b, "/alertas/" + id, "{\"estado\":\"INACTIVA\"}").andExpect(status().isNotFound());
        deleteCon(b, "/alertas/" + id).andExpect(status().isNotFound());
        patchCon(a, "/alertas/" + id, "{\"estado\":\"PAUSADA\"}").andExpect(status().isUnprocessableEntity());
        patchCon(a, "/alertas/" + id, "{\"estado\":\"INACTIVA\"}").andExpect(jsonPath("$.estado").value("INACTIVA"));
        deleteCon(a, "/alertas/" + id).andExpect(status().isNoContent());
        getCon(a, "/alertas").andExpect(jsonPath("$", hasSize(0)));
    }
}
