package com.eduplatform.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证受控 Markdown 的允许集合、危险语法、占位符一致性基础和可见字符上限。 */
class ControlledMarkdownValidatorTest {
    private final ControlledMarkdownValidator validator = new ControlledMarkdownValidator();

    @Test
    void acceptsHeadingsListsTablesCodeHttpsLatexAndRepeatedControlledImage() {
        UUID image = UUID.randomUUID();
        String markdown = "# 标题\n\n- 列表\n\n|a|b|\n|-|-|\n|1|2|\n\n```java\nint x = 1;\n```\n"
                + "公式 $x^2$ 与 [文档](https://example.test/doc)\n\n![示意图](media:" + image + ")\n"
                + "![再次引用](media:" + image + ")";
        assertThat(validator.validate(markdown)).containsExactly(image.toString());
    }

    @Test
    void rejectsRawHtmlExternalOrEmptyAltImagesAndDangerousProtocols() {
        assertThatThrownBy(() -> validator.validate("<iframe src=\"https://example.test\"></iframe>"))
                .isInstanceOf(CourseException.class);
        assertThatThrownBy(() -> validator.validate("![外图](https://example.test/a.png)"))
                .isInstanceOf(CourseException.class);
        assertThatThrownBy(() -> validator.validate("![](media:00000000-0000-4000-8000-000000000001)"))
                .isInstanceOf(CourseException.class);
        assertThatThrownBy(() -> validator.validate("[危险](javascript:alert(1))"))
                .isInstanceOf(CourseException.class);
        assertThatThrownBy(() -> validator.validate("[内网](http://example.test)"))
                .isInstanceOf(CourseException.class);
    }

    @Test
    void rejectsMalformedPlaceholderAndVisibleCharacterOverflowButAllowsEmptyDraft() {
        assertThat(validator.validate("  ")).isEmpty();
        assertThatThrownBy(() -> validator.validate("media:not-a-uuid"))
                .isInstanceOf(CourseException.class);
        assertThatThrownBy(() -> validator.validate("字".repeat(100_001)))
                .isInstanceOf(CourseException.class);
    }
}
