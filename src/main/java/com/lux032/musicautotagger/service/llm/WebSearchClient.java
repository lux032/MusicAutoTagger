package com.lux032.musicautotagger.service.llm;

import lombok.Data;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 联网搜索适配层。
 *
 * 背景：早期只有一个 {@link NativeWebSearchClient}，直接依赖模型厂商自带的 Web Search 工具
 * （OpenAI Responses 的 web_search_preview / Anthropic 的 web_search_20250305）。
 * 实践中这条链路不可靠：
 *   - 大量第三方 OpenAI 兼容端点根本不实现 /responses 或不支持 tools；
 *   - 模型是否真的检索、返回几条 citation 完全不可控；
 *   - candidate 的 source_urls 由模型自述，来源门槛形同虚设。
 *
 * 因此抽出本接口，允许换成「外部检索器（Tavily） + 普通 chat 归纳」的实现
 * （{@link TavilyWebSearchClient}），此时 URL 由检索器提供，是真实存在的。
 *
 * 上层（OnlineIdentificationService）只依赖本接口，不感知具体来源。
 */
public interface WebSearchClient {

    /** 是否存在可用的搜索配置；false 时上层应报「联网搜索未启用」 */
    boolean hasEnabledEndpoint();

    /**
     * 执行一次联网搜索并让模型按 systemPrompt 的要求归纳。
     *
     * @return 文本 + 证据（citations）；失败时抛异常，不返回半成品
     */
    SearchResponse search(String systemPrompt, String userPrompt) throws LlmClient.LlmException;

    /** 供日志 / 落库展示的实现名，例如 native-openai / tavily */
    String name();

    // ==================== 数据结构 ====================

    @Data
    class Citation {
        private String url;
        private String title;
        private String snippet;
        private String domain;
        private long retrievedAt;
        private String reliability;

        public static Citation of(String url, String title, String snippet) {
            Citation c = new Citation();
            c.setUrl(url);
            c.setTitle(title);
            c.setSnippet(snippet);
            c.setRetrievedAt(System.currentTimeMillis());
            String host = null;
            try {
                host = URI.create(url).getHost();
            } catch (Exception ignored) {
                // 模型 / 检索器偶尔回传非法 URL，域名解析失败只降级为 LOW，不影响整体流程
            }
            c.setDomain(host);
            c.setReliability(SourceReliability.of(host));
            return c;
        }
    }

    @Data
    class SearchResponse {
        private String text;
        private String provider;
        private String model;
        private int endpointIndex;
        private List<Citation> citations = new ArrayList<>();

        /** URL 去重后追加 */
        public void addCitation(Citation citation) {
            if (citation == null || citation.getUrl() == null || citation.getUrl().isBlank()) {
                return;
            }
            for (Citation existing : citations) {
                if (citation.getUrl().equals(existing.getUrl())) {
                    return;
                }
            }
            citations.add(citation);
        }
    }

    // ==================== 域名可信度 ====================

    /**
     * 来源可信度注册表。
     *
     * 必须精确匹配或子域名匹配：早期的 contains() 写法会把 fake-discogs.example.com、
     * musicbrainz-data.example.org 这类伪造域名当成 HIGH，直接绕过来源门槛。
     */
    final class SourceReliability {

        private static final List<String> HIGH_TRUST = List.of(
            "musicbrainz.org", "discogs.com", "vgmdb.net", "spotify.com",
            "music.apple.com", "bandcamp.com");

        private static final List<String> MEDIUM_TRUST = List.of(
            "wikipedia.org", "amazon.com", "amazon.co.jp", "amazon.jp",
            "last.fm", "allmusic.com", "rateyourmusic.com");

        private SourceReliability() {
        }

        public static String of(String domain) {
            if (domain == null || domain.isBlank()) {
                return "LOW";
            }
            String d = domain.toLowerCase(Locale.ROOT);
            if (d.startsWith("www.")) {
                d = d.substring(4);
            }
            if (matches(d, HIGH_TRUST)) {
                return "HIGH";
            }
            if (matches(d, MEDIUM_TRUST)) {
                return "MEDIUM";
            }
            return "LOW";
        }

        /** 精确相等，或是其真子域（artist.bandcamp.com 这类合法子域仍然放行） */
        private static boolean matches(String domain, List<String> registry) {
            for (String allowed : registry) {
                if (domain.equals(allowed) || domain.endsWith("." + allowed)) {
                    return true;
                }
            }
            return false;
        }

        /** 默认优先检索的域名，作为 Tavily include_domains 的兜底值 */
        public static List<String> defaultPreferredDomains() {
            List<String> all = new ArrayList<>(HIGH_TRUST);
            all.addAll(MEDIUM_TRUST);
            return all;
        }
    }
}
