package co.edu.ucc.asimetria.tareas;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import co.edu.ucc.asimetria.mercado.CotizacionService;
import co.edu.ucc.asimetria.mercado.IngestaService;
import co.edu.ucc.asimetria.usuario.UsuarioService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cada tarea corre bajo su propio bloqueo; sin bloqueo (otra instancia lo tiene) no hace nada. */
class TareasProgramadasTest {

    private static PropiedadesAsimetria props(boolean demo) {
        return new PropiedadesAsimetria(new PropiedadesAsimetria.Jwt("x".repeat(32), 120), 4, "simulado",
                new PropiedadesAsimetria.AlphaVantage("", 25), new PropiedadesAsimetria.SendGrid("", "a@b.c"),
                Duration.ofSeconds(5), "memoria", demo, false, true, true, 0, new PropiedadesAsimetria.LimiteTasa(false, 1, 1, 1, 1));
    }

    @Test
    void conBloqueoEjecutaCadaTareaYSinBloqueoLaOmite() {
        BloqueoDistribuido conCandado = mock(BloqueoDistribuido.class);
        when(conCandado.ejecutar(anyLong(), any())).thenAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return true;
        });
        IngestaService ingesta = mock(IngestaService.class);
        CotizacionService cot = mock(CotizacionService.class);
        AuditoriaService auditoria = mock(AuditoriaService.class);
        UsuarioService usuarios = mock(UsuarioService.class);
        Clock reloj = Clock.fixed(Instant.parse("2026-11-09T21:30:00Z"), ZoneOffset.UTC);
        TareasProgramadas t = new TareasProgramadas(conCandado, ingesta, cot, auditoria, usuarios, props(true), reloj);
        t.alArrancar();
        t.sincronizarCotizaciones();
        t.cierreDiario();
        t.purgarAuditoria();
        verify(ingesta).cargaInicial();
        verify(usuarios).crearDemo();
        verify(cot).sincronizar();
        verify(ingesta).cierreDiario(LocalDate.of(2026, 11, 9));
        verify(auditoria).purgar();

        BloqueoDistribuido ocupado = mock(BloqueoDistribuido.class);   // otra instancia tiene el candado
        IngestaService otra = mock(IngestaService.class);
        new TareasProgramadas(ocupado, otra, cot, auditoria, usuarios, props(false), reloj).alArrancar();
        verify(otra, never()).cargaInicial();
    }
}
