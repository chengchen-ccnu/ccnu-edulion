package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.service.KnowledgeGraphService;
import com.eduplatform.learning.service.LegacyQuestionImportService;
import com.eduplatform.learning.web.KnowledgeGraphDtos;
import com.eduplatform.learning.web.QuestionDtos;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/** T10B H2 合成验收：图方向、DAG、幂等、来源 parent 隔离及历史 tags 元数据。 */
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeGraphIntegrationTest {
    private static final String ADMIN = "00000000-0000-4000-8000-00000000a001";
    private static final String TEACHER = "00000000-0000-4000-8000-00000000a002";
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeGraphService graphs;
    @Autowired LegacyQuestionImportService legacy;
    @Autowired ObjectMapper mapper;
    @MockitoBean MediaClient media;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM learning_outbox_event"); jdbc.update("DELETE FROM question_legacy_review_item");
        jdbc.update("DELETE FROM question_legacy_import_issue"); jdbc.update("DELETE FROM question_legacy_source_ref");
        jdbc.update("DELETE FROM question_legacy_import_batch"); jdbc.update("DELETE FROM question_version_child");
        jdbc.update("DELETE FROM question_version_knowledge_point"); jdbc.update("DELETE FROM question_version_media");
        jdbc.update("DELETE FROM question_idempotency_request"); jdbc.update("DELETE FROM question_version");
        jdbc.update("UPDATE question_aggregate SET parent_question_id=NULL"); jdbc.update("DELETE FROM question_aggregate");
        jdbc.update("DELETE FROM question_import_task"); jdbc.update("DELETE FROM knowledge_point_prerequisite");
        jdbc.update("DELETE FROM knowledge_graph_import_issue"); jdbc.update("DELETE FROM knowledge_graph_import_relation");
        jdbc.update("DELETE FROM knowledge_graph_import_node"); jdbc.update("DELETE FROM knowledge_graph_import_batch");
        jdbc.update("DELETE FROM knowledge_graph_import_task"); jdbc.update("DELETE FROM knowledge_point");
    }

    @Test
    void dryRunValidatesDirectionMetricsAndNeverTurnsSourceParentIntoForeignKey() {
        var task = create(true, 2, 1, "KG_DRY");
        graphs.nodes(ADMIN, task.id(), new KnowledgeGraphDtos.NodeBatchRequest(UUID.randomUUID(), true,
                List.of(node("a", "整数", "03", "group-x", "a".repeat(64)),
                        node("b", "有理数", "03", "group-x", "b".repeat(64)))));
        var result = graphs.relations(ADMIN, task.id(), new KnowledgeGraphDtos.RelationBatchRequest(UUID.randomUUID(), true,
                List.of(relation("b", "a", "c".repeat(64)))));
        assertThat(result.status()).isEqualTo(KnowledgeGraphDtos.Status.COMPLETED);
        var summary = graphs.get(ADMIN, task.id()).summary();
        assertThat(summary.nodeCount()).isEqualTo(2); assertThat(summary.relationCount()).isEqualTo(1);
        assertThat(summary.longestPrerequisiteChain()).isEqualTo(1); assertThat(summary.isolatedNodeCount()).isZero();
        assertThat(summary.unresolvedParentKeyCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point", Integer.class)).isZero();
    }

    @Test
    void importIsIdempotentStoresBToAAndDefaultsNodesDisabled() {
        var task = create(false, 2, 1, "KG_IMPORT");
        UUID nodeBatch = UUID.randomUUID();
        var request = new KnowledgeGraphDtos.NodeBatchRequest(nodeBatch, true,
                List.of(node("a", "整数", "03", "opaque-parent", "a".repeat(64)), node("b", "有理数", "03", null, "b".repeat(64))));
        graphs.nodes(ADMIN, task.id(), request);
        assertThat(graphs.nodes(ADMIN, task.id(), request).replayed()).isTrue();
        graphs.relations(ADMIN, task.id(), new KnowledgeGraphDtos.RelationBatchRequest(UUID.randomUUID(), true,
                List.of(relation("b", "a", "c".repeat(64)))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point WHERE enabled=TRUE", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point WHERE parent_id IS NOT NULL OR taxonomy_parent_id IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point_prerequisite r JOIN knowledge_point p ON p.id=r.prerequisite_id JOIN knowledge_point d ON d.id=r.dependent_id WHERE p.source_know_id='b' AND d.source_know_id='a'", Integer.class)).isEqualTo(1);
    }

    @Test
    void finalValidationAggregatesMissingSelfCrossStageAndCycleWithoutBusinessWrites() {
        var task = create(false, 3, 5, "KG_INVALID");
        graphs.nodes(ADMIN, task.id(), new KnowledgeGraphDtos.NodeBatchRequest(UUID.randomUUID(), true,
                List.of(node("a", "A", "03", null, "a".repeat(64)), node("b", "B", "03", null, "b".repeat(64)), node("c", "C", "04", null, "c".repeat(64)))));
        graphs.relations(ADMIN, task.id(), new KnowledgeGraphDtos.RelationBatchRequest(UUID.randomUUID(), true,
                List.of(relation("a", "b", "1".repeat(64)), relation("b", "a", "2".repeat(64)),
                        relation("a", "a", "3".repeat(64)), relation("a", "c", "4".repeat(64)),
                        relation("missing", "a", "5".repeat(64)))));
        var summary = graphs.get(ADMIN, task.id()).summary();
        assertThat(summary.cycleDetected()).isTrue(); assertThat(summary.selfLoopCount()).isEqualTo(1);
        assertThat(summary.crossStageEdgeCount()).isEqualTo(1); assertThat(summary.missingEndpointCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point", Integer.class)).isZero();
        assertThat(graphs.issues(ADMIN, task.id(), 1, 20).items()).extracting(KnowledgeGraphDtos.ImportIssue::issueCode)
                .contains("SELF_LOOP", "CROSS_STAGE", "ENDPOINT_MISSING", "CYCLE_DETECTED");
    }

    @Test
    void enabledMappedPointsArePagedAndGraphNeighbourhoodIsBounded() {
        var task = create(false, 2, 1, "KG_QUERY");
        graphs.nodes(ADMIN, task.id(), new KnowledgeGraphDtos.NodeBatchRequest(UUID.randomUUID(), true,
                List.of(node("a", "整数", "03", null, "a".repeat(64)), node("b", "有理数", "03", null, "b".repeat(64)))));
        graphs.relations(ADMIN, task.id(), new KnowledgeGraphDtos.RelationBatchRequest(UUID.randomUUID(), true,
                List.of(relation("b", "a", "c".repeat(64)))));
        jdbc.update("UPDATE knowledge_point SET normalized_stage='JUNIOR',stage='JUNIOR',enabled=TRUE,knowledge_type_name='概念' WHERE source_system='KG_QUERY'");
        var page = graphs.search("JUNIOR", null, "数", 1, 20);
        assertThat(page.items()).hasSize(2); assertThat(page.items()).allMatch(item -> "概念".equals(item.knowledgeTypeName()));
        String center = jdbc.queryForObject("SELECT id FROM knowledge_point WHERE source_know_id='b'", String.class);
        var graph = graphs.graph(center, 1);
        assertThat(graph.directDependents()).singleElement().satisfies(item -> assertThat(item.name()).isEqualTo("整数"));
        assertThat(graph.edges()).singleElement();
    }

    @Test
    void legacyTagsBindBySourceIdAndPersistImmutableMetadata() throws Exception {
        Instant now = Instant.now(); String pointId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO knowledge_point(id,code,name,parent_id,stage,grade,subject,sort_order,enabled,revision,created_at,updated_at,source_system,source_know_id,discipline_code,education_stage_code,normalized_stage,knowledge_type_id,knowledge_type_name,level_sort_index,node_kind,source_status,source_content_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                pointId, "KG_tag-1", "有理数", null, "JUNIOR", null, "MATHEMATICS", 1, true, 0, Timestamp.from(now), Timestamp.from(now),
                "KG_TAG", "tag-1", "math", "03", "JUNIOR", "TYPE_A", "概念", 1, "point", "0", "a".repeat(64));
        var task = legacy.create(TEACHER, new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(), "KG_TAG", "d".repeat(64), false, 1)).response();
        var json = mapper.readTree("""
                {"exerciseID":"tag-question","exerciseType":"fillin","difficulty":1,"gradeIndex":7,"gradeName":"七年级",
                 "question":"计算","queAns":"1","solution":"","subQues":[],"tags":[{"knowId":"tag-1","knowName":"有理数","knowTypeId":"TYPE_A","priority":2,"sortIndex":7,"coreFlag":1,"examPointFlag":0,"lastExamPointFlag":1}]}
                """);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(json)));
        var result = legacy.submit(TEACHER, task.id(), new QuestionDtos.LegacyImportBatchRequest(UUID.randomUUID(), true,
                List.of(new QuestionDtos.LegacyImportItem("tag-question", "fixture/七年级/tag-question.json", "fixture", "七年级", hash, json))), "trace-tag");
        assertThat(result.importedCount()).isEqualTo(1);
        var metadata = jdbc.queryForMap("SELECT sequence_no,priority,core_flag,exam_point_flag,last_exam_point_flag,source_tag_sort_index FROM question_version_knowledge_point");
        assertThat(metadata).containsEntry("SEQUENCE_NO", 1).containsEntry("PRIORITY", 2).containsEntry("SOURCE_TAG_SORT_INDEX", 7);
        assertThat(((Boolean) metadata.get("CORE_FLAG"))).isTrue(); assertThat(((Boolean) metadata.get("EXAM_POINT_FLAG"))).isFalse();
    }

    private KnowledgeGraphDtos.ImportTask create(boolean dryRun, int nodes, int relations, String source) {
        return graphs.create(ADMIN, new KnowledgeGraphDtos.CreateImportRequest(UUID.randomUUID(), source, dryRun,
                "e".repeat(64), "f".repeat(64), nodes, relations)).response();
    }
    private static KnowledgeGraphDtos.NodeInput node(String id, String name, String stage, String parent, String hash) {
        return new KnowledgeGraphDtos.NodeInput(id, name, "math", stage, "TYPE_A", "#112233", 1, "point", parent, "0", hash);
    }
    private static KnowledgeGraphDtos.RelationInput relation(String prerequisite, String dependent, String hash) {
        return new KnowledgeGraphDtos.RelationInput(prerequisite, dependent, "PRECEDES", hash);
    }
}
