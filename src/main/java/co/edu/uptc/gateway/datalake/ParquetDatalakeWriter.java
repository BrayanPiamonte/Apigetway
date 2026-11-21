package co.edu.uptc.gateway.datalake;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Escribe el datalake local de peticiones en Parquet, particionado al estilo Hive:
 * <pre>
 *   {dir}/peticiones/anio=2026/mes=11/dia=21/peticiones_HHmmss_xxxxxxxx.parquet
 * </pre>
 * Las peticiones se encolan en memoria (sin bloquear la respuesta) y se vuelcan en lotes:
 * cada {@code flushInterval}, al llegar a {@code batchSize} registros, y al apagar el Gateway.
 * Para escribir Parquet se usa DuckDB embebido (un archivo .jar, sin Hadoop).
 * Un fallo al escribir solo deja un WARN: nunca afecta a las peticiones.
 */
public class ParquetDatalakeWriter {

    private static final Logger log = LoggerFactory.getLogger(ParquetDatalakeWriter.class);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HHmmss");

    static final String COLUMNAS = """
            request_id VARCHAR, ts TIMESTAMP, metodo VARCHAR, path VARCHAR, ruta VARCHAR,
            status INTEGER, duracion_ms INTEGER, usuario VARCHAR, rol VARCHAR,
            estudiante_id BIGINT, curso_id BIGINT, ip_cliente VARCHAR, cache VARCHAR,
            idempotency_key VARCHAR, user_agent VARCHAR, origen VARCHAR""";

    private final Path baseDir;
    private final int batchSize;
    private final ConcurrentLinkedQueue<RegistroPeticion> cola = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendientes = new AtomicInteger();
    private final ScheduledExecutorService scheduler;
    private Connection duck;

    public ParquetDatalakeWriter(Path baseDir, long flushIntervalMs, int batchSize) {
        this.baseDir = baseDir.toAbsolutePath();
        this.batchSize = batchSize;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "datalake-writer");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flushSeguro, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        log.info("Datalake de peticiones (Parquet) en {}", this.baseDir.resolve("peticiones"));
    }

    /** Encola sin bloquear. Si se acumulan muchas, se pide un volcado anticipado. */
    public void registrar(RegistroPeticion registro) {
        cola.add(registro);
        if (pendientes.incrementAndGet() >= batchSize) {
            scheduler.execute(this::flushSeguro);
        }
    }

    private void flushSeguro() {
        try {
            flush();
        } catch (Exception e) {
            log.warn("No se pudo escribir el datalake de peticiones: {}", e.toString());
        }
    }

    /** Vuelca lo pendiente: un archivo Parquet por día presente en el lote. */
    synchronized void flush() throws SQLException, IOException {
        List<RegistroPeticion> lote = new ArrayList<>();
        RegistroPeticion r;
        while ((r = cola.poll()) != null) {
            lote.add(r);
            pendientes.decrementAndGet();
        }
        if (lote.isEmpty()) {
            return;
        }
        Map<LocalDate, List<RegistroPeticion>> porDia = new TreeMap<>();
        for (RegistroPeticion reg : lote) {
            porDia.computeIfAbsent(reg.ts().toLocalDate(), d -> new ArrayList<>()).add(reg);
        }
        for (Map.Entry<LocalDate, List<RegistroPeticion>> dia : porDia.entrySet()) {
            escribirDia(dia.getKey(), dia.getValue());
        }
    }

    private void escribirDia(LocalDate dia, List<RegistroPeticion> registros) throws SQLException, IOException {
        Path carpeta = baseDir.resolve("peticiones")
                .resolve("anio=" + dia.getYear())
                .resolve(String.format("mes=%02d", dia.getMonthValue()))
                .resolve(String.format("dia=%02d", dia.getDayOfMonth()));
        Files.createDirectories(carpeta);
        String nombre = "peticiones_" + LocalDateTime.now().format(HORA) + "_"
                + UUID.randomUUID().toString().substring(0, 8) + ".parquet";
        Path archivo = carpeta.resolve(nombre);

        Connection c = conexion();
        try (Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP TABLE lote (" + COLUMNAS + ")");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO lote VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (RegistroPeticion reg : registros) {
                ps.setString(1, reg.requestId());
                ps.setTimestamp(2, Timestamp.valueOf(reg.ts()));
                ps.setString(3, reg.metodo());
                ps.setString(4, reg.path());
                ps.setString(5, reg.ruta());
                ps.setInt(6, reg.status());
                ps.setInt(7, reg.duracionMs());
                texto(ps, 8, reg.usuario());
                texto(ps, 9, reg.rol());
                numero(ps, 10, reg.estudianteId());
                numero(ps, 11, reg.cursoId());
                texto(ps, 12, reg.ipCliente());
                texto(ps, 13, reg.cache());
                texto(ps, 14, reg.idempotencyKey());
                texto(ps, 15, reg.userAgent());
                ps.setString(16, reg.origen());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        String destino = archivo.toString().replace('\\', '/').replace("'", "''");
        try (Statement st = c.createStatement()) {
            st.execute("COPY lote TO '" + destino + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
            st.execute("DROP TABLE lote");
        }
        log.debug("Datalake: {} peticiones -> {}", registros.size(), archivo);
    }

    private Connection conexion() throws SQLException {
        if (duck == null || duck.isClosed()) {
            duck = DriverManager.getConnection("jdbc:duckdb:"); // base en memoria, solo para escribir Parquet
        }
        return duck;
    }

    private static void texto(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
    }

    private static void numero(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT); else ps.setLong(i, v);
    }

    @PreDestroy
    public void cerrar() {
        scheduler.shutdown();
        flushSeguro();
        try {
            if (duck != null) duck.close();
        } catch (SQLException ignored) {
            // se está apagando
        }
    }
}
