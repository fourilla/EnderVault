package io.github.fourilla.endervault.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequestOperationsService;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.search.SearchQueryException;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestAdminQueryService;
import io.github.fourilla.endervault.web.api.v1.share.ShareApiController;
import io.github.fourilla.endervault.web.filerequest.FileRequestUrlBuilder;
import io.github.fourilla.endervault.web.support.ShareUrlBuilder;
import org.junit.jupiter.api.Test;

class LinkSearchValidationTest {
    @Test
    void invalidShareQueryDoesNotReadRecordsOrBuildCapabilityUrls() {
        var service = mock(ShareLinkService.class);
        var activity = mock(ActivityLogService.class);
        var tokens = mock(PublicLinkTokenService.class);
        var urls = mock(ShareUrlBuilder.class);
        var controller = new ShareApiController(service, activity, tokens, urls, new NasProperties());
        assertThatThrownBy(() -> controller.list("name:any || status:invalid")).isInstanceOf(SearchQueryException.class);
        verifyNoInteractions(service, activity, tokens, urls);
    }

    @Test
    void invalidRequestQueryDoesNotReadDuplicateDefaultsOrNormalizeDestination() {
        var service = mock(FileRequestService.class);
        var operations = mock(FileRequestOperationsService.class);
        var urls = mock(FileRequestUrlBuilder.class);
        var query = new FileRequestAdminQueryService(service, operations, urls, new NasProperties());
        assertThatThrownBy(() -> query.list("not-created", "not-created", "name:any || status:invalid"))
                .isInstanceOf(SearchQueryException.class);
        verifyNoInteractions(service, operations, urls);
    }
}
