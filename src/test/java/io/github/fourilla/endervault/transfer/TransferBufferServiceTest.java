package io.github.fourilla.endervault.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.storage.FileItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class TransferBufferServiceTest {

    private final TransferBufferService service = new TransferBufferService();

    @Test
    void directorySelectionReplacesDescendantItems() {
        MockHttpSession session = new MockHttpSession();

        service.add(session, List.of(file("docs/note.txt")));
        TransferBuffer buffer = service.add(session, List.of(directory("docs")));

        assertThat(buffer.items()).extracting(TransferBufferItem::path).containsExactly("docs");
    }

    @Test
    void descendantSelectionIsSkippedWhenAncestorDirectoryIsBuffered() {
        MockHttpSession session = new MockHttpSession();

        service.add(session, List.of(directory("docs")));
        TransferBuffer buffer = service.add(session, List.of(file("docs/note.txt")));

        assertThat(buffer.items()).extracting(TransferBufferItem::path).containsExactly("docs");
    }

    @Test
    void laterSelectionsAreAppendedToCurrentBuffer() {
        MockHttpSession session = new MockHttpSession();

        service.add(session, List.of(file("a.txt")));
        TransferBuffer buffer = service.add(session, List.of(file("b.txt")));

        assertThat(buffer.items()).extracting(TransferBufferItem::path).containsExactly("a.txt", "b.txt");
    }

    @Test
    void removesSingleBufferedItemByPath() {
        MockHttpSession session = new MockHttpSession();

        service.add(session, List.of(file("a.txt"), file("b.txt")));
        TransferBuffer buffer = service.remove(session, "a.txt");

        assertThat(buffer.items()).extracting(TransferBufferItem::path).containsExactly("b.txt");
    }

    @Test
    void removingLastItemClearsBuffer() {
        MockHttpSession session = new MockHttpSession();

        service.add(session, List.of(file("a.txt")));
        TransferBuffer buffer = service.remove(session, "a.txt");

        assertThat(buffer.active()).isFalse();
        assertThat(service.current(session).active()).isFalse();
    }

    @Test
    void replacesBufferedItems() {
        MockHttpSession session = new MockHttpSession();

        service.add(session, List.of(file("a.txt"), file("b.txt")));
        TransferBuffer buffer = service.replace(session, List.of(fileItem("b.txt")));

        assertThat(buffer.items()).extracting(TransferBufferItem::path).containsExactly("b.txt");
        assertThat(service.current(session).items()).extracting(TransferBufferItem::path).containsExactly("b.txt");
    }

    private FileItem file(String path) {
        return item(path, false);
    }

    private FileItem directory(String path) {
        return item(path, true);
    }

    private FileItem item(String path, boolean directory) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new FileItem(
                name,
                path,
                directory,
                0L,
                directory ? "-" : "0 B",
                "-",
                Instant.EPOCH,
                directory ? "directory" : "text/plain",
                !directory,
                false
        );
    }

    private TransferBufferItem fileItem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new TransferBufferItem(path, name, false);
    }
}
