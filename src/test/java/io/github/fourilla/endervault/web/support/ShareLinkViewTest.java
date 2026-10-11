package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareTargetType;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShareLinkViewTest {

    @ParameterizedTest
    @ValueSource(strings = {"50%.txt", "literal%2B.txt", "semi;colon.txt", "mixed%;+한글.txt"})
    void restrictedFilenamesUseTokenOnlyDownload(String name) {
        ShareLinkView view = ShareLinkView.from(link("folder+name/" + name, ShareTargetType.FILE),
                "https://example.test/app/s/");

        assertThat(view.url()).isEqualTo("https://example.test/app/s/guest");
        assertThat(view.directDownloadUrl()).isEqualTo(view.url() + "/download");
        assertThat(URI.create(view.directDownloadUrl()).getRawQuery()).isNull();
        assertThat(view.hasDirectDownloadUrl()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a+b.txt", "a b.txt", "한글.txt", "brace{name}.txt", "amp&equals=#.txt"})
    void otherFilenamesKeepExistingNamedDownload(String name) {
        ShareLinkView view = ShareLinkView.from(link("folder/" + name, ShareTargetType.FILE), "/s/");
        URI download = URI.create(view.directDownloadUrl());

        assertThat(download.getPath()).isEqualTo("/s/guest/download/" + name);
        assertThat(download.getRawQuery()).isNull();
        assertThat(download.getRawFragment()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/s/", "https://example.test/s", "https://example.test/app/s/"})
    void fallbackPreservesConfiguredBaseAndContext(String base) {
        ShareLinkView view = ShareLinkView.from(link("folder\\semi;colon.txt", ShareTargetType.FILE), base);
        String normalizedBase = base.endsWith("/") ? base : base + "/";

        assertThat(view.url()).isEqualTo(normalizedBase + "guest");
        assertThat(view.directDownloadUrl()).isEqualTo(normalizedBase + "guest/download");
    }

    @Test
    void disabledDirectDownloadLinksStayAbsent() {
        ShareLinkView view = ShareLinkView.from(link("50%.txt", ShareTargetType.FILE), "/s/", false);

        assertThat(view.directDownloadUrl()).isNull();
        assertThat(view.hasDirectDownloadUrl()).isFalse();
        assertThat(view.url()).isEqualTo("/s/guest");
    }

    @Test
    void directorySharesHaveNoDirectFileDownloadLink() {
        ShareLinkView view = ShareLinkView.from(link("folder%;name", ShareTargetType.DIRECTORY), "/s/");

        assertThat(view.directDownloadUrl()).isNull();
        assertThat(view.hasDirectDownloadUrl()).isFalse();
    }

    @Test
    void restrictedAncestorNameDoesNotRemoveSafeFilenameSegment() {
        ShareLinkView view = ShareLinkView.from(link("folder%;name/file+name.txt", ShareTargetType.FILE), "/s/");

        assertThat(view.directDownloadUrl()).isEqualTo("/s/guest/download/file+name.txt");
    }

    private ShareLink link(String path, ShareTargetType type) {
        return new ShareLink("guest", path, type, Instant.EPOCH, null, true, true);
    }
}
