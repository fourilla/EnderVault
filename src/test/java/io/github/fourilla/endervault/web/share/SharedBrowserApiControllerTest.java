package io.github.fourilla.endervault.web.share;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SharedBrowserApiControllerTest {

    @Test
    void unexpectedIoFailureReturnsGenericJsonAndNoStoreWithoutAcceptHeader() throws Exception {
        SharedBrowserQueryService query = mock(SharedBrowserQueryService.class);
        when(query.listing(eq("private-token"), isNull(), any(HttpServletRequest.class)))
                .thenThrow(new IOException("C:/private/storage/path"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SharedBrowserApiController(query)).build();

        mockMvc.perform(get("/s/private-token/listing.json"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.notification.message").value("Shared content could not be loaded."));
    }
}
