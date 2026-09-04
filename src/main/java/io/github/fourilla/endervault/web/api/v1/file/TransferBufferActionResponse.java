package io.github.fourilla.endervault.web.api.v1.file;

import io.github.fourilla.endervault.transfer.TransferBuffer;
import io.github.fourilla.endervault.transfer.TransferBufferItem;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.task.TaskPayload;
import java.util.List;

public record TransferBufferActionResponse(
        boolean ok,
        FlashNotification notification,
        TransferBufferPayload transferBuffer,
        TaskPayload task
) {
    public static TransferBufferActionResponse ok(FlashNotification notification, TransferBuffer transferBuffer) {
        return ok(notification, transferBuffer, null);
    }

    public static TransferBufferActionResponse ok(
            FlashNotification notification,
            TransferBuffer transferBuffer,
            TaskPayload task
    ) {
        return new TransferBufferActionResponse(
                true,
                notification,
                TransferBufferPayload.from(transferBuffer),
                task
        );
    }

    public record TransferBufferPayload(
            boolean active,
            int count,
            List<TransferBufferItemPayload> items
    ) {
        public static TransferBufferPayload from(TransferBuffer buffer) {
            return new TransferBufferPayload(
                    buffer.active(),
                    buffer.count(),
                    buffer.items().stream()
                            .map(TransferBufferItemPayload::from)
                            .toList()
            );
        }
    }

    public record TransferBufferItemPayload(String path, String name, String iconClass) {
        public static TransferBufferItemPayload from(TransferBufferItem item) {
            return new TransferBufferItemPayload(item.path(), item.name(), item.iconClass());
        }
    }
}
