package co.edu.ucc.asimetria.dominio.calculo;

/** Sensibilidad asimétrica de un activo frente al índice (HU-03). */
public record ResultadoBeta(double alcista, double bajista, Double global, Double ratio) {

    /** «Sube más de lo que cae» (HU-03 CA-2). */
    public boolean favorable() {
        return alcista > bajista;
    }
}
