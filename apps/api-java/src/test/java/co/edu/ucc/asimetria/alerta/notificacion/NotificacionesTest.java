package co.edu.ucc.asimetria.alerta.notificacion;

import co.edu.ucc.asimetria.alerta.notificacion.CanalNotificacion.ErrorNotificacion;
import co.edu.ucc.asimetria.alerta.notificacion.CanalNotificacion.Mensaje;
import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.comun.almacen.AlmacenMemoria;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Cliente SendGrid (sin red: MockRestServiceServer) y cadena de responsabilidad de canales. */
class NotificacionesTest {

    private record SendGrid(ClienteSendGrid cliente, MockRestServiceServer servidor, AuditoriaService auditoria) {
    }

    private static SendGrid sendGrid(String clave) {
        RestClient.Builder b = RestClient.builder().baseUrl("https://api.sendgrid.com/v3/mail/send");
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        AuditoriaService a = mock(AuditoriaService.class);
        return new SendGrid(new ClienteSendGrid(b.build(), new PropiedadesAsimetria.SendGrid(clave, "alertas@asimetria.app"), a), s, a);
    }

    @Test
    void sendGridDevuelveMessageIdYTraza() {
        SendGrid sg = sendGrid("SG.prueba");
        sg.servidor().expect(header("Authorization", "Bearer SG.prueba")).andExpect(content().string(not(containsString("sandbox"))))
                .andRespond(withStatus(HttpStatus.ACCEPTED).header("X-Message-Id", "abc123"));
        assertThat(sg.cliente().enviar("a@prueba.test", "s", "t", false)).isEqualTo("abc123");
        verify(sg.auditoria()).registrarLlamada(eq("SENDGRID"), eq("mail.send"), eq(true), eq(202), anyLong(), eq("message-id abc123"));
        sg.servidor().verify();
    }

    @Test
    void sendGridSandboxNoEntregaYErroresSonErrorNotificacion() {
        SendGrid sg = sendGrid("SG.prueba");
        sg.servidor().expect(content().string(containsString("\"sandbox_mode\""))).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        sg.servidor().expect(content().string(containsString("subject"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"errors\":[]}"));
        assertThat(sg.cliente().enviar("a@prueba.test", "s", "t", true)).isNull();
        assertThatThrownBy(() -> sg.cliente().enviar("a@prueba.test", "s", "t", false))
                .isInstanceOf(ErrorNotificacion.class).hasMessageContaining("401");
        verify(sg.auditoria()).registrarLlamada(eq("SENDGRID"), eq("mail.send"), eq(false), eq(401), anyLong(), eq("{\"errors\":[]}"));
        assertThat(sendGrid("").cliente().configurado()).isFalse();
    }

    @Test
    void cadenaCorreoConClaveYEnAppComoRespaldo() {
        AlmacenMemoria almacen = new AlmacenMemoria(Clock.systemUTC());
        SendGrid conClave = sendGrid("SG.prueba");
        conClave.servidor().expect(header("Authorization", "Bearer SG.prueba")).andRespond(withStatus(HttpStatus.ACCEPTED).header("X-Message-Id", "m1"));
        CanalNotificacion cadena = new Canales().cadenaNotificaciones(conClave.cliente(), almacen);
        assertThat(cadena.enviar(new Mensaje("a@prueba.test", "s", "t", "CORREO"))).isEqualTo(new CanalNotificacion.ResultadoEnvio("ENVIADO_CORREO", "m1"));
        assertThat(cadena.enviar(new Mensaje("a@prueba.test", "s", "t", "PUSH")).estadoEnvio()).isEqualTo("EN_APP");

        CanalNotificacion sinClave = new Canales().cadenaNotificaciones(sendGrid("").cliente(), almacen);
        assertThat(sinClave.enviar(new Mensaje("a@prueba.test", "s", "t", "CORREO")).estadoEnvio()).isEqualTo("EN_APP");

        almacen.guardar(Canales.BANDERA_CORREO_CAIDO, "true", null);   // inyección de fallos
        assertThatThrownBy(() -> sinClave.enviar(new Mensaje("a@prueba.test", "s", "t", "CORREO"))).isInstanceOf(ErrorNotificacion.class);
        assertThat(sinClave.enviar(new Mensaje("a@prueba.test", "s", "t", "PUSH")).estadoEnvio()).isEqualTo("EN_APP");
    }

    @Test
    void cadenaSinEslabonFinalInformaElError() {
        CanalNotificacion soloCorreo = new Canales.CanalCorreo(sendGrid("").cliente(), new AlmacenMemoria(Clock.systemUTC()));
        assertThatThrownBy(() -> soloCorreo.enviar(new Mensaje("a", "s", "t", "PUSH"))).isInstanceOf(ErrorNotificacion.class)
                .hasMessageContaining("Ningún canal");
    }
}
