package co.edu.ucc.asimetria.mercado.proveedor;

/** Fallo del proveedor externo. Jerarquía: límite de cuota y circuito abierto son casos particulares. */
public class ErrorProveedor extends RuntimeException {

    public ErrorProveedor(String mensaje) {
        super(mensaje);
    }

    /** HU-01 CA-3: el proveedor (o la cuota local) rechazó la petición por límite. */
    public static class LimiteExcedido extends ErrorProveedor {
        public LimiteExcedido(String mensaje) {
            super(mensaje);
        }
    }

    /** El circuit breaker está abierto: no se llama al proveedor y se usan los datos almacenados. */
    public static class CircuitoAbierto extends ErrorProveedor {
        public CircuitoAbierto(String mensaje) {
            super(mensaje);
        }
    }
}
