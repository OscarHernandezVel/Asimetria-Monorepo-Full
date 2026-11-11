package co.edu.ucc.asimetria.alerta.notificacion;

import co.edu.ucc.asimetria.alerta.notificacion.CanalNotificacion.ErrorNotificacion;
import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente HTTP de SendGrid ({@code POST /v3/mail/send}). Cada llamada queda trazada; la clave va
 * en la cabecera Authorization y nunca se registra. Con {@code sandbox} SendGrid valida clave y
 * remitente pero no entrega el correo (prueba de integración sin molestar a nadie).
 */
public class ClienteSendGrid {

    private final RestClient http;
    private final PropiedadesAsimetria.SendGrid config;
    private final AuditoriaService auditoria;

    public ClienteSendGrid(RestClient http, PropiedadesAsimetria.SendGrid config, AuditoriaService auditoria) {
        this.http = http;
        this.config = config;
        this.auditoria = auditoria;
    }

    public boolean configurado() {
        return config.configurado();
    }

    /** @return X-Message-Id para rastrear la entrega en el panel de SendGrid */
    public String enviar(String destino, String asunto, String texto, boolean sandbox) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("personalizations", List.of(Map.of("to", List.of(Map.of("email", destino)))));
        cuerpo.put("from", Map.of("email", config.remitente()));
        cuerpo.put("subject", asunto);
        cuerpo.put("content", List.of(Map.of("type", "text/plain", "value", texto)));
        if (sandbox) {
            cuerpo.put("mail_settings", Map.of("sandbox_mode", Map.of("enable", true)));
        }
        String operacion = sandbox ? "mail.send (sandbox)" : "mail.send";
        long t0 = System.nanoTime();
        ResponseEntity<String> r;
        try {
            r = http.post().header("Authorization", "Bearer " + config.clave()).body(cuerpo).retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { /* se trata abajo */ })
                    .toEntity(String.class);
        } catch (RestClientException e) {
            auditoria.registrarLlamada("SENDGRID", operacion, false, null, ms(t0), e.getMessage());
            throw new ErrorNotificacion("No fue posible conectar con SendGrid");
        }
        String id = r.getHeaders().getFirst("X-Message-Id");
        boolean ok = r.getStatusCode().is2xxSuccessful();
        auditoria.registrarLlamada("SENDGRID", operacion, ok, r.getStatusCode().value(), ms(t0),
                id != null ? "message-id " + id : r.getBody());
        if (!ok) {
            throw new ErrorNotificacion("SendGrid respondió " + r.getStatusCode().value());
        }
        return id;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
