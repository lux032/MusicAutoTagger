package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmAlbumJudge;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.NativeWebSearchClient;
import com.lux032.musicautotagger.service.llm.TavilyWebSearchClient;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import com.lux032.musicautotagger.util.TagQualityEvaluator;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 实时联网识别：搜索只生成候选，绝不自动归档。 */
@Slf4j
public class OnlineIdentificationService {
    private static final String SYSTEM = "You are a music release research assistant with web search. "
        + "Use current web sources. Return ONLY one JSON object. Never invent MusicBrainz IDs or unsupported fields. "
        + "A formal candidate needs one HIGH reliability source, or two independent MEDIUM sources. LOW sources are clues only. "
        + "Include at most five candidates. Preserve source URLs.";

    private final MusicConfig config;
    // 两个实现都在启动时建好（构造开销只是持有 config 与一个 HttpClient），
    // 运行时按配置切换，避免「改了 provider 却要重启才生效」这种半生效状态
    private final WebSearchClient nativeSearchClient;
    private final WebSearchClient tavilySearchClient;
    private final ReviewQueueService reviewQueue;
    private final TagWriterService tagWriter;
    private final AudioFingerprintService fingerprintService;
    private final OnlineTrackMatcher trackMatcher;
    private final Gson gson = new Gson();

    public OnlineIdentificationService(MusicConfig config, ReviewQueueService reviewQueue,
                                       TagWriterService tagWriter, AudioFingerprintService fingerprintService) {
        this.config = config;
        this.nativeSearchClient = new NativeWebSearchClient(config);
        this.tavilySearchClient = new TavilyWebSearchClient(config, new LlmClient(config));
        this.reviewQueue = reviewQueue;
        this.tagWriter = tagWriter;
        this.fingerprintService = fingerprintService;
        this.trackMatcher = new OnlineTrackMatcher(tagWriter);
    }

    /**
     * 按 llm.webSearch.provider 选择联网搜索实现。每次调用都重新读配置，
     * 因此在 Web 面板上切换搜索来源无需重启。
     *
     * 模型原生 web search 在第三方端点上普遍不可用（不实现 /responses、不支持 tools、
     * 或声称支持但从不真正检索），因此提供 tavily 这条「外部检索器 + 普通 chat 归纳」的路径。
     */
    private WebSearchClient searchClient() {
        return "tavily".equalsIgnoreCase(config.getWebSearchProvider())
            ? tavilySearchClient : nativeSearchClient;
    }

    public boolean isAvailable() { return searchClient().hasEnabledEndpoint(); }

    public ReviewItem search(File albumRoot, String sourceType, boolean analyzeCover) throws Exception {
        return search(albumRoot, sourceType, analyzeCover, null);
    }

    /**
     * 只为指定的已有条目搜索（后台自动搜索用）。条目已不在待确认时返回 null，绝不新建条目。
     */
    public ReviewItem searchForExistingItem(String itemId, File albumRoot) throws Exception {
        return search(albumRoot, null, false, itemId);
    }

