package co.edu.ucc.asimetria;

import co.edu.ucc.asimetria.mercado.CotizacionService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RNF-05 (salud), RNF-06 (fallos del proveedor), trazabilidad de integraciones y cabeceras de seguridad. */
class OperacionApiTest extends PruebaIntegracion {

    @Autowired
    private CotizacionService cotizaciones;
    @Autowired
    private co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido almacen;

    @Test
    void rnf05HealthYCabecerasDeSeguridad() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ok")).andExpect(jsonPath("$.db").value(true))
                .andExpect(jsonPath("$.cache_tipo").value("memoria"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void rnf06ProveedorCaidoMantieneElUltimoDatoSin500() throws Exception {
        Cuenta c = registrar();
        cotizaciones.sincronizar();
        String antes = cuerpo(getCon(c, "/mercado/cotizaciones"));
        putCon(c, "/demo/fallos", "{\"proveedor_caido\":true,\"correo_caido\":false}").andExpect(status().isOk());
        try {
            getCon(c, "/demo/fallos").andExpect(jsonPath("$.proveedor_caido").value(true));
            for (int i = 0; i < 3; i++) {                    // 3 ciclos fallidos abren el circuito
                assertThat(cotizaciones.sincronizar().desactualizado()).isTrue();
            }
            String despues = cuerpo(getCon(c, "/mercado/cotizaciones").andExpect(status().isOk())
                    .andExpect(jsonPath("$.estado.desactualizado").value(true)));
            assertThat(JsonPath.<Object>read(despues, "$.cotizaciones.RTC.precio")).isEqualTo(JsonPath.read(antes, "$.cotizaciones.RTC.precio"));
            getCon(c, "/integraciones").andExpect(jsonPath("$.proveedores[0].circuito").value("ABIERTO"));
        } finally {
            putCon(c, "/demo/fallos", "{\"proveedor_caido\":false,\"correo_caido\":false}");
            almacen.eliminar("cb:simulado:abierto");   // no dejar el circuito abierto para otras pruebas
            almacen.eliminar("cb:simulado:fallos");
        }
    }

    @Test
    void integracionesMuestraEstadoYOrigenDeLosPrecios() throws Exception {
        Cuenta c = registrar();
        getCon(c, "/integraciones").andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedores[*].proveedor", hasItems("ALPHAVANTAGE", "SENDGRID")))
                .andExpect(jsonPath("$.proveedores[0].cuota_limite_diaria").value(25))
                .andExpect(jsonPath("$.origen_precios[*].fuente", hasItems("SIMULADO")))
                .andExpect(jsonPath("$.recientes").isArray());
        postCon(c, "/integraciones/probar", "{}").andExpect(jsonPath("$.ALPHAVANTAGE.ok").value(false))
                .andExpect(jsonPath("$.SENDGRID.detalle").value(org.hamcrest.Matchers.containsString("No configurado")));
    }

    @Test
    void erroresConContratoDeLaApi() throws Exception {
        Cuenta c = registrar();
        getCon(c, "/no-existe").andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").exists());
        postCon(c, "/watchlist", "{json roto").andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/openapi.json")).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").exists());
    }
}
