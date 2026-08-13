package io.github.fourilla.endervault.filetool;

import io.github.fourilla.endervault.filetool.strategy.ArchiveFileToolStrategy;
import io.github.fourilla.endervault.filetool.strategy.ComicFileToolStrategy;
import io.github.fourilla.endervault.filetool.strategy.DirectoryFileToolStrategy;
import io.github.fourilla.endervault.filetool.strategy.HexFileToolStrategy;
import io.github.fourilla.endervault.filetool.strategy.ImageFileToolStrategy;
import io.github.fourilla.endervault.filetool.strategy.PdfFileToolStrategy;
import io.github.fourilla.endervault.filetool.strategy.TextFileToolStrategy;
import io.github.fourilla.endervault.filetool.strategy.VideoFileToolStrategy;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FileToolRegistry {

    private final List<FileToolStrategy> strategies;

    public FileToolRegistry() {
        this(defaultStrategies());
    }

    @Autowired
    public FileToolRegistry(List<FileToolStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(FileToolStrategy::priority))
                .toList();
    }

    public FileToolDescriptor resolve(FileToolContext context) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(context))
                .findFirst()
                .map(strategy -> strategy.describe(context))
                .orElseThrow(() -> new IllegalStateException("No file tool strategy supports " + context.name()));
    }

    private static List<FileToolStrategy> defaultStrategies() {
        return List.of(
                new DirectoryFileToolStrategy(),
                new TextFileToolStrategy(),
                new ComicFileToolStrategy(),
                new ArchiveFileToolStrategy(),
                new ImageFileToolStrategy(),
                new VideoFileToolStrategy(),
                new PdfFileToolStrategy(),
                new HexFileToolStrategy()
        );
    }
}