    private ReviewItem search(File albumRoot, String sourceType, boolean analyzeCover, String targetItemId) throws Exception {
        List<File> files = collect(albumRoot);
        if (files.isEmpty()) throw new IllegalArgumentException("recovery.no.audio.files");
        String evidenceHash = evidenceHash(files);
        ReviewItem item = targetItemId != null
            ? reviewQueue.prepareOnlineSearchForItem(targetItemId, albumRoot.getAbsolutePath(), evidenceHash)
            : reviewQueue.enqueueRecoveryFolder(albumRoot.getAbsolutePath(), sourceType,
                files, tagWriter, evidenceHash);
        if (item == null) {
            log.info("条目已不在待确认状态，取消联网搜索: {}", albumRoot.getName());
            return null;
        }

        final String itemId = item.getId();
        List<Integer> durations = fingerprintService.extractDurationSequence(files);
        // 共享条目的所有修改都在队列锁内完成，且只在条目仍待确认时生效
        if (!reviewQueue.updatePending(itemId, target -> {
            if (durations != null) {
                target.setDurationSequence(new ArrayList<>(durations));
            }
            if (analyzeCover) {
                appendResolutionNote(target, "已申请封面视觉分析；当前原生搜索适配器未检测到可移植的图片+搜索联合协议，已明确降级为文本联网搜索");
            }
            return true;
        })) {
            log.info("条目已不在待确认状态，取消联网搜索: {}", albumRoot.getName());
            return item;
        }
        String prompt = item.effectiveKind() == ReviewItem.Kind.TRACKS_MISSING && item.getMissingTracks() != null
            ? buildMissingTracksPrompt(item, albumRoot, files, durations)
            : buildPrompt(albumRoot, files, durations, analyzeCover);

        // 第一轮结果立即持久化；只有证据不足才执行第二轮。
        // 同一次识别的两轮搜索必须用同一个实现：中途配置变更不应让第二轮换一套证据来源
        WebSearchClient client = searchClient();
        WebSearchClient.SearchResponse first = client.search(SYSTEM, prompt);
        Parsed parsed = parse(first);
        matchAll(parsed, files, durations);
        if (!save(item, first, parsed, evidenceHash)) {
            return item;
        }

        if (parsed.candidates.isEmpty() || parsed.needsSecondRound) {
            String secondPrompt = prompt + "\nFIRST ROUND FINDINGS:\n" + first.getText()
                + "\nRun a second, narrower search using aliases, original-language names, catalog numbers, official pages, Discogs or VGMdb."
                + " Return the same JSON schema.";
            try {
                WebSearchClient.SearchResponse second = client.search(SYSTEM, secondPrompt);
                Parsed secondParsed = parse(second);
                merge(parsed, secondParsed);
                mergeEvidence(parsed.clues, second.getCitations());
                matchAll(parsed, files, durations);
                save(item, second, parsed, evidenceHash);
            } catch (LlmClient.LlmException e) {
                reviewQueue.updatePending(itemId, target -> {
                    appendResolutionNote(target, "第二轮联网搜索失败；已保留第一轮结果，可人工使用或重新搜索");
                    return true;
                });
            }
        }
        return item;
    }

    private String buildPrompt(File root, List<File> files, List<Integer> durations, boolean analyzeCover) {
        StringBuilder sb = new StringBuilder();
        sb.append("Research the exact music release represented by this local album folder.\n");
        sb.append("FOLDER: ").append(root.getName()).append("\nFILE COUNT: ").append(files.size()).append('\n');
        sb.append("DURATIONS: ").append(durations).append('\n');
        sb.append("LOCAL TRACKS:\n");
        int i=1;
        for (File file : files) {
            MusicMetadata md=tagWriter.readTags(file);
            sb.append(i++).append(". file=\"").append(file.getName()).append("\"");
            if(md!=null){ sb.append(" title=\"").append(nz(md.getTitle())).append("\" artist=\"")
                .append(nz(md.getArtist())).append("\" album=\"").append(nz(md.getAlbum())).append("\"")
                .append(" disc=").append(nz(md.getDiscNo())).append(" track=").append(nz(md.getTrackNo())); }
            sb.append('\n');
        }
        sb.append("VISUAL COVER ANALYSIS REQUESTED: ").append(analyzeCover)
            .append(". This API integration currently uses cover-derived text only when available; do not claim you saw an image unless image evidence is supplied.\n");
        sb.append("Search current official and music database sources. Return strict JSON:\n")
            .append("{\"needs_second_round\":boolean,\"candidates\":[{\"title\":string,\"artist\":string,")
            .append("\"album_artist\":string,\"release_date\":string,\"edition\":string,\"country\":string,")
            .append("\"label\":string,\"catalog_number\":string,\"cover_url\":string,\"confidence\":number 0..1,")
            .append("\"reason\":string,\"tracks\":[{\"disc\":number,\"track\":number,\"title\":string,\"artist\":string,\"duration\":number}],")
            .append("\"source_urls\":[string]}],\"clues\":[{\"url\":string,\"title\":string,\"snippet\":string}]}.");
        return sb.toString();
    }

