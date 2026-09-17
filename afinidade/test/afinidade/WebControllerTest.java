package afinidade;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import afinidade.Protocol.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(WebController.class)
class WebControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TcpClient gateway;

    @Test
    void catalogIsForwardedToTcpClient() throws Exception {
        when(gateway.exchange(any())).thenReturn(Response.success(Map.of("artists", new int[] { 1, 2 })));
        mvc.perform(get("/api/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.artists.length()").value(2));
        verify(gateway).exchange(new Request("CATALOG", null, null, null, null, null));
    }

    @Test
    void conflictsHaveHttp409() throws Exception {
        when(gateway.exchange(any())).thenReturn(Response.error("CONFLICT", "Recarregue o perfil."));
        mvc.perform(
            put("/api/users/1/ratings")
                .contentType("application/json")
                .content("{\"version\":0,\"ratings\":[4]}")
        )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Recarregue o perfil."));
    }

    @Test
    void invalidJsonHasReadableError() throws Exception {
        mvc.perform(put("/api/users/1/ratings").contentType("application/json").content("invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(gateway);
    }

    @Test
    void networkFailureIs503() throws Exception {
        when(gateway.exchange(any())).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Servidor indisponível.")
        );
        mvc.perform(get("/api/health"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("Servidor indisponível."));
    }
}
