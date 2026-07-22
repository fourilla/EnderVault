package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StorageHiddenPolicyTest {

    @Test
    void dosHiddenAttributesAreWindowsOnlyPolicy() {
        assertThat(StorageHiddenPolicy.isWindowsOs("Windows 11")).isTrue();
        assertThat(StorageHiddenPolicy.isWindowsOs("Linux")).isFalse();
        assertThat(StorageHiddenPolicy.isWindowsOs("Mac OS X")).isFalse();
    }

    @Test
    void dotPrefixNamesAreUsedForPortableHiddenNames() {
        assertThat(StorageHiddenPolicy.hiddenName("note.txt")).isEqualTo(".note.txt");
        assertThat(StorageHiddenPolicy.hiddenName(".note.txt")).isEqualTo(".note.txt");
        assertThat(StorageHiddenPolicy.visibleName(".note.txt")).isEqualTo("note.txt");
    }
}
