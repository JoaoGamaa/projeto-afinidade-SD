package afinidade;

import afinidade.Protocol.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!cliente")
public class TcpServer {

    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);
    private final MusicRepository repository;
    private final RecommendationService recommendations;
    private final String host;
    private final int configuredPort, timeout;
    private final ThreadPoolExecutor pool;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptor;

    public TcpServer(
        MusicRepository repository,
        RecommendationService recommendations,
        @Value("${music.tcp.host}") String host,
        @Value("${music.tcp.port}") int port,
        @Value("${music.tcp.workers}") int workers,
        @Value("${music.tcp.queue-capacity}") int queue,
        @Value("${music.tcp.timeout-ms}") int timeout
    ) {
        this.repository = repository;
        this.recommendations = recommendations;
        this.host = host;
        this.configuredPort = port;
        this.timeout = timeout;
        AtomicInteger sequence = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queue),
            task -> new Thread(task, "tcp-client-" + sequence.incrementAndGet()),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @PostConstruct
    public void start() throws IOException {
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(host, configuredPort));
        running = true;
        acceptor = new Thread(this::acceptConnections, "tcp-acceptor");
        acceptor.start();
        log.info("Servidor TCP escutando em {}:{}; workers={}", host, port(), pool.getMaximumPoolSize());
    }

    public int port() {
        return listener.getLocalPort();
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket socket = listener.accept();
                socket.setSoTimeout(timeout);
                connections.add(socket);
                try {
                    pool.execute(() -> serve(socket));
                } catch (RejectedExecutionException busy) {
                    // Não fazemos escrita bloqueante na thread que aceita conexões.
                    connections.remove(socket);
                    socket.close();
                    log.warn("Limite de conexões atingido; conexão recusada.");
                }
            } catch (IOException error) {
                if (running) log.error("Erro ao aceitar conexão", error);
            }
        }
    }

    private void serve(Socket socket) {
        log.debug("Conexão aberta: {}", socket.getRemoteSocketAddress());
        try (
            socket;
            Reader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );
            Writer writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            )
        ) {
            String line;
            while (running && (line = Protocol.readLine(reader)) != null) {
                Response response;
                try {
                    Request request = Protocol.JSON.readValue(line, Request.class);
                    response = handle(request);
                    log.debug(
                        "Operação {} atendida por {}",
                        request.operation(),
                        Thread.currentThread().getName()
                    );
                } catch (JsonProcessingException invalid) {
                    response = Response.error(
                        "INVALID_REQUEST",
                        "JSON inválido ou campos incompatíveis com o protocolo."
                    );
                } catch (DomainException error) {
                    response = Response.error(error.code(), error.getMessage());
                } catch (Exception error) {
                    log.error("Falha ao processar requisição", error);
                    response = Response.error("INTERNAL_ERROR", "Falha interna ao processar a requisição.");
                }
                Protocol.writeLine(writer, response);
            }
        } catch (IOException error) {
            log.debug("Conexão encerrada: {}", error.getMessage());
        } finally {
            connections.remove(socket);
        }
    }

    public Response handle(Request request) {
        if (request == null || request.operation() == null) throw new DomainException(
            "INVALID_REQUEST",
            "Informe a operação."
        );
        return switch (request.operation()) {
            case "PING" -> Response.success(Map.of("status", "UP", "protocol", "JSONL/TCP", "version", 1));
            case "CATALOG" -> Response.success(repository.snapshot());
            case "UPDATE_RATINGS" -> {
                requireUser(request);
                if (request.version() == null) throw new DomainException(
                    "INVALID_REQUEST",
                    "Informe a versão atual do perfil."
                );
                yield Response.success(
                    repository.update(request.userId(), request.ratings(), request.version())
                );
            }
            case "RECOMMEND" -> {
                requireUser(request);
                yield Response.success(
                    recommendations.recommend(
                        repository.snapshot(),
                        request.userId(),
                        request.k() == null ? 3 : request.k(),
                        request.limit() == null ? 5 : request.limit()
                    )
                );
            }
            default -> throw new DomainException("INVALID_REQUEST", "Operação desconhecida.");
        };
    }

    private void requireUser(Request request) {
        if (request.userId() == null || request.userId() < 1) throw new DomainException(
            "INVALID_REQUEST",
            "Informe um userId positivo."
        );
    }

    @PreDestroy
    public void stop() {
        running = false;
        try {
            if (listener != null) listener.close();
        } catch (IOException ignored) {}
        connections.forEach(socket -> {
            try {
                socket.close();
            } catch (IOException ignored) {}
        });
        pool.shutdownNow();
        try {
            if (acceptor != null) acceptor.join(2000);
            pool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
