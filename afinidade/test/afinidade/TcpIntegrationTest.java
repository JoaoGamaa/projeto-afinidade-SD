package afinidade;

import static org.assertj.core.api.Assertions.*;

import afinidade.Models.*;
import afinidade.Protocol.*;
import afinidade.Protocol.DomainException;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@org.springframework.test.context.ActiveProfiles("servidor")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = { "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1", "music.tcp.port=0" }
)
class TcpIntegrationTest {

    @Autowired
    TcpServer server;

    @Autowired
    MusicRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void prepareTestData() {
        PersistenceTest.insertTestData(jdbc);
    }

    private Response exchange(Request request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10000);
            Protocol.writeLine(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                request
            );
            return Protocol.JSON.readValue(
                Protocol.readLine(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)),
                Response.class
            );
        }
    }

    @Test
    void returnsManuallyInsertedProfilesWithFifteenRatings() throws Exception {
        Response response = exchange(new Request("CATALOG", null, null, null, null, null));
        assertThat(response.ok()).isTrue();
        assertThat(response.data().get("artists")).hasSize(15);
        assertThat(response.data().get("users")).hasSize(2);
        response
            .data()
            .get("users")
            .forEach(user -> assertThat(user.get("ratings")).hasSize(15));
    }

    @Test
    void servesMultipleMessagesAndRecoversFromMalformedJson() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            Reader reader = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8);
            writer.write("not-json\n");
            writer.flush();
            assertThat(Protocol.JSON.readValue(Protocol.readLine(reader), Response.class).code()).isEqualTo(
                "INVALID_REQUEST"
            );
            Protocol.writeLine(writer, new Request("PING", null, null, null, null, null));
            assertThat(Protocol.JSON.readValue(Protocol.readLine(reader), Response.class).ok()).isTrue();
        }
    }

    @Test
    void handlesTwentyFourConcurrentTcpClients() throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(24);
        CountDownLatch ready = new CountDownLatch(24),
            start = new CountDownLatch(1);
        try {
            List<Future<Response>> responses = new ArrayList<>();
            for (int i = 0; i < 24; i++) responses.add(
                clients.submit(() -> {
                    try (Socket socket = new Socket("127.0.0.1", server.port())) {
                        socket.setSoTimeout(15000);
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(
                            "Barreira expirada"
                        );
                        Protocol.writeLine(
                            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                            new Request("RECOMMEND", 1L, null, null, 3, 5)
                        );
                        return Protocol.JSON.readValue(
                            Protocol.readLine(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                            ),
                            Response.class
                        );
                    }
                })
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Response> response : responses)
                assertThat(response.get(20, TimeUnit.SECONDS).ok()).isTrue();
        } finally {
            start.countDown();
            clients.shutdownNow();
        }
    }

    @Test
    void concurrentWritesDoNotSilentlyOverwrite() throws Exception {
        Profile original = repository.snapshot().users().get(0);
        ExecutorService clients = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Response> save = () -> {
                start.await();
                return exchange(
                    new Request(
                        "UPDATE_RATINGS",
                        original.id(),
                        original.ratings(),
                        original.version(),
                        null,
                        null
                    )
                );
            };
            Future<Response> first = clients.submit(save),
                second = clients.submit(save);
            start.countDown();
            List<Response> responses = List.of(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)
            );
            assertThat(responses.stream().filter(Response::ok).count()).isEqualTo(1);
            assertThat(
                responses
                    .stream()
                    .filter(r -> "CONFLICT".equals(r.code()))
                    .count()
            ).isEqualTo(1);
            assertThat(repository.snapshot().users().get(0).version()).isEqualTo(original.version() + 1);
        } finally {
            clients.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidRatingsAndUnknownOperations() throws Exception {
        Profile user = repository.snapshot().users().get(0);
        assertThat(
            exchange(new Request("UPDATE_RATINGS", 1L, List.of(4, 3), user.version(), null, null)).code()
        ).isEqualTo("INVALID_REQUEST");
        List<Integer> invalid = new ArrayList<>(user.ratings());
        invalid.set(0, 5);
        assertThat(
            exchange(new Request("UPDATE_RATINGS", 1L, invalid, user.version(), null, null)).code()
        ).isEqualTo("INVALID_REQUEST");
        assertThat(exchange(new Request("UNKNOWN", null, null, null, null, null)).code()).isEqualTo(
            "INVALID_REQUEST"
        );
        assertThat(exchange(new Request("RECOMMEND", 999L, null, null, 3, 5)).code()).isEqualTo("NOT_FOUND");
        assertThat(repository.snapshot().users().get(0)).isEqualTo(user);
    }
}
