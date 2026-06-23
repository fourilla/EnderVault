package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.transfer.TransferBuffer;
import io.github.fourilla.endervault.transfer.TransferBufferItem;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.task.TaskPayload;
import java.util.List;

record TransferBufferActionResponse(
        boolean ok,
        FlashNotification notification,
        TransferBufferPayload transferBuffer,
        TaskPayload task
) {
    static TransferBufferActionResponse ok(FlashNotification notification, TransferBuffer transferBuffer) {
        return ok(notification, transferBuffer, null);
    }

    static TransferBufferActionResponse ok(
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

    private record TransferBufferPayload(
            boolean active,
            int count,
            List<TransferBufferItemPayload> items
    ) {
        static TransferBufferPayload from(TransferBuffer buffer) {
            return new TransferBufferPayload(
                    buffer.active(),
                    buffer.count(),
                    buffer.items().stream()
                            .map(TransferBufferItemPayload::from)
                            .toList()
            );
        }
    }

    private record TransferBufferItemPayload(
            String path,
            String name,
            String iconClass
    ) {
        static TransferBufferItemPayload from(TransferBufferItem item) {
            return new TransferBufferItemPayload(item.path(), item.name(), item.iconClass());
        }
    }
}
