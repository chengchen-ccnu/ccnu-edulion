package com.eduplatform.learning.web;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.service.QuestionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 已登录学生和教师的公共系统题只读边界；响应永不包含答案、解析或教师私有题。 */
@RestController
@Validated
@ConditionalOnBean(QuestionRepository.class)
public class SystemQuestionController {
    private final QuestionService questions;

    public SystemQuestionController(QuestionService questions) { this.questions = questions; }

    /** 分页返回 ACTIVE SYSTEM 题安全摘要；身份只用于确认已登录。 */
    @GetMapping("/api/v1/learning/system-questions")
    public QuestionDtos.SystemQuestionPage list(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) QuestionModels.Stage stage,
            @RequestParam(required = false) QuestionModels.Grade grade,
            @RequestParam(required = false) QuestionModels.Type type,
            @RequestParam(required = false) QuestionModels.Difficulty difficulty,
            @RequestParam(required = false) String knowledgePointId) {
        LearningIdentity.authenticated(userId, roles, permissions);
        return questions.listSystem(page, size, stage, grade, type, difficulty, knowledgePointId);
    }

    /** 返回单个 ACTIVE SYSTEM 题内容；答案和解析在服务层构造投影时已移除。 */
    @GetMapping("/api/v1/learning/system-questions/{questionId}")
    public QuestionDtos.SystemQuestion get(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String questionId) {
        LearningIdentity.authenticated(userId, roles, permissions);
        return questions.getSystem(questionId);
    }
}
