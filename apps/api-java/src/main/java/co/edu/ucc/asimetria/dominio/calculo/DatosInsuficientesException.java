package co.edu.ucc.asimetria.dominio.calculo;

/** HU-03 CA-4: no hay sesiones suficientes para una beta confiable. */
public class DatosInsuficientesException extends RuntimeException {

    public DatosInsuficientesException(String mensaje) {
        super(mensaje);
    }
}