    /**
     * 「锁定专辑缺曲」专用 prompt：已经知道最接近的版本，只需要找到收录了缺失曲目的完整版本
     * （单曲的 c/w 曲、豪华版 / 数字版 bonus、更大的原声合集等）以及完整曲目表。
     * 输出格式与普通搜索一致，复用同一套来源门槛与逐曲匹配。
     */
    String buildMissingTracksPrompt(ReviewItem item, File root, List<File> files, List<Integer> durations) {
        ReviewItem.MissingTracks missing = item.getMissingTracks();
        java.util.Map<String, ReviewItem.FileEntry> identified = new java.util.HashMap<>();
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry entry : item.getFiles()) {
                if (entry.getOriginalPath() != null) identified.put(new File(entry.getOriginalPath()).getAbsolutePath(), entry);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("A local album folder was matched to a MusicBrainz release, but that release does NOT contain\n")
          .append("all of the local tracks. Find the exact edition that contains ALL local tracks and its full tracklist.\n\n");
        sb.append("FOLDER: ").append(root.getName()).append("\nLOCAL FILE COUNT: ").append(files.size()).append('\n');
        sb.append("CLOSEST MUSICBRAINZ RELEASE: title=\"").append(nz(missing.getAlbumTitle()))
          .append("\" artist=\"").append(nz(missing.getAlbumArtist())).append("\"");
        if (missing.getReleaseTrackCount() > 0) sb.append(" tracks=").append(missing.getReleaseTrackCount());
        sb.append('\n');
        if (missing.getUnmatchedFiles() != null && !missing.getUnmatchedFiles().isEmpty()) {
            sb.append("LOCAL TRACKS CONFIRMED MISSING FROM THAT RELEASE:\n");
            for (java.util.Map.Entry<String, String> e : missing.getUnmatchedFiles().entrySet()) {
                sb.append("  - file=\"").append(e.getKey()).append("\" identified_title=\"").append(nz(e.getValue())).append("\"\n");
            }
        } else if (missing.getUnmatchedLocalCount() > 0) {
            sb.append("At least ").append(missing.getUnmatchedLocalCount())
              .append(" local track(s) have no duration counterpart in that release.\n");
        }
        sb.append("DURATIONS (seconds, local order): ").append(durations).append('\n');
        sb.append("LOCAL TRACKS (identified by acoustic fingerprint where available):\n");
        int i = 1;
        for (File file : files) {
            ReviewItem.FileEntry entry = identified.get(file.getAbsolutePath());
            MusicMetadata md = entry != null && entry.getMetadata() != null ? entry.getMetadata() : tagWriter.readTags(file);
            sb.append(i++).append(". file=\"").append(file.getName()).append("\"");
            if (md != null) {
                sb.append(" title=\"").append(nz(md.getTitle())).append("\" artist=\"").append(nz(md.getArtist())).append("\"");
            }
            if (entry != null && entry.getDuration() != null) sb.append(" duration=").append(entry.getDuration());
            sb.append('\n');
        }
        sb.append("Typical causes: a single whose coupling (c/w) track is missing on MusicBrainz, a deluxe / digital edition\n")
          .append("with bonus tracks, or a larger soundtrack collection. Prefer official store pages, the label site, VGMdb, Discogs.\n")
          .append("If the closest release above is in fact the right edition and the extra local files are simply not part of it,\n")
          .append("return it as a candidate and explain which local files do not belong to it.\n");
        sb.append("Return strict JSON:\n")
            .append("{\"needs_second_round\":boolean,\"candidates\":[{\"title\":string,\"artist\":string,")
            .append("\"album_artist\":string,\"release_date\":string,\"edition\":string,\"country\":string,")
            .append("\"label\":string,\"catalog_number\":string,\"cover_url\":string,\"confidence\":number 0..1,")
            .append("\"reason\":string,\"tracks\":[{\"disc\":number,\"track\":number,\"title\":string,\"artist\":string,\"duration\":number}],")
            .append("\"source_urls\":[string]}],\"clues\":[{\"url\":string,\"title\":string,\"snippet\":string}]}.");
        return sb.toString();
    }

    private Parsed parse(WebSearchClient.SearchResponse response) throws LlmClient.LlmException {
        JsonObject json = LlmAlbumJudge.parseJsonObject(response.getText());
        if (json == null) throw new LlmClient.LlmException("llm.web.search.response.not.json");
        Parsed result=new Parsed();
        result.needsSecondRound=bool(json,"needs_second_round");
        result.clues.addAll(toEvidence(response.getCitations()));
        JsonArray candidates=json.getAsJsonArray("candidates");
        if(candidates!=null) for(JsonElement el:candidates){
            if(result.candidates.size()>=5) break;
            if(!el.isJsonObject()) continue;
            ReviewItem.OnlineCandidate c=parseCandidate(el.getAsJsonObject(),result.clues);
            if(c.isOfficialCandidate()) result.candidates.add(c);
        }
        JsonArray clues=json.getAsJsonArray("clues");
        if(clues!=null) for(JsonElement el:clues){
            if(!el.isJsonObject()) continue;
            JsonObject o=el.getAsJsonObject(); ReviewItem.OnlineEvidence e=new ReviewItem.OnlineEvidence();
            e.setUrl(str(o,"url"));e.setTitle(str(o,"title"));e.setSnippet(str(o,"snippet"));e.setRetrievedAt(System.currentTimeMillis());e.setReliability("LOW");result.clues.add(e);
        }
        return result;
    }

