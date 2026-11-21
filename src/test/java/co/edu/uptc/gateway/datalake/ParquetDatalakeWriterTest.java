package co.edu.uptc.gateway.datalake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParquetDatalakeWriterTest {

    @TempDir
    Path dir;

    @Test
    void escribeUnParquetPorDiaParticionadoPorAnioMesDia() throws Exception {
        ParquetDatalakeWriter writer = new ParquetDatalakeWriter(dir, 60_000, 1000);
        LocalDateTime hoy = LocalDateTime.of(2026, 11, 21, 10, 15);
        writer.registrar(registro("a", hoy, 201, null));
        writer.registrar(registro("b", hoy.plusSeconds(1), 409, "k-1"));
        writer.registrar(registro("c", LocalDateTime.of(2026, 7, 20, 8, 0), 200, null));
        writer.flush();
        writer.cerrar();

        Path noviembre = dir.resolve("peticiones/anio=2026/mes=11/dia=21");
        Path julio = dir.resolve("peticiones/anio=2026/mes=07/dia=20");
        assertTrue(Files.isDirectory(noviembre));
        assertTrue(Files.isDirectory(julio));
        try (Stream<Path> archivos = Files.list(noviembre)) {
            List<Path> parquet = archivos.toList();
            assertEquals(1, parquet.size());
            assertTrue(parquet.get(0).getFileName().toString().endsWith(".parquet"));
        }

        String glob = dir.toString().replace('\\', '/') + "/peticiones/**/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT count(*) AS total,
                            count(*) FILTER (WHERE status = 409) AS conflictos,
                            count(*) FILTER (WHERE anio = 2026 AND mes = 7) AS julio,
                            max(idempotency_key) AS clave
                       FROM read_parquet('%s', hive_partitioning = true)
                     """.formatted(glob))) {
            rs.next();
            assertEquals(3, rs.getLong("total"));
            assertEquals(1, rs.getLong("conflictos"));
            assertEquals(1, rs.getLong("julio"));
            assertEquals("k-1", rs.getString("clave"));
        }
    }

    @Test
    void clasificaLaRutaPorPath() {
        assertEquals("detalle", PeticionesLogFilter.ruta("/api/estudiantes/5/detalle"));
        assertEquals("inscripciones", PeticionesLogFilter.ruta("/api/inscripciones"));
        assertEquals("materias", PeticionesLogFilter.ruta("/api/materias/cursos/3"));
        assertTrue(PeticionesLogFilter.excluido("/health"));
    }

    private static RegistroPeticion registro(String id, LocalDateTime ts, int status, String key) {
        return new RegistroPeticion(id, ts, "POST", "/api/inscripciones", "inscripciones", status, 12,
                "admin", "ADMIN", 5L, null, "127.0.0.1", null, key, "test", "gateway");
    }
}
