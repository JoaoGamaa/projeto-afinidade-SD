package afinidade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.List;

/** TCP: uma mensagem JSON UTF-8 por linha, até 65.536 caracteres. */
public final class Protocol {

    public static final int MAX_LINE = 65_536;
    public static final ObjectMapper JSON = new ObjectMapper()
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Protocol() {}

    public record Request(
        String operation,
        Long userId,
        List<Integer> ratings,
        Long version,
        Integer k,
        Integer limit
    ) {}

    public record Response(boolean ok, String code, String message, JsonNode data) {
        public static Response success(Object data) {
            return new Response(true, "OK", null, JSON.valueToTree(data));
        }

        public static Response error(String code, String message) {
            return new Response(false, code, message, null);
        }
    }

    public static class DomainException extends RuntimeException {

        private final String code;

        public DomainException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static String readLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') return line.toString();
            if (line.length() >= MAX_LINE) throw new IOException("Mensagem excede o limite do protocolo.");
            line.append((char) c);
        }
        if (!line.isEmpty()) throw new EOFException("Mensagem incompleta: falta a quebra de linha.");
        return null;
    }

    public static void writeLine(Writer writer, Object message) throws IOException {
        writer.write(JSON.writeValueAsString(message));
        writer.write('\n');
        writer.flush();
    }
}
