package afinidade;

import afinidade.Protocol.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Cada requisição HTTP abre uma conexão TCP independente: não há socket compartilhado. */
@Component
@Profile("!servidor")
public class TcpClient {

    private final String host;
    private final int port, connectTimeout, readTimeout;

    public TcpClient(
        @Value("${music.server.host}") String host,
        @Value("${music.server.port}") int port,
        @Value("${music.server.connect-timeout-ms}") int connectTimeout,
        @Value("${music.server.read-timeout-ms}") int readTimeout
    ) {
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public Response exchange(Request request) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            socket.setSoTimeout(readTimeout);
            Writer writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            );
            Reader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );
            Protocol.writeLine(writer, request);
            String line = Protocol.readLine(reader);
            if (line == null) throw new EOFException("Servidor encerrou a conexão.");
            return Protocol.JSON.readValue(line, Response.class);
        } catch (IOException error) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Servidor musical indisponível. Verifique o processo TCP e tente novamente."
            );
        }
    }
}
