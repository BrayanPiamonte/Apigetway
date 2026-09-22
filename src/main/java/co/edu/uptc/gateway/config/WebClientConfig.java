package co.edu.uptc.gateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * WebClient que el Gateway usa para llamar a los módulos desde su propia lógica (hoy, solo el
 * endpoint de composición /api/estudiantes/{id}/detalle). Es independiente del cliente HTTP que
 * usa el enrutamiento simple (ese se configura por separado en spring.cloud.gateway.httpclient).
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient compositionWebClient(GatewayProperties props) {
        int timeoutMs = (int) props.compositionTimeout().toMillis();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(props.compositionTimeout())
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();
    }
}
