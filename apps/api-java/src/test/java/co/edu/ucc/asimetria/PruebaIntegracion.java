package co.edu.ucc.asimetria;

import co.edu.ucc.asimetria.mercado.IngestaService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base de las pruebas de integración: la aplicación completa contra un PostgreSQL + TimescaleDB real
 * (Testcontainers), con 5 años de datos simulados. Un único contenedor para todas las clases.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class PruebaIntegracion {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    protected static final String V1 = "/api/v1";
    private static volatile boolean cargado;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    private IngestaService ingesta;

    @BeforeEach
    void cargarDatosUnaVez() {
        if (!cargado) {
            synchronized (PruebaIntegracion.class) {
                if (!cargado) {
                    ingesta.cargaInicial();
                    cargado = true;
                }
            }
        }
    }

    protected record Cuenta(String token, String email, long id) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    /** Registra un usuario nuevo (correo único) y devuelve su token. */
    protected Cuenta registrar(String perfil) throws Exception {
        String email = "u" + UUID.randomUUID().toString().substring(0, 10) + "@prueba.test";
        String perfilJson = perfil == null ? "null" : "\"" + perfil + "\"";
        String r = mvc.perform(post(V1 + "/auth/registro").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nombre":"Usuario Prueba","email":"%s","password":"Prueba2026x","perfil":%s}""".formatted(email, perfilJson)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Cuenta(JsonPath.read(r, "$.access_token"), email, ((Number) JsonPath.read(r, "$.usuario.id")).longValue());
    }

    protected Cuenta registrar() throws Exception {
        return registrar("MODERADO");
    }

    protected ResultActions getCon(Cuenta c, String ruta) throws Exception {
        return mvc.perform(get(V1 + ruta).header("Authorization", c.bearer()));
    }

    protected ResultActions postCon(Cuenta c, String ruta, String json) throws Exception {
        return mvc.perform(post(V1 + ruta).header("Authorization", c.bearer()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions patchCon(Cuenta c, String ruta, String json) throws Exception {
        return mvc.perform(patch(V1 + ruta).header("Authorization", c.bearer()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions putCon(Cuenta c, String ruta, String json) throws Exception {
        return mvc.perform(put(V1 + ruta).header("Authorization", c.bearer()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions deleteCon(Cuenta c, String ruta) throws Exception {
        return mvc.perform(delete(V1 + ruta).header("Authorization", c.bearer()));
    }

    protected static String cuerpo(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }
}