    private ReviewItem.OnlineCandidate parseCandidate(JsonObject o,List<ReviewItem.OnlineEvidence> allEvidence){
        ReviewItem.OnlineCandidate c=new ReviewItem.OnlineCandidate();c.setId(UUID.randomUUID().toString());
        c.setTitle(str(o,"title"));c.setArtist(str(o,"artist"));c.setAlbumArtist(str(o,"album_artist"));
        c.setReleaseDate(str(o,"release_date"));c.setEdition(str(o,"edition"));c.setCountry(str(o,"country"));c.setLabel(str(o,"label"));
        c.setCatalogNumber(str(o,"catalog_number"));c.setCoverUrl(str(o,"cover_url"));c.setConfidence(normalizeConfidence(number(o,"confidence")));c.setReason(str(o,"reason"));
        Set<String> sourceUrls=new HashSet<>();JsonArray urls=o.getAsJsonArray("source_urls");if(urls!=null)for(JsonElement u:urls)if(u.isJsonPrimitive())sourceUrls.add(u.getAsString());
        // 模型未标注 source_urls 时不得继承全部检索证据：否则只要本轮搜索出现过任意一个
        // HIGH 域名，候选就会无条件通过来源门槛，恰好在模型输出不规范时失效。
        for(ReviewItem.OnlineEvidence e:allEvidence)if(sourceUrls.contains(e.getUrl()))c.getSources().add(e);
        long high=c.getSources().stream().filter(e->"HIGH".equals(e.getReliability())).count();
        long mediumDomains=c.getSources().stream().filter(e->"MEDIUM".equals(e.getReliability())).map(ReviewItem.OnlineEvidence::getDomain).distinct().count();
        c.setOfficialCandidate(high>=1||mediumDomains>=2);
        JsonArray tracks=o.getAsJsonArray("tracks");if(tracks!=null)for(JsonElement te:tracks){if(!te.isJsonObject())continue;JsonObject t=te.getAsJsonObject();ReviewItem.OnlineTrack track=new ReviewItem.OnlineTrack();track.setDiscNo(integer(t,"disc"));track.setTrackNo(integer(t,"track"));track.setTitle(str(t,"title"));track.setArtist(str(t,"artist"));track.setDuration(integerNullable(t,"duration"));c.getTracks().add(track);}
        return c;
    }

    /** 提前算好逐曲匹配与覆盖率，人工确认前就能在界面上看到到底匹上了几首。 */
    private void matchAll(Parsed parsed, List<File> files, List<Integer> durations) {
        for (ReviewItem.OnlineCandidate candidate : parsed.candidates) {
            trackMatcher.match(candidate, files, durations);
        }
    }

