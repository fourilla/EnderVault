package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SelectedItemsTest {

    @Test
    void preservesCommaInsideSingleItemName() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("items", "demo,final.txt");

        assertThat(SelectedItems.from(request)).containsExactly("demo,final.txt");
    }

    @Test
    void preservesRepeatedItemParameters() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("items", "one.txt", "two,final.txt");

        assertThat(SelectedItems.from(request)).containsExactly("one.txt", "two,final.txt");
    }
}
