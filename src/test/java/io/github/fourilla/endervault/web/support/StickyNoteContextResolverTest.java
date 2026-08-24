package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.stickynote.StickyNotePageCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StickyNoteContextResolverTest {

    private final StickyNoteContextResolver resolver = new StickyNoteContextResolver(new StickyNotePageCatalog());

    @Test
    void resolvesFileBrowserWithoutViewPreferenceParametersInContext() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/files");
        request.addParameter("path", "docs/sub");
        request.addParameter("sort", "name");
        request.addParameter("page", "3");

        StickyNotePageContext context = resolver.resolve(request);

        assertThat(context.available()).isTrue();
        assertThat(context.targetType()).isEqualTo("STORAGE");
        assertThat(context.targetKey()).isEqualTo("docs/sub");
        assertThat(context.surface()).isEqualTo("BROWSER");
    }

    @Test
    void separatesBookmarkDirectoryAndDetailSurfaces() {
        MockHttpServletRequest browser = new MockHttpServletRequest("GET", "/files/bookmarks");
        browser.addParameter("directory", "directory-id");
        MockHttpServletRequest detail = new MockHttpServletRequest("GET", "/files/bookmarks/detail");
        detail.addParameter("id", "bookmark-id");

        assertThat(resolver.resolve(browser)).extracting(
                StickyNotePageContext::targetType,
                StickyNotePageContext::targetKey,
                StickyNotePageContext::surface
        ).containsExactly("BOOKMARK", "directory-id", "BROWSER");
        assertThat(resolver.resolve(detail)).extracting(
                StickyNotePageContext::targetType,
                StickyNotePageContext::targetKey,
                StickyNotePageContext::surface
        ).containsExactly("BOOKMARK", "bookmark-id", "DETAIL");
    }

    @Test
    void excludesReadOnlyMode() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/files/read-only");

        assertThat(resolver.resolve(request).available()).isFalse();
    }

    @Test
    void resolvesNewerAdminManagementPagesFromThePageCatalog() {
        MockHttpServletRequest fileRequests = new MockHttpServletRequest("GET", "/admin/file-requests");
        MockHttpServletRequest pendingDecisions = new MockHttpServletRequest("GET", "/admin/pending-decisions");

        assertThat(resolver.resolve(fileRequests)).extracting(
                StickyNotePageContext::targetType,
                StickyNotePageContext::targetKey,
                StickyNotePageContext::surface
        ).containsExactly("PAGE", "file-requests", "PAGE");
        assertThat(resolver.resolve(pendingDecisions)).extracting(
                StickyNotePageContext::targetType,
                StickyNotePageContext::targetKey,
                StickyNotePageContext::surface
        ).containsExactly("PAGE", "pending-decisions", "PAGE");
    }
}
