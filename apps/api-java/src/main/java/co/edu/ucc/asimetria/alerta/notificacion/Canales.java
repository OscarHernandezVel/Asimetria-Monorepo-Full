package co.edu.ucc.asimetria.alerta.notificacion;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import co.edu.ucc.asimetria.mercado.proveedor.FabricaProveedores;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Eslabones concretos y ensamblado de la cadena: correo (SendGrid) → en la app. */
@Configuration
public class Canales {

    public static final String BANDERA_CORREO_CAIDO = "sim:correo_caido";

    /** Atiende los mensajes de canal CORREO cuando hay clave de SendGrid; los demás pasan al siguiente. */
    static final class CanalCorreo extends CanalNotificacion {
        private final ClienteSendGrid sendgrid;
        private final AlmacenCompartido almacen;

        CanalCorreo(ClienteSendGrid sendgrid, AlmacenCompartido almacen) {
            this.sendgrid = sendgrid;
            this.almacen = almacen;
        }

        @Override
        protected boolean atiende(Mensaje m) {
            if ("CORREO".equals(m.canal()) && almacen.obtener(BANDERA_CORREO_CAIDO).map(Boolean::parseBoolean).orElse(false)) {
                throw new ErrorNotificacion("Servicio de correo no disponible");   // inyección de fallos (HU-06 CA-4)
            }
            return "CORREO".equals(m.canal()) && sendgrid.configurado();
        }

        @Override
        protected ResultadoEnvio entregar(Mensaje m) {
            return new ResultadoEnvio("ENVIADO_CORREO", sendgrid.enviar(m.destinatario(), m.asunto(), m.texto(), false));
        }
    }

    /** Último eslabón: la notificación queda visible en la app (siempre disponible). */
    static final class CanalEnApp extends CanalNotificacion {
        @Override
        protected boolean atiende(Mensaje m) {
            return true;
        }

        @Override
        protected ResultadoEnvio entregar(Mensaje m) {
            return new ResultadoEnvio("EN_APP", null);
        }
    }

    @Bean
    public ClienteSendGrid clienteSendGrid(PropiedadesAsimetria props, AuditoriaService auditoria) {
        return new ClienteSendGrid(FabricaProveedores.clienteHttp("https://api.sendgrid.com/v3/mail/send"), props.sendgrid(), auditoria);
    }

    @Bean
    public CanalNotificacion cadenaNotificaciones(ClienteSendGrid sendgrid, AlmacenCompartido almacen) {
        CanalNotificacion inicio = new CanalCorreo(sendgrid, almacen);
        inicio.luego(new CanalEnApp());
        return inicio;
    }
}
