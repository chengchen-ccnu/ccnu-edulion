package com.eduplatform.agentaccess.service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 加载并检索随应用发布的中文帮助知识库。
 *
 * <p>输入来自 classpath manifest 与其中列出的只读版本文件；启动时会校验结构、版本、重复规则、
 * 面向学生正文和安全路由，任一错误都会让应用启动失败，绝不会退回模型自由猜测。</p>
 */
@Component
public class UserHelpKnowledgeBase {
    private static final Pattern VERSION = Pattern.compile("^[0-9]{4}\\.[0-9]{2}\\.[0-9]+$");
    private static final Pattern ARTICLE_ID = Pattern.compile("^help_[a-z0-9_]{3,64}$");
    private static final Pattern ROUTE = Pattern.compile("^/[a-z0-9-]+(?:/[a-z0-9-]+)*$");
    private static final Pattern DANGEROUS_TEXT = Pattern.compile(
            "(?i)(?:\\bT\\d{2}(?:\\.\\d+)*\\b|PR\\s*#?\\d+|agent-access-service|operation-service|"
                    + "数据库|环境变量|测试账号|internal\\b|api\\s*key|token\\b|隐藏权限|维护指令|"
                    + "忽略.{0,8}(?:指令|规则)|system\\s*prompt|prompt\\s*injection)");
    private static final Set<String> TOPICS = Set.of("AUTH", "ACCOUNT_SECURITY", "COURSES",
            "LEARNING_CONTENT", "PRACTICE", "ASSESSMENT", "RESULTS", "PROGRESS", "AI_BOUNDARIES");
    private static final Set<String> ROUTE_ALLOWLIST = Set.of(
            "/login", "/register", "/forgot-password", "/profile/security", "/courses",
            "/my-learning", "/practice");

    private final ObjectMapper mapper;
    private volatile Snapshot snapshot;

    public UserHelpKnowledgeBase(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取 manifest 指向的全部发布版本并选择当前版本；返回前不会产生外部写入。 */
    @PostConstruct
    public void load() {
        try {
            validateSchemaResource();
            Manifest manifest = read("help-kb/manifest.json", Manifest.class);
            validateManifest(manifest);
            KnowledgeFile current = null;
            for (VersionEntry entry : manifest.versions()) {
                KnowledgeFile file = read("help-kb/" + entry.file(), KnowledgeFile.class);
                validateFile(file, entry.version());
                if (entry.file().equals(manifest.currentFile())) current = file;
            }
            if (current == null || !current.version().equals(manifest.currentVersion())) {
                throw invalid("manifest 当前版本与文件不一致");
            }
            validateGlobalUniqueness(current.articles());
            snapshot = new Snapshot(manifest.currentVersion(), List.copyOf(current.articles()));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("帮助知识库无法读取", failure);
        }
    }

    /**
     * 在当前版本内做有界、可解释的规则与关键词匹配。
     *
     * @param query 学生平台使用问题，调用入口已限制长度
     * @param limit 最多返回 1～5 条安全正文证据
     * @return 当前版本及有限证据；不包含权重、内部文件路径或未命中文章
     */
    public SearchResponse search(String query, int limit) {
        Snapshot active = snapshot;
        if (active == null) throw invalid("帮助知识库尚未加载");
        String normalizedQuery = normalize(query);
        List<ScoredArticle> matches = new ArrayList<>();
        for (Article article : active.articles()) {
            boolean direct = article.highPrecisionRules().stream()
                    .map(UserHelpKnowledgeBase::normalize).anyMatch(normalizedQuery::equals);
            int score = direct ? 10_000 : score(normalizedQuery, article);
            if (score > 0) matches.add(new ScoredArticle(article, direct, score));
        }
        matches.sort(Comparator.comparing(ScoredArticle::direct).reversed()
                .thenComparing(ScoredArticle::score, Comparator.reverseOrder())
                .thenComparing(item -> item.article().articleId()));
        List<HelpEvidence> evidence = matches.stream().limit(Math.max(1, Math.min(5, limit)))
                .map(item -> new HelpEvidence(item.article().articleId(), item.article().title(),
                        item.article().body(), item.article().version(), item.direct(), item.article().appRoute()))
                .toList();
        return new SearchResponse(active.version(), evidence);
    }

    /** 供构建期测试复用与启动期相同的语义校验，不会替换当前已加载快照。 */
    void validateCandidateJson(String json, String expectedVersion) {
        try {
            KnowledgeFile file = mapper.readValue(json, KnowledgeFile.class);
            validateFile(file, expectedVersion);
            validateGlobalUniqueness(file.articles());
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("帮助候选版本无法解析", failure);
        }
    }

    private int score(String query, Article article) {
        int score = 0;
        for (String keyword : article.keywords()) {
            String candidate = normalize(keyword);
            if (query.contains(candidate)) score += Math.max(2, candidate.length());
        }
        if (query.contains(normalize(article.title()))) score += 8;
        return score;
    }

    private void validateSchemaResource() throws Exception {
        JsonNode schema = readTree("help-kb/user-help.schema.json");
        if (!"https://json-schema.org/draft/2020-12/schema".equals(schema.path("$schema").asText())
                || !schema.path("required").isArray() || !schema.path("properties").has("articles")) {
            throw invalid("帮助 JSON Schema 不完整");
        }
    }

    private static void validateManifest(Manifest manifest) {
        if (manifest.schemaVersion() != 1 || !"zh-CN".equals(manifest.locale())
                || !VERSION.matcher(text(manifest.currentVersion())).matches()
                || manifest.effectiveAt() == null || manifest.versions() == null || manifest.versions().isEmpty()
                || manifest.currentFile() == null || !manifest.currentFile().startsWith("versions/")
                || manifest.currentFile().contains("..") || !manifest.currentFile().endsWith(".json")) {
            throw invalid("帮助 manifest 不合法");
        }
        Instant.parse(manifest.effectiveAt());
        Set<String> versions = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (VersionEntry entry : manifest.versions()) {
            if (entry == null || !VERSION.matcher(text(entry.version())).matches()
                    || entry.file() == null || !entry.file().startsWith("versions/")
                    || entry.file().contains("..") || !entry.file().endsWith(".json")
                    || !versions.add(entry.version()) || !files.add(entry.file())) {
                throw invalid("帮助 manifest 含重复或非法版本");
            }
        }
        if (!versions.contains(manifest.currentVersion()) || !files.contains(manifest.currentFile())) {
            throw invalid("帮助 manifest 未列出当前版本");
        }
    }

    private static void validateFile(KnowledgeFile file, String expectedVersion) {
        if (file == null || !expectedVersion.equals(file.version()) || !"zh-CN".equals(file.locale())
                || file.articles() == null || file.articles().isEmpty() || file.articles().size() > 100) {
            throw invalid("帮助版本文件结构或版本不合法");
        }
        Set<String> ids = new HashSet<>();
        for (Article article : file.articles()) {
            if (article == null || !ARTICLE_ID.matcher(text(article.articleId())).matches()
                    || !TOPICS.contains(article.topic()) || !expectedVersion.equals(article.version())
                    || length(article.title(), 2, 80) == false || length(article.body(), 30, 1200) == false
                    || article.keywords() == null || article.keywords().size() < 2 || article.keywords().size() > 12
                    || article.highPrecisionRules() == null || article.highPrecisionRules().isEmpty()
                    || article.highPrecisionRules().size() > 12 || !ids.add(article.articleId())) {
                throw invalid("帮助文章结构、ID 或版本不合法");
            }
            validateStrings(article.keywords(), 2, 30, "关键词");
            validateStrings(article.highPrecisionRules(), 2, 80, "高精度规则");
            rejectDangerous(article.title());
            rejectDangerous(article.body());
            if (article.appRoute() != null && (!ROUTE.matcher(article.appRoute()).matches()
                    || !ROUTE_ALLOWLIST.contains(article.appRoute()) || article.appRoute().startsWith("//")
                    || article.appRoute().startsWith("/api/") || article.appRoute().startsWith("/internal/")
                    || article.appRoute().contains("{") || article.appRoute().contains(".."))) {
                throw invalid("帮助文章包含不安全应用内路由");
            }
        }
    }

    private static void validateGlobalUniqueness(List<Article> articles) {
        Set<String> rules = new HashSet<>();
        for (Article article : articles) {
            for (String rule : article.highPrecisionRules()) {
                if (!rules.add(normalize(rule))) throw invalid("帮助文章包含重复高精度规则");
            }
        }
    }

    private static void validateStrings(List<String> values, int min, int max, String label) {
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (!length(value, min, max) || !unique.add(normalize(value))) throw invalid("帮助" + label + "不合法或重复");
            rejectDangerous(value);
        }
    }