    /** 搜索返回时条目可能已被人工处理：只在仍待确认时写入，返回是否写入 */
    /** 搜索返回时条目可能已被人工处理：检查与写入在队列锁内一次完成，返回是否写入 */
    private boolean save(ReviewItem item,WebSearchClient.SearchResponse response,Parsed parsed,String hash){
        // 深拷贝：第二轮 merge / matchAll 会原地修改 parsed 里的候选和逐曲匹配，
        // 挂到队列条目上的对象图必须与之完全隔离，不能在锁外被改动
        List<ReviewItem.OnlineCandidate> candidates=deepCopy(parsed.candidates,ReviewItem.OnlineCandidate[].class);
        List<ReviewItem.OnlineEvidence> clues=deepCopy(parsed.clues,ReviewItem.OnlineEvidence[].class);
        String note=candidates.isEmpty()?"联网搜索未找到满足来源门槛的正式候选，已保存线索":"联网搜索完成，等待人工确认";
        long now=System.currentTimeMillis();
        boolean saved=reviewQueue.updatePending(item.getId(),target->{
            target.setOnlineCandidates(candidates);target.setOnlineClues(clues);target.setOnlineSearchedAt(now);
            target.setOnlineSearchProvider(response.getProvider());target.setOnlineSearchModel(response.getModel());target.setEvidenceHash(hash);target.setOnlineEvidenceStale(false);
            String existing=target.getResolutionNote();
            if(existing==null||existing.isBlank()||isOnlineSearchTemplate(existing))target.setResolutionNote(note);else appendResolutionNote(target,note);
            return true;
        });
        if(!saved)log.info("条目已被处理，丢弃联网搜索结果: {}",item.getFolderName());
        return saved;
    }
    static <T> List<T> deepCopy(List<T> source,Class<T[]> arrayType){
        if(source==null)return new ArrayList<>();
        Gson gson=new Gson();
        return new ArrayList<>(java.util.Arrays.asList(gson.fromJson(gson.toJson(source),arrayType)));
    }
    private boolean isOnlineSearchTemplate(String note){return note.startsWith("联网搜索完成")||note.startsWith("联网搜索未找到满足来源门槛");}
    private void appendResolutionNote(ReviewItem item,String note){String existing=item.getResolutionNote();item.setResolutionNote(existing==null||existing.isBlank()?note:existing+" | "+note);}
    private void merge(Parsed a,Parsed b){Set<String> keys=new HashSet<>();for(ReviewItem.OnlineCandidate c:a.candidates)keys.add(key(c));for(ReviewItem.OnlineCandidate c:b.candidates)if(keys.add(key(c))&&a.candidates.size()<5)a.candidates.add(c);}
    private String key(ReviewItem.OnlineCandidate c){return (nz(c.getArtist())+"|"+nz(c.getTitle())+"|"+nz(c.getReleaseDate())+"|"+nz(c.getEdition())).toLowerCase(Locale.ROOT);}
    private void mergeEvidence(List<ReviewItem.OnlineEvidence> target,List<WebSearchClient.Citation> citations){Set<String> urls=new HashSet<>();for(ReviewItem.OnlineEvidence e:target)urls.add(e.getUrl());for(ReviewItem.OnlineEvidence e:toEvidence(citations))if(urls.add(e.getUrl()))target.add(e);}
    private List<ReviewItem.OnlineEvidence> toEvidence(List<WebSearchClient.Citation> citations){List<ReviewItem.OnlineEvidence> out=new ArrayList<>();for(WebSearchClient.Citation c:citations){ReviewItem.OnlineEvidence e=new ReviewItem.OnlineEvidence();e.setUrl(c.getUrl());e.setDomain(c.getDomain());e.setTitle(c.getTitle());e.setSnippet(c.getSnippet());e.setRetrievedAt(c.getRetrievedAt());e.setReliability(c.getReliability());out.add(e);}return out;}
    private List<File> collect(File root)throws Exception{List<File> files=new ArrayList<>();if(root.isFile())files.add(root);else try(var s=Files.walk(root.toPath())){s.filter(Files::isRegularFile).map(java.nio.file.Path::toFile).filter(f->isAudio(f)).forEach(files::add);}files.sort(Comparator.comparing(File::getAbsolutePath));return files;}
    private boolean isAudio(File f){String n=f.getName().toLowerCase();for(String ext:config.getSupportedFormats())if(n.endsWith("."+ext.toLowerCase()))return true;return false;}
    /** 供人工确认阶段重算并比对，确保搜索后文件未被改动。必须与搜索时采用同一套收集语义。 */
    public String evidenceHashForFolder(File albumRoot)throws Exception{return evidenceHash(collect(albumRoot));}
    public String evidenceHash(List<File> files)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");for(File f:files){md.update(f.getAbsolutePath().getBytes(StandardCharsets.UTF_8));md.update(Long.toString(f.length()).getBytes());md.update(Long.toString(f.lastModified()).getBytes());MusicMetadata m=tagWriter.readTags(f);if(m!=null)md.update((nz(m.getTitle())+nz(m.getArtist())+nz(m.getAlbum())+nz(m.getTrackNo())+nz(m.getDiscNo())).getBytes(StandardCharsets.UTF_8));}StringBuilder sb=new StringBuilder();for(byte b:md.digest())sb.append(String.format("%02x",b));return sb.toString();}
    private static class Parsed{boolean needsSecondRound;List<ReviewItem.OnlineCandidate> candidates=new ArrayList<>();List<ReviewItem.OnlineEvidence> clues=new ArrayList<>();}
    private String str(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():null;}private boolean bool(JsonObject o,String k){try{return o.has(k)&&o.get(k).getAsBoolean();}catch(Exception e){return false;}}private double number(JsonObject o,String k){try{return o.get(k).getAsDouble();}catch(Exception e){return 0;}}
    /** 兼容模型偶尔返回 85 而不是 0.85，同时挡住 NaN/Infinity 和越界值。 */
    private double normalizeConfidence(double value){if(!Double.isFinite(value))return 0;if(value>1&&value<=100)value/=100;return Math.max(0,Math.min(1,value));}
    private int integer(JsonObject o,String k){Integer i=integerNullable(o,k);return i==null?0:i;}private Integer integerNullable(JsonObject o,String k){try{return o.has(k)?o.get(k).getAsInt():null;}catch(Exception e){return null;}}private String nz(String s){return s==null?"":s;}
}
