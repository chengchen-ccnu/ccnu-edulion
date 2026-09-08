package com.eduplatform.learning.service;

import com.eduplatform.learning.web.QuestionDtos;
import java.util.List;

/** 题目安全媒体投影：隐藏只用于参考答案/解析的绑定，兼容稳定占位和旧 [图片N]，不修改原始版本。 */
final class QuestionMediaVisibility {
    private QuestionMediaVisibility() {}

    /**
     * 以同一版本的题干/选项和答案/解析区分媒体用途；两区都引用的媒体按可见正文处理。
     * 未使用内联语法的独立媒体沿用题目附件语义；隐藏区独占的媒体不会因挂在同一版本而公开。
     */
    static List<QuestionDtos.MediaResponse> content(String stem, List<QuestionDtos.OptionInput> options,
            List<String> answers, String analysis, List<QuestionDtos.MediaResponse> media) {
        String visible = stem + "\n" + String.join("\n", options.stream().map(QuestionDtos.OptionInput::content).toList());
        String hidden = String.join("\n", answers) + "\n" + (analysis == null ? "" : analysis);
        return media.stream().filter(item -> referenced(visible, item)
                || !referenced(hidden, item)).toList();
    }

    private static boolean referenced(String text, QuestionDtos.MediaResponse item) {
        return text.toLowerCase(java.util.Locale.ROOT).contains("{{media:" + item.mediaId().toLowerCase(java.util.Locale.ROOT) + "}}")
                || text.contains("[图片" + item.sequence() + "]");
    }
}
