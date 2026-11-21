package co.edu.uptc.gateway.datalake;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Datalake local de peticiones. Solo se activa con gateway.datalake.enabled=true (por defecto en
 * local). En AWS se apaga con DATALAKE_ENABLED=false y entonces ni siquiera se carga DuckDB.
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.datalake", name = "enabled", havingValue = "true")
public class DatalakeConfig {

    @Bean(destroyMethod = "cerrar")
    ParquetDatalakeWriter parquetDatalakeWriter(
            @Value("${gateway.datalake.dir:./datalake}") String dir,
            @Value("${gateway.datalake.flush-interval:30s}") Duration flushInterval,
            @Value("${gateway.datalake.batch-size:1000}") int batchSize) {
        return new ParquetDatalakeWriter(Path.of(dir), flushInterval.toMillis(), batchSize);
    }

    @Bean
    PeticionesLogFilter peticionesLogFilter(ParquetDatalakeWriter writer) {
        return new PeticionesLogFilter(writer);
    }
}
