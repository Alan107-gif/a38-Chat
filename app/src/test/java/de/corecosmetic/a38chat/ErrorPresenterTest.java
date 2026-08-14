package de.corecosmetic.a38chat;

import org.junit.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ErrorPresenterTest {
    @Test
    public void normalModeNeverShowsRawTechnicalDetails() {
        String visible = ErrorPresenter.message(
                "en",
                new UnknownHostException("internal-host.example"),
                false
        );

        assertEquals("No internet connection.", visible);
        assertFalse(visible.contains("internal-host"));
        assertFalse(visible.contains("UnknownHostException"));
    }

    @Test
    public void debugModeAddsBoundedTechnicalDetails() {
        String visible = ErrorPresenter.message(
                "de",
                new ChatApi.ApiException(503, "upstream failed"),
                true
        );

        assertTrue(visible.startsWith("Der Chatdienst ist derzeit nicht erreichbar."));
        assertTrue(visible.contains("ApiException (HTTP 503)"));
        assertTrue(visible.contains("upstream failed"));
    }

    @Test
    public void allSupportedLanguagesHaveFriendlyNetworkText() {
        for (String language : new String[]{"en", "de", "fr", "ru", "uk", "it"}) {
            String visible = ErrorPresenter.message(language, new UnknownHostException("raw"), false);
            assertFalse(visible.isEmpty());
            assertFalse(visible.contains("raw"));
        }
    }

    @Test
    public void timeoutAndRejectedConnectionAreNotMisreportedAsNoInternet() {
        assertEquals(
                "The connection timed out. Please try again.",
                ErrorPresenter.message("en", new SocketTimeoutException("raw timeout"), false)
        );
        assertEquals(
                "Could not connect to the chat service.",
                ErrorPresenter.message("en", new ConnectException("raw refusal"), false)
        );
    }

    @Test
    public void debugDetailsRedactCredentials() {
        String visible = ErrorPresenter.message(
                "en",
                new IllegalStateException(
                        "token=abc123 password: hunter2 Authorization: Bearer header-secret"
                ),
                true
        );

        assertTrue(visible.contains("token=[redacted]"));
        assertTrue(visible.contains("password: [redacted]"));
        assertFalse(visible.contains("abc123"));
        assertFalse(visible.contains("hunter2"));
        assertFalse(visible.contains("header-secret"));
    }
}
