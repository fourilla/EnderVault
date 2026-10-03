package io.github.fourilla.endervault.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestSearchSchema;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkSearchSchema;
import io.github.fourilla.endervault.share.ShareTargetType;
import io.github.fourilla.endervault.web.support.ShareLinkPayload;
import io.github.fourilla.endervault.web.support.ShareLinkView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkSearchSchemaTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    private ShareLink share(boolean enabled, Instant expires, Boolean preview) {
        return new ShareLink("secret-capability", "photos/summer holiday.jpg", ShareTargetType.FILE,
                NOW.minusSeconds(60), expires, enabled, preview);
    }

    private FileRequest request(boolean enabled, Instant expires, long bytes, int files) {
        return new FileRequest("id", "secret-capability", "Summer holiday", "Please send originals",
                "photos/2026", UploaderNamePolicy.REQUIRED, 100, 200, 2, List.of("jpg"),
                bytes, files, List.of(), NOW.minusSeconds(60), expires, enabled);
    }

    @Test
    void sharedDefaultsMatchOnlyTheTargetPathAndNameMatchesOnlyTheLastComponent() {
        var item = share(true, null, true);
        assertThat(ShareLinkSearchSchema.compile("PHOTOS \"summer holiday\"", NOW).test(item)).isTrue();
        assertThat(ShareLinkSearchSchema.compile("name:photos", NOW).test(item)).isFalse();
        assertThat(ShareLinkSearchSchema.compile("secret-capability", NOW).test(item)).isFalse();
        assertThat(ShareLinkSearchSchema.compile("name:holiday path:photos type:FILE preview:ON status:ACTIVE", NOW).test(item)).isTrue();
        assertThat(ShareLinkSearchSchema.compile("type:directory || preview:off", NOW).test(item)).isFalse();
        var root = new ShareLink("root", "", ShareTargetType.DIRECTORY, NOW, null, true, null);
        assertThat(ShareLinkSearchSchema.compile("path:/ type:directory preview:off", NOW).test(root)).isTrue();
    }

    @Test
    void requestsMatchTitleOrDestinationByDefaultButRequireExplicitDescriptionAndPolicy() {
        var item = request(true, null, 0, 0);
        assertThat(FileRequestSearchSchema.compile("\"Summer holiday\" photos", NOW).test(item)).isTrue();
        assertThat(FileRequestSearchSchema.compile("originals", NOW).test(item)).isFalse();
        assertThat(FileRequestSearchSchema.compile("secret-capability", NOW).test(item)).isFalse();
        assertThat(FileRequestSearchSchema.compile("name:summer destination:2026 description:originals uploader-policy:REQUIRED", NOW).test(item)).isTrue();
        assertThat(FileRequestSearchSchema.compile("name:photos || uploader-policy:none", NOW).test(item)).isFalse();
    }

    @Test
    void sharedStatusAndPayloadUseTheSameTimeAndRevocationPrecedesExpiry() {
        var expired = share(true, NOW, false);
        assertThat(ShareLinkSearchSchema.compile("status:expired", NOW).test(expired)).isTrue();
        assertThat(ShareLinkSearchSchema.compile("status:active", NOW.minusNanos(1)).test(expired)).isTrue();
        assertThat(ShareLinkSearchSchema.compile("status:revoked", NOW).test(expired.revoke())).isTrue();
        assertThat(ShareLinkSearchSchema.compile("status:expired", NOW).test(expired.revoke())).isFalse();
        var payload = ShareLinkPayload.from(ShareLinkView.from(expired, "/s/"), NOW);
        assertThat(payload.statusLabel()).isEqualTo("Expired");
        assertThat(payload.statusClass()).isEqualTo("expired");
        assertThat(payload.active()).isFalse();
    }

    @Test
    void requestStatusPreservesRevokedExpiredFullActivePriorityAndEitherQuotaCanFillIt() {
        var bytesFull = request(true, null, 200, 0);
        var countFull = request(true, null, 0, 2);
        for (var item : List.of(bytesFull, countFull)) {
            assertThat(FileRequestSearchSchema.compile("status:full", NOW).test(item)).isTrue();
            assertThat(item.statusLabel(NOW)).isEqualTo("Full");
            assertThat(item.statusClass(NOW)).isEqualTo("warning");
            assertThat(item.usable(NOW)).isFalse();
        }
        var expired = request(true, NOW, 200, 2);
        assertThat(FileRequestSearchSchema.compile("status:expired", NOW).test(expired)).isTrue();
        assertThat(FileRequestSearchSchema.compile("status:full", NOW).test(expired)).isFalse();
        assertThat(FileRequestSearchSchema.compile("status:revoked", NOW).test(expired.revoke())).isTrue();
        assertThat(FileRequestSearchSchema.compile("status:active", NOW).test(request(true, NOW.plusNanos(1), 199, 1))).isTrue();
    }

    @Test
    void datesUseStoredInstantsWithInclusiveRangesAndNullDoesNotBecomeNeverOrZero() {
        String query = "created:>=2026-10-03T23:59:00Z expires:2026-10-04T00:00:00Z..2026-10-04T00:00:00Z";
        assertThat(ShareLinkSearchSchema.compile(query, NOW).test(share(true, NOW, true))).isTrue();
        assertThat(FileRequestSearchSchema.compile(query, NOW).test(request(true, NOW, 0, 0))).isTrue();
        assertThat(ShareLinkSearchSchema.compile("expires:>1970-01-01T00:00:00Z", NOW).test(share(true, null, true))).isFalse();
        assertThat(FileRequestSearchSchema.compile("expires:>1970-01-01T00:00:00Z", NOW).test(request(true, null, 0, 0))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"path:photos || status:unknown", "preview:enabled", "type:zip", "token:secret",
            "created:2026-02-30", "expires:never", "name:summer || size:1MB"})
    void sharesValidateEveryTermBeforeReadingCandidates(String query) {
        assertThatThrownBy(() -> ShareLinkSearchSchema.compile(query, NOW)).isInstanceOf(SearchQueryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name:summer || status:warning", "uploader-policy:always", "token:secret",
            "created:2026-02-30", "expires:never", "destination:photos || extension:jpg"})
    void requestsValidateEveryTermBeforeReadingCandidates(String query) {
        assertThatThrownBy(() -> FileRequestSearchSchema.compile(query, NOW)).isInstanceOf(SearchQueryException.class);
    }

    @Test
    void metadataAndEmptyQueriesUseTheSameRegisteredSchema() {
        assertThat(ShareLinkSearchSchema.defaultFields()).containsExactly("path");
        assertThat(FileRequestSearchSchema.defaultFields()).containsExactly("name", "destination");
        assertThat(ShareLinkSearchSchema.fields()).extracting(SearchSchema.FieldInfo::key)
                .containsExactly("name", "path", "type", "status", "preview", "created", "expires");
        assertThat(FileRequestSearchSchema.fields()).extracting(SearchSchema.FieldInfo::key)
                .containsExactly("name", "description", "destination", "status", "uploader-policy", "created", "expires");
        assertThat(ShareLinkSearchSchema.compile(null, NOW).test(share(false, NOW, false))).isTrue();
        assertThat(FileRequestSearchSchema.compile("  ", NOW).test(request(false, NOW, 200, 2))).isTrue();
        assertThatThrownBy(() -> ShareLinkSearchSchema.compile("  typo:value", NOW))
                .isInstanceOfSatisfying(SearchQueryException.class, error -> assertThat(error.position()).isEqualTo(2));
        assertThatThrownBy(() -> FileRequestSearchSchema.fields().get(4).values().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
