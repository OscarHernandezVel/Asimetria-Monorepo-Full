package co.edu.ucc.asimetria.dominio;

import co.edu.ucc.asimetria.dominio.alerta.Condiciones;
import co.edu.ucc.asimetria.dominio.alerta.TipoCondicion;
import co.edu.ucc.asimetria.dominio.calculo.DatosInsuficientesException;
import co.edu.ucc.asimetria.dominio.calculo.MotorCalculo;
import co.edu.ucc.asimetria.dominio.calculo.ResultadoBeta;
import co.edu.ucc.asimetria.dominio.calculo.ResultadoSimulacion;
import co.edu.ucc.asimetria.dominio.mercado.Cotizacion;
import co.edu.ucc.asimetria.dominio.perfil.MetricaActivo;
import co.edu.ucc.asimetria.dominio.perfil.MotorRecomendacion;
import co.edu.ucc.asimetria.dominio.perfil.MotorRecomendacion.Filtros;
import co.edu.ucc.asimetria.dominio.perfil.PerfilRiesgo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Criterios de aceptación del documento sobre el dominio (sin Spring ni base de datos). */
class DominioTest {

    @Test
    void hu03CasoRitechBetaAlcista2YBajista125() {
        ResultadoBeta b = MotorCalculo.betaAsimetrica(new double[]{0.04, -0.025}, new double[]{0.02, -0.02}, 1);
        assertThat(b.alcista()).isCloseTo(2.0, within(1e-12));
        assertThat(b.bajista()).isCloseTo(1.25, within(1e-12));
        assertThat(b.ratio()).isCloseTo(1.6, within(1e-12));
        assertThat(b.favorable()).isTrue();
    }

    @Test
    void hu03Ca4DatosInsuficientes() {
        assertThatThrownBy(() -> MotorCalculo.betaAsimetrica(new double[]{0.04}, new double[]{0.02}, 1))
                .isInstanceOf(DatosInsuficientesException.class).hasMessage(MotorCalculo.ERROR_DATOS);
        assertThatThrownBy(() -> MotorCalculo.betaAsimetrica(new double[]{0.1}, new double[]{0.1, 0.2}, 1))
                .isInstanceOf(DatosInsuficientesException.class);
    }

    @Test
    void metricasAuxiliares() {
        assertThat(MotorCalculo.retornos(new double[]{100, 110})).containsExactly(new double[]{0.1}, within(1e-12));
        assertThat(MotorCalculo.retornos(new double[]{1})).isEmpty();
        assertThat(MotorCalculo.beta(new double[]{0.1}, new double[]{0})).isNull();
        assertThat(MotorCalculo.maxDrawdown(new double[]{100, 120, 90, 130})).isCloseTo(-25, within(1e-9));
        assertThat(MotorCalculo.correlacion(new double[]{1, 2, 3}, new double[]{2, 4, 6})).isCloseTo(1.0, within(1e-12));
        assertThat(MotorCalculo.correlacion(new double[]{1}, new double[]{1})).isNull();
        assertThat(MotorCalculo.correlacion(new double[]{1, 1}, new double[]{2, 3})).isNull();
        assertThat(MotorCalculo.volatilidad(new double[]{0.01})).isZero();
        assertThat(MotorCalculo.volatilidad(new double[]{0.01, -0.01})).isGreaterThan(0);
        assertThat(MotorCalculo.media(new double[0])).isZero();
        assertThat(MotorCalculo.nivelVolatilidad(10)).isEqualTo("Baja");
        assertThat(MotorCalculo.nivelVolatilidad(25)).isEqualTo("Media");
        assertThat(MotorCalculo.nivelVolatilidad(40)).isEqualTo("Alta");
        assertThat(MotorCalculo.retornoEsperadoCapm(null, new double[]{0.01})).isNull();
        assertThat(MotorCalculo.retornoEsperadoCapm(1.0, new double[]{0.001, 0.001})).isCloseTo(25.2, within(1e-9));
    }

    @Test
    void hu07Ca3QuizClasificaEnTresPerfiles() {
        assertThat(MotorCalculo.perfilDesdeQuiz(List.of(1, 1, 1, 2, 1))).isEqualTo(PerfilRiesgo.CONSERVADOR);
        assertThat(MotorCalculo.perfilDesdeQuiz(List.of(2, 2, 2, 2, 2))).isEqualTo(PerfilRiesgo.MODERADO);
        assertThat(MotorCalculo.perfilDesdeQuiz(List.of(3, 3, 3, 2, 3))).isEqualTo(PerfilRiesgo.AGRESIVO);
        assertThatThrownBy(() -> MotorCalculo.perfilDesdeQuiz(List.of(1, 4))).hasMessageContaining("todas");
        assertThatThrownBy(() -> MotorCalculo.perfilDesdeQuiz(List.of())).hasMessageContaining("todas");
    }

