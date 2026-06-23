package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BrowserPreferenceCookiesTest {

    @Test
    void requestedValueWinsAndIsRemembered() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String value = BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.FILES.viewCookie(),
                "GRID",
                input -> "grid".equalsIgnoreCase(input) ? "grid" : "table"
        );

        assertThat(value).isEqualTo("grid");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(header -> assertThat(header)
                        .contains("endervault.files.view=grid")
                        .contains("HttpOnly")
                        .contains("SameSite=Lax"));
    }

    @Test
    void cookieValueIsUsedWhenRequestParameterIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(BrowserPreferenceCookies.FILES.sortCookie(), "modified"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        String value = BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.FILES.sortCookie(),
                null,
                input -> input == null || input.isBlank() ? "name" : input
        );

        assertThat(value).isEqualTo("modified");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void integerCookieFallsBackWhenItCannotBeParsed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(BrowserPreferenceCookies.FILES.pageSizeCookie(), "large"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        int value = BrowserPreferenceCookies.intValue(
                request,
                response,
                BrowserPreferenceCookies.FILES.pageSizeCookie(),
                null,
                input -> input == null ? 200 : input
        );

        assertThat(value).isEqualTo(200);
    }

    @Test
    void clearExpiresScopeCookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        BrowserPreferenceCookies.clear(response, BrowserPreferenceCookies.FILES);

        List<String> headers = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(headers).hasSize(4);
        assertThat(headers).allSatisfy(header -> assertThat(header).contains("Max-Age=0"));
    }

    @Test
    void recentSharesCommonViewAndPageSizeWithFiles() {
        assertThat(BrowserPreferenceCookies.RECENT.viewCookie())
                .isEqualTo(BrowserPreferenceCookies.FILES.viewCookie());
        assertThat(BrowserPreferenceCookies.RECENT.pageSizeCookie())
                .isEqualTo(BrowserPreferenceCookies.FILES.pageSizeCookie());
        assertThat(BrowserPreferenceCookies.RECENT.sortCookie())
                .isNotEqualTo(BrowserPreferenceCookies.FILES.sortCookie());
        assertThat(BrowserPreferenceCookies.RECENT.directionCookie())
                .isNotEqualTo(BrowserPreferenceCookies.FILES.directionCookie());
    }
}
