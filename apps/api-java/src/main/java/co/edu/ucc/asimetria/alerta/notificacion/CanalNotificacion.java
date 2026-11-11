package co.edu.ucc.asimetria.alerta.notificacion;

/**
 * Eslabón de la <b>cadena de responsabilidad</b> de notificaciones (HU-06).
 *
 * <p>Cada canal decide si atiende el mensaje; si no, lo pasa al siguiente. Hoy: correo (SendGrid)
 * → en la app. Añadir SMS o push real es insertar un eslabón, sin tocar el evaluador de alertas.
 * Si un canal acepta el mensaje y falla, lanza {@link ErrorNotificacion}: la alerta queda pendiente
 * y se reintenta en la siguiente pasada (CA-4: la alerta no se pierde).
 */
public abstract class CanalNotificacion {

    public record Mensaje(String destinatario, String asunto, String texto, String canal) {
    }

    /** Resultado del envío: estado guardado en {@code notificaciones} y el id del proveedor (rastreo). */
    public record ResultadoEnvio(String estadoEnvio, String proveedorId) {
    }

    public static class ErrorNotificacion extends RuntimeException {
        public ErrorNotificacion(String mensaje) {
            super(mensaje);
        }
    }

    private CanalNotificacion siguiente;

    public CanalNotificacion luego(CanalNotificacion siguiente) {
        this.siguiente = siguiente;
        return siguiente;
    }

    public final ResultadoEnvio enviar(Mensaje m) {
        if (atiende(m)) {
            return entregar(m);
        }
        if (siguiente == null) {
            throw new ErrorNotificacion("Ningún canal disponible para " + m.canal());
        }
        return siguiente.enviar(m);
    }

    protected abstract boolean atiende(Mensaje m);

    protected abstract ResultadoEnvio entregar(Mensaje m);
}