    @Test
    void hu05Ca1Ca2Backtesting1040Y975() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        ResultadoSimulacion alza = ResultadoSimulacion.builder(1000).sesion(d, 100, 100).sesion(d.plusDays(1), 104, 102).construir();
        ResultadoSimulacion baja = ResultadoSimulacion.builder(1000).sesion(d, 100, 100).sesion(d.plusDays(1), 97.5, 98).construir();
        assertThat(alza.valorFinal()).isEqualTo(1040.0);
        assertThat(alza.valorIndice()).isEqualTo(1020.0);
        assertThat(alza.retorno()).isCloseTo(4.0, within(1e-9));
        assertThat(baja.valorFinal()).isEqualTo(975.0);
        assertThat(alza.serie()).hasSize(2);
    }

    @Test
    void hu05Ca4DatosInvalidos() {
        assertThatThrownBy(() -> ResultadoSimulacion.builder(0)).hasMessageContaining("mayor que 0");
        assertThatThrownBy(() -> ResultadoSimulacion.builder(-5)).hasMessageContaining("mayor que 0");
        assertThatThrownBy(() -> ResultadoSimulacion.builder(Double.NaN)).hasMessageContaining("mayor que 0");
        assertThatThrownBy(() -> ResultadoSimulacion.builder(100).sesion(LocalDate.now(), 1, 1).construir())
                .hasMessageContaining("No hay datos");
    }

    private static final List<MetricaActivo> ACTIVOS = List.of(
            new MetricaActivo("A", 1.0, 1.0, 15, 7.5),    // baja volatilidad
            new MetricaActivo("B", 2.0, 1.25, 30, 12),    // asimétrico
            new MetricaActivo("C", 2.5, 2.4, 50, 20),     // muy volátil, captura al alza
            new MetricaActivo("D", 0.6, 0.4, 18, 6));     // muy defensivo

    @Test
    void hu04Ca1ConservadorSoloVeActivosDentroDeSuMargen() {
        var r = MotorRecomendacion.recomendar(ACTIVOS, PerfilRiesgo.CONSERVADOR, Filtros.ninguno());
        assertThat(r).extracting(x -> x.activo().ticker()).containsExactlyInAnyOrder("A", "D");
        // Strategy: el conservador premia la beta bajista pequeña → D antes que A
        assertThat(r.get(0).activo().ticker()).isEqualTo("D");
    }

    @Test
    void hu04Ca3CadaPerfilPriorizaDistinto() {
        var moderado = MotorRecomendacion.recomendar(ACTIVOS, PerfilRiesgo.MODERADO, Filtros.ninguno());
        var agresivo = MotorRecomendacion.recomendar(ACTIVOS, PerfilRiesgo.AGRESIVO, Filtros.ninguno());
        assertThat(moderado).extracting(x -> x.activo().ticker()).doesNotContain("C");
        assertThat(moderado.get(0).activo().ticker()).isEqualTo("B");
        assertThat(agresivo.get(0).activo().ticker()).isEqualTo("C");
        assertThat(agresivo).hasSize(4);
        assertThat(agresivo).isSortedAccordingTo((x, y) -> Double.compare(y.puntaje(), x.puntaje()));
    }

    @Test
    void hu04Ca2Ca4Ca5FiltrosSinResultadosYSinPerfil() {
        var f = new Filtros(25.0, 7.0, 0.4, 50);
        assertThat(MotorRecomendacion.recomendar(ACTIVOS, PerfilRiesgo.AGRESIVO, f)).extracting(x -> x.activo().ticker()).containsExactly("A");
        assertThat(MotorRecomendacion.recomendar(ACTIVOS, PerfilRiesgo.AGRESIVO, new Filtros(null, 99.0, null, 50))).isEmpty();
        assertThat(MotorRecomendacion.recomendar(ACTIVOS, PerfilRiesgo.AGRESIVO, new Filtros(null, null, null, 2))).hasSize(2);
        assertThatThrownBy(() -> MotorRecomendacion.recomendar(ACTIVOS, null, Filtros.ninguno()))
                .isInstanceOf(MotorRecomendacion.PerfilRequeridoException.class).hasMessageContaining("quiz");
        assertThat(PerfilRiesgo.desde(null)).isNull();
        assertThat(PerfilRiesgo.desde("MODERADO").volatilidadMaxima()).isEqualTo(35.0);
    }

    @Test
    void hu06CondicionesYFabrica() {
        var variacion = Condiciones.de(TipoCondicion.VARIACION_PORCENTUAL);
        var precio = Condiciones.de("PRECIO_LIMITE");
        assertThat(variacion.seCumple(Cotizacion.de(104.02, 100), 3)).isTrue();   // +4.02 %
        assertThat(variacion.seCumple(Cotizacion.de(96.5, 100), 3)).isTrue();     // −3.5 %
        assertThat(variacion.seCumple(Cotizacion.de(98.8, 100), 3)).isFalse();    // −1.2 %
        assertThat(precio.seCumple(Cotizacion.de(212.4, 210), 200)).isTrue();
        assertThat(precio.seCumple(Cotizacion.de(199, 210), 200)).isFalse();
        assertThat(variacion.describir("RiTech", Cotizacion.de(104, 100), 3)).isEqualTo("RiTech varió +4.00% (umbral 3%)");
        assertThat(variacion.describir("RiTech", Cotizacion.de(104, 100), 2.5)).contains("umbral 2.5%");
        assertThat(precio.describir("NASDAQ 100", Cotizacion.de(18942.6, 18900), 1)).isEqualTo("NASDAQ 100 alcanzó $18,942.60");
        assertThat(Cotizacion.de(1, 0).variacion()).isZero();
        assertThatThrownBy(() -> Condiciones.de("OTRO")).isInstanceOf(IllegalArgumentException.class);
    }
}
