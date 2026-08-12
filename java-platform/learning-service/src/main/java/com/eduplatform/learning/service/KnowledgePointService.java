package com.eduplatform.learning.service;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.domain.QuestionModels.KnowledgePoint;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.web.QuestionDtos;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.transaction.annotation.Transactional;

/** 管理知识点树；教师只读启用节点，管理员写操作校验父级与乐观 revision。 */
@Service
@ConditionalOnBean(QuestionRepository.class)
public class KnowledgePointService {
    private final QuestionRepository repository;

    public KnowledgePointService(QuestionRepository repository) { this.repository = repository; }

    /** 按可选年级和父节点返回启用树；父节点被禁用时其子节点也不会越过父节点成为新根。 */
    public List<QuestionDtos.KnowledgePointResponse> teacherTree(QuestionModels.Grade grade, String parentId) {
        List<KnowledgePoint> all = repository.listKnowledgePoints(true, grade);
        return tree(all, parentId);
    }

    /** 创建稳定编码的初中数学节点，父节点必须属于相同学段/年级/学科。 */
    @Transactional
    public QuestionDtos.KnowledgePointResponse create(QuestionDtos.CreateKnowledgePointRequest request) {
        validateParent(request.parentId() == null ? null : request.parentId().toString(), request.stage(), request.grade(), request.subject(), null);
        Instant now = Instant.now();
        KnowledgePoint value = new KnowledgePoint(UUID.randomUUID().toString(), request.code(), request.name().trim(),
                request.parentId() == null ? null : request.parentId().toString(), request.stage(), request.grade(),
                request.subject(), request.sortOrder(), request.enabled(), 0, now, now);
        try { repository.insertKnowledgePoint(value); }
        catch (DataIntegrityViolationException conflict) {
            throw new QuestionException(HttpStatus.CONFLICT, "QUESTION.KNOWLEDGE_CODE_CONFLICT", "知识点编码已存在或父节点不合法");
        }
        return response(value, List.of());
    }

    /** 更新名称、父级、年级、顺序和启用状态；稳定 ID/编码/学段/学科保持不变。 */
    @Transactional
    public QuestionDtos.KnowledgePointResponse update(String id, QuestionDtos.UpdateKnowledgePointRequest request) {
        KnowledgePoint current = repository.findKnowledgePoint(id).orElseThrow(KnowledgePointService::notFound);
        String parent = request.parentId() == null ? null : request.parentId().toString();
        validateParent(parent, current.stage(), request.grade(), current.subject(), id);
        if (parent != null && descendants(id).contains(parent)) {
            throw new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "QUESTION.KNOWLEDGE_CYCLE", "知识点父级不能形成环");
        }
        if (repository.updateKnowledgePoint(id, request.expectedRevision(), request.name().trim(), parent,
                request.grade(), request.sortOrder(), request.enabled(), Instant.now()) == 0) {
            throw new QuestionException(HttpStatus.CONFLICT, "QUESTION.REVISION_CONFLICT", "知识点已被更新，请刷新后重试");
        }
        return response(repository.findKnowledgePoint(id).orElseThrow(), List.of());
    }

    private void validateParent(String parentId, QuestionModels.Stage stage, QuestionModels.Grade grade,
            QuestionModels.Subject subject, String selfId) {
        if (parentId == null) return;
        if (parentId.equals(selfId)) throw new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT,
                "QUESTION.KNOWLEDGE_CYCLE", "知识点不能以自身为父级");
        KnowledgePoint parent = repository.findKnowledgePoint(parentId).orElseThrow(KnowledgePointService::notFound);
        if (parent.stage() != stage || parent.grade() != grade || parent.subject() != subject) {
            throw new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "QUESTION.KNOWLEDGE_PARENT_MISMATCH",
                    "父子知识点必须属于相同学段、年级和学科");
        }
    }

    private Set<String> descendants(String id) {
        Map<String, List<String>> children = new HashMap<>();
        for (KnowledgePoint point : repository.listKnowledgePoints(false, null)) {
            if (point.parentId() != null) children.computeIfAbsent(point.parentId(), ignored -> new ArrayList<>()).add(point.id());
        }
        Set<String> values = new HashSet<>();
        collect(id, children, values);
        return values;
    }

    private void collect(String id, Map<String, List<String>> children, Set<String> values) {
        for (String child : children.getOrDefault(id, List.of())) if (values.add(child)) collect(child, children, values);
    }

    private List<QuestionDtos.KnowledgePointResponse> tree(List<KnowledgePoint> points, String requestedParent) {
        Map<String, List<KnowledgePoint>> children = new HashMap<>();
        for (KnowledgePoint point : points) children.computeIfAbsent(point.parentId(), ignored -> new ArrayList<>()).add(point);
        Comparator<KnowledgePoint> order = Comparator.comparingInt(KnowledgePoint::sortOrder).thenComparing(KnowledgePoint::code);
        children.values().forEach(values -> values.sort(order));
        return children.getOrDefault(requestedParent, List.of()).stream().map(point -> node(point, children)).toList();
    }

    private QuestionDtos.KnowledgePointResponse node(KnowledgePoint point, Map<String, List<KnowledgePoint>> children) {
        return response(point, children.getOrDefault(point.id(), List.of()).stream().map(child -> node(child, children)).toList());
    }

    /** 将领域节点转为契约响应；调用方显式提供已经授权和排序的子节点。 */
    public static QuestionDtos.KnowledgePointResponse response(KnowledgePoint value,
            List<QuestionDtos.KnowledgePointResponse> children) {
        return new QuestionDtos.KnowledgePointResponse(value.id(), value.code(), value.name(), value.parentId(),
                value.stage(), value.grade(), value.subject(), value.sortOrder(), value.enabled(), value.revision(), children);
    }

    private static QuestionException notFound() {
        return new QuestionException(HttpStatus.NOT_FOUND, "QUESTION.NOT_FOUND", "知识点不存在");
    }
}