    private static void rejectDangerous(String value) {
        if (DANGEROUS_TEXT.matcher(value).find()) throw invalid("帮助正文包含内部术语或危险指令");
    }

    private <T> T read(String path, Class<T> type) throws Exception {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(input, type);
        }
    }

    private JsonNode readTree(String path) throws Exception {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(input);
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？、；：,.!?;:‘’“”\"']+", "");
    }

    private static boolean length(String value, int min, int max) {
        return value != null && value.length() >= min && value.length() <= max;
    }

    private static String text(String value) { return value == null ? "" : value; }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
    private static IllegalStateException invalid(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    public record SearchResponse(String version, List<HelpEvidence> evidence) {}
    public record HelpEvidence(String evidenceId, String title, String summary, String version,
            boolean directAnswer, String appRoute) {}
    private record Snapshot(String version, List<Article> articles) {}
    private record ScoredArticle(Article article, boolean direct, int score) {}
    private record Manifest(int schemaVersion, String locale, String currentVersion, String effectiveAt,
            String currentFile, List<VersionEntry> versions) {}
    private record VersionEntry(String version, String file) {}
    private record KnowledgeFile(String version, String locale, List<Article> articles) {}
    /** 使用普通 bean 承载可选 appRoute，避免全局严格 Jackson 把缺省可选字段误判为缺失必填项。 */
    private static final class Article {
        public String articleId;
        public String topic;
        public String title;
        public List<String> keywords;
        public List<String> highPrecisionRules;
        public String body;
        public String version;
        public String appRoute;

        public Article() {}
        String articleId() { return articleId; }
        String topic() { return topic; }
        String title() { return title; }
        List<String> keywords() { return keywords; }
        List<String> highPrecisionRules() { return highPrecisionRules; }
        String body() { return body; }
        String version() { return version; }
        String appRoute() { return appRoute; }
    }
}
