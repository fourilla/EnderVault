package io.github.fourilla.endervault.notificationcenter;

import java.io.IOException;
import java.util.List;

public interface ActionRequiredProvider {

    List<ActionRequiredItem> items() throws IOException;

    String reviewAllHref();
}
