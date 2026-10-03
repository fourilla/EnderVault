package io.github.fourilla.endervault.web.api.v1.search;

import io.github.fourilla.endervault.web.support.FlashNotification;

public record SearchQueryErrorResponse(boolean ok, FlashNotification notification, int position) {}
