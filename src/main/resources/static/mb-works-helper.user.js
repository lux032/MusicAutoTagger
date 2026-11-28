// ==UserScript==
// @name         MusicAutoTagger · MusicBrainz Works & Credits Helper
// @namespace    https://github.com/lux032/MusicAutoTagger
// @version      1.1.0
// @description  将 MusicAutoTagger 整理的词曲编关系一键预填到 MusicBrainz 关系编辑页（仅写入编辑器供人工复核，绝不自动提交）
// @author       MusicAutoTagger
// @match        https://musicbrainz.org/release/*/edit-relationships*
// @match        https://beta.musicbrainz.org/release/*/edit-relationships*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';

    const HASH_PREFIX = '#mat-works=';
    const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    // 严格限定明确的版本/编曲/伴奏标记，避免把 "(Midnight)" 等标题本体括号误判为衍生版
    const ALT_VERSION_SUFFIX_RE = /\s*[\(\uFF08\[](?:[^)\uFF09\]]*\b(?:ver\.?|version|remix|mix|edit|inst\.?|instrumental|off\s*vocal|karaoke|acoustic|live|short\s*ver\.?|full\s*ver\.?|tv\s*size|radio\s*edit|piano\s*ver\.?|orchestra(?:l)?|ballad|dub|club\s*mix|extended|reprise|demo|arrangement|arr\.?|remaster(?:ed)?)\b[^)\uFF09\]]*|(?:カラオケ|インスト|リアレンジ|アレンジ|リミックス)[^)\uFF09\]]*)[\)\uFF09\]]\s*$/i;
    const REL_STATUS_ADD = 1;
    const REL_STATUS_REMOVE = 3;
    const FETCH_TIMEOUT_MS = 10000;
    const FETCH_MAX_RETRIES = 2;

    const artistCache = new Map();
    const fetchedWorkRelsCache = new Map();
    let currentPayload = null;
    let trackPlans = [];
    let isApplying = false;

    function sleep(ms) {
        return new Promise((resolve) => setTimeout(resolve, ms));
    }

    function nextFrame() {
        return new Promise((resolve) => {
            if (typeof requestAnimationFrame === 'function') {
                requestAnimationFrame(() => setTimeout(resolve, 16));
            } else {
                setTimeout(resolve, 16);
            }
        });
    }

    async function waitForState(predicate, timeoutMs) {
        const deadline = Date.now() + (timeoutMs || 2500);
        while (Date.now() < deadline) {
            const st = window.MB && window.MB.relationshipEditor && window.MB.relationshipEditor.state;
            if (st && predicate(st)) {
                return true;
            }
            await nextFrame();
        }
        const finalState = window.MB && window.MB.relationshipEditor && window.MB.relationshipEditor.state;
        return Boolean(finalState && predicate(finalState));
    }

    function isValidUuid(v) {
        return typeof v === 'string' && UUID_RE.test(v.trim());
    }

    function safeHttpUrl(raw) {
        if (!raw) return null;
        try {
            const u = new URL(String(raw).trim());
            if (u.protocol === 'https:' || u.protocol === 'http:') {
                return u.href;
            }
        } catch (e) { /* invalid url */ }
        return null;
    }

    function decodeBase64UrlUtf8(b64url) {
        let b64 = String(b64url).replace(/-/g, '+').replace(/_/g, '/');
        while (b64.length % 4 !== 0) b64 += '=';
        const bin = atob(b64);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) {
            bytes[i] = bin.charCodeAt(i);
        }
        return new TextDecoder('utf-8').decode(bytes);
    }

    function normalizeTitleKey(title) {
        return String(title || '')
            .trim()
            .toLowerCase()
            .replace(/[\u3000\s]+/g, ' ');
    }

    function stripVersionSuffix(title) {
        const raw = String(title || '').trim();
        const stripped = raw.replace(ALT_VERSION_SUFFIX_RE, '').trim();
        return stripped && stripped !== raw ? stripped : null;
    }

    function el(tag, attrs, children) {
        const node = document.createElement(tag);
        if (attrs) {
            for (const [k, v] of Object.entries(attrs)) {
                if (v == null || v === false) continue;
                if (k === 'className') node.className = String(v);
                else if (k === 'text') node.textContent = String(v);
                else if (k === 'checked' || k === 'disabled') node[k] = Boolean(v);
                else if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
                else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v);
                else if (v === true) node.setAttribute(k, '');
                else node.setAttribute(k, String(v));
            }
        }
        if (Array.isArray(children)) {
            for (const child of children) {
                if (child == null) continue;
                if (typeof child === 'string') node.appendChild(document.createTextNode(child));
                else node.appendChild(child);
            }
        }
        return node;
    }

    function isEditorReady() {
        const mb = window.MB;
        return Boolean(
            mb &&
            mb.relationshipEditor &&
            typeof mb.relationshipEditor.dispatch === 'function' &&
            typeof mb.relationshipEditor.getRelationshipStateId === 'function' &&
            mb.relationshipEditor.state &&
            mb.relationshipEditor.state.entity &&
            mb.tree &&
            typeof mb.tree.iterate === 'function' &&
            mb.linkedEntities &&
            mb.linkedEntities.link_type
        );
    }

    async function waitForEditor(timeoutMs) {
        const deadline = Date.now() + (timeoutMs || 15000);
        while (Date.now() < deadline) {
            if (isEditorReady()) return true;
            await sleep(200);
        }
        return isEditorReady();
    }

    function findLinkType(type0, type1, name) {
        const map = window.MB && window.MB.linkedEntities && window.MB.linkedEntities.link_type;
        if (!map) return null;
        for (const lt of Object.values(map)) {
            if (lt && lt.type0 === type0 && lt.type1 === type1 && lt.name === name) {
                return lt;
            }
        }
        return null;
    }

    function findRecordingOfLinkType() {
        const mb = window.MB;
        const gid = mb && mb.constants && mb.constants.RECORDING_OF_LINK_TYPE_GID;
        if (gid && mb.linkedEntities && mb.linkedEntities.link_type && mb.linkedEntities.link_type[gid]) {
            return mb.linkedEntities.link_type[gid];
        }
        return findLinkType('recording', 'work', 'performance');
    }

    function findSongWorkTypeId() {
        const map = window.MB && window.MB.linkedEntities && window.MB.linkedEntities.work_type;
        if (map) {
            for (const wt of Object.values(map)) {
                if (wt && wt.name === 'Song' && typeof wt.id === 'number') {
                    return wt.id;
                }
            }
        }
        return 17; // MusicBrainz default WorkType ID for "Song"
    }

    function findLanguageEntity(iso3) {
        if (!iso3) return null;
        const target = String(iso3).trim().toLowerCase();
        const map = window.MB && window.MB.linkedEntities && window.MB.linkedEntities.language;
        if (!map) return null;
        for (const lang of Object.values(map)) {
            if (lang && String(lang.iso_code_3 || '').toLowerCase() === target) {
                return lang;
            }
        }
        return null;
    }

    function getEditorTrackIndex(stateOverride) {
        const mb = window.MB;
        const state = stateOverride || (mb && mb.relationshipEditor && mb.relationshipEditor.state);
        const byRecordingGid = new Map();
        const byRecordingId = new Map();
        const byPos = new Map();

        if (state && state.mediums) {
            for (const [medium, recordingStateTree] of mb.tree.iterate(state.mediums)) {
                const recStateByIdLocal = new Map();
                for (const recState of mb.tree.iterate(recordingStateTree)) {
                    const rec = recState.recording;
                    if (!rec) continue;
                    recStateByIdLocal.set(rec.id, recState);
                    byRecordingId.set(rec.id, { medium, recordingState: recState });
                    if (rec.gid) {
                        byRecordingGid.set(rec.gid.toLowerCase(), { medium, recordingState: recState });
                    }
                }
                const loaded = state.loadedTracks && typeof state.loadedTracks.get === 'function'
                    ? state.loadedTracks.get(medium.position)
                    : null;
                const tracks = (loaded && loaded.length) ? loaded : (medium.tracks || []);
                for (let idx = 0; idx < tracks.length; idx++) {
                    const tr = tracks[idx];
                    if (!tr || !tr.recording) continue;
                    const recState = recStateByIdLocal.get(tr.recording.id);
                    if (!recState) continue;
                    const pos = Number(tr.position) || (idx + 1);
                    const key = (Number(medium.position) || 1) + ':' + pos;
                    byPos.set(key, { medium, track: tr, recordingState: recState });
                }
            }
        }
        return { byRecordingGid, byRecordingId, byPos };
    }

    function iterateTargetTypeGroupRelationships(targetTypeGroups) {
        const result = [];
        if (!targetTypeGroups || !window.MB || !window.MB.tree) return result;
        const tree = window.MB.tree;
        for (const [, linkTypeGroups] of tree.iterate(targetTypeGroups)) {
            for (const linkTypeGroup of tree.iterate(linkTypeGroups)) {
                for (const phraseGroup of tree.iterate(linkTypeGroup.phraseGroups)) {
                    for (const rel of tree.iterate(phraseGroup.relationships)) {
                        if (rel && rel._status !== REL_STATUS_REMOVE) {
                            result.push(rel);
                        }
                    }
                }
            }
        }
        return result;
    }

    /** 仅返回当前有效（未标删除）performance 关系所关联的 WorkState 列表。 */
    function getActiveRelatedWorks(recordingState) {
        if (!recordingState || !recordingState.relatedWorks || !window.MB || !window.MB.tree) return [];
        const activeWorkIds = new Set();
        const activeRels = iterateTargetTypeGroupRelationships(recordingState.targetTypeGroups);
        for (const rel of activeRels) {
            if (rel.entity0 && rel.entity0.entityType === 'recording'
                    && rel.entity1 && rel.entity1.entityType === 'work') {
                activeWorkIds.add(rel.entity1.id);
            }
        }
        const out = [];
        const hasRelInfo = activeRels.length > 0 || Boolean(recordingState.targetTypeGroups);
        for (const workState of window.MB.tree.iterate(recordingState.relatedWorks)) {
            if (!workState || !workState.work) continue;
            if (!hasRelInfo || activeWorkIds.has(workState.work.id)) {
                out.push(workState);
            }
        }
        return out;
    }

    function findActiveRelatedWorkById(recordingState, workId) {
        for (const ws of getActiveRelatedWorks(recordingState)) {
            if (ws.work && ws.work.id === workId) {
                return ws;
            }
        }
        return null;
    }

    function hasExistingArtistRelationship(targetTypeGroups, linkTypeId, artistGid) {
        const wantGid = String(artistGid || '').toLowerCase();
        for (const rel of iterateTargetTypeGroupRelationships(targetTypeGroups)) {
            if (rel.linkTypeID !== linkTypeId) continue;
            const e0 = rel.entity0;
            const e1 = rel.entity1;
            if (e0 && e0.entityType === 'artist' && String(e0.gid || '').toLowerCase() === wantGid) {
                return true;
            }
            if (e1 && e1.entityType === 'artist' && String(e1.gid || '').toLowerCase() === wantGid) {
                return true;
            }
        }
        return false;
    }

    function hasFetchedRawArtistRelationship(rawRelationships, linkTypeId, artistGid) {
        if (!Array.isArray(rawRelationships)) return false;
        const wantGid = String(artistGid || '').toLowerCase();
        for (const rel of rawRelationships) {
            if (!rel || rel.linkTypeID !== linkTypeId) continue;
            const t = rel.target;
            if (t && t.entityType === 'artist' && String(t.gid || '').toLowerCase() === wantGid) {
                return true;
            }
        }
        return false;
    }

    function parseRetryAfterMs(headerVal) {
        if (!headerVal) return null;
        const trimmed = String(headerVal).trim();
        if (/^\d+$/.test(trimmed)) {
            return Math.min(10000, Math.max(500, Number(trimmed) * 1000));
        }
        const dateMs = Date.parse(trimmed);
        if (!Number.isNaN(dateMs)) {
            return Math.min(10000, Math.max(500, dateMs - Date.now()));
        }
        return null;
    }

    async function fetchJsonWithRetry(url, label) {
        let lastErr = null;
        for (let attempt = 0; attempt <= FETCH_MAX_RETRIES; attempt++) {
            const controller = typeof AbortController === 'function' ? new AbortController() : null;
            const timer = controller ? setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS) : null;
            try {
                const resp = await fetch(url, {
                    method: 'GET',
                    credentials: 'same-origin',
                    headers: { Accept: 'application/json' },
                    signal: controller ? controller.signal : undefined,
                });
                if (resp.ok) {
                    return await resp.json();
                }
                const retryable = resp.status === 429 || (resp.status >= 500 && resp.status < 600);
                lastErr = new Error(label + ' 失败 (HTTP ' + resp.status + ')');
                if (!retryable || attempt === FETCH_MAX_RETRIES) {
                    throw lastErr;
                }
                const retryMs = parseRetryAfterMs(resp.headers && resp.headers.get && resp.headers.get('Retry-After'))
                    || (500 * Math.pow(2, attempt));
                await sleep(retryMs);
            } catch (e) {
                lastErr = e && e.name === 'AbortError'
                    ? new Error(label + ' 请求超时')
                    : e;
                if (attempt === FETCH_MAX_RETRIES) {
                    throw lastErr;
                }
                await sleep(400 * Math.pow(2, attempt));
            } finally {
                if (timer) clearTimeout(timer);
            }
        }
        throw lastErr || new Error(label + ' 失败');
    }

    async function fetchArtistEntity(mbid) {
        const key = String(mbid || '').trim().toLowerCase();
        if (!isValidUuid(key)) {
            throw new Error('无效的 Artist MBID: ' + mbid);
        }
        if (artistCache.has(key)) {
            return artistCache.get(key);
        }
        const initialLinked = window.MB && window.MB.linkedEntities && window.MB.linkedEntities.artist;
        if (initialLinked) {
            for (const ent of Object.values(initialLinked)) {
                if (ent && ent.entityType === 'artist' && String(ent.gid || '').toLowerCase() === key) {
                    artistCache.set(key, ent);
                    return ent;
                }
            }
        }
        const data = await fetchJsonWithRetry(
            '/ws/js/entity/' + encodeURIComponent(key),
            '获取 Artist 实体 (' + key + ')'
        );
        if (!data || data.entityType !== 'artist' || !data.id || !data.gid) {
            throw new Error('Artist 实体响应格式无效: ' + key);
        }
        // 响应返回后再从当前 window.MB.linkedEntities.artist 登记，避免持有过期引用
        const liveLinked = window.MB && window.MB.linkedEntities && window.MB.linkedEntities.artist;
        if (liveLinked && !liveLinked[data.id]) {
            liveLinked[data.id] = data;
        }
        artistCache.set(key, data);
        await sleep(120);
        return data;
    }

    async function ensureExistingWorkRelationshipsLoaded(work) {
        if (!work || work._fromBatchCreateWorksDialog === true || !(work.id > 0) || !isValidUuid(work.gid)) {
            return [];
        }
        if (Array.isArray(work.relationships)) {
            return work.relationships;
        }
        const gidKey = work.gid.toLowerCase();
        if (fetchedWorkRelsCache.has(gidKey)) {
            return fetchedWorkRelsCache.get(gidKey);
        }
        try {
            const data = await fetchJsonWithRetry(
                '/ws/js/entity/' + encodeURIComponent(gidKey) + '?inc=rels',
                '加载已有 Work 关系 (' + gidKey + ')'
            );
            const rels = Array.isArray(data && data.relationships) ? data.relationships : [];
            fetchedWorkRelsCache.set(gidKey, rels);
            if (rels.length > 0 && window.MB && window.MB.relationshipEditor && window.MB.relationshipEditor.dispatch) {
                window.MB.relationshipEditor.dispatch({
                    type: 'load-work-relationships',
                    work,
                    relationships: rels,
                });
                await waitForState(() => true, 120);
            }
            return rels;
        } catch (e) {
            // 非致命：回退到当前页面已有状态树查重（上游提交前仍会合并相同关系）
            return [];
        }
    }

    function parsePayloadText(rawText) {
        if (!rawText || !String(rawText).trim()) {
            throw new Error('内容为空');
        }
        let text = String(rawText).trim();
        if (text.startsWith(HASH_PREFIX)) {
            text = decodeBase64UrlUtf8(text.slice(HASH_PREFIX.length));
        }
        const obj = JSON.parse(text);
        if (!obj || obj.v !== 1 || !Array.isArray(obj.tracks)) {
            throw new Error('不是有效的 MusicAutoTagger Works 填充包（需 v=1 且包含 tracks）');
        }
        if (!isValidUuid(obj.releaseId)) {
            throw new Error('填充包缺少合法的 releaseId（需先在 MusicAutoTagger 完成第 2 步关联 Release）');
        }
        return obj;
    }

    function normalizeRoleEntries(list, role) {
        if (!Array.isArray(list)) return [];
        const out = [];
        const seen = new Set();
        for (const item of list) {
            if (!item) continue;
            const mbid = isValidUuid(item.mbid) ? item.mbid.trim().toLowerCase() : null;
            const name = String(item.canonicalName || item.name || item.cleanName || '').trim();
            if (!name && !mbid) continue;
            const dedupKey = role + '|' + (mbid || name.toLowerCase());
            if (seen.has(dedupKey)) continue;
            seen.add(dedupKey);
            // 仅显式标记 needsVerification === false 的条目默认勾选，缺省手工项按需复核处理
            const needsVerification = item.needsVerification !== false;
            out.push({
                role,
                mbid,
                name: name || mbid,
                creditedAs: String(item.creditedAs || item.suggestedCreditedAs || '').trim(),
                needsVerification,
                selected: Boolean(mbid) && !needsVerification,
            });
        }
        return out;
    }

    function buildTrackPlans(payload, previousPlans) {
        const index = getEditorTrackIndex();
        const prevByKey = new Map();
        if (Array.isArray(previousPlans)) {
            for (const old of previousPlans) {
                prevByKey.set(old.planKey, old);
            }
        }

        // 先扫描全部曲目以建立基准曲名索引（支持衍生版排在原版之前或之后）
        const rawTracks = [];
        const baseTrackIdxByTitle = new Map();
        for (let i = 0; i < payload.tracks.length; i++) {
            const tr = payload.tracks[i];
            if (!tr) continue;
            const discNo = Number(tr.discNo) || 1;
            const trackNo = Number(tr.trackNo) || (i + 1);
            const recGid = isValidUuid(tr.recordingId) ? tr.recordingId.trim().toLowerCase() : null;
            // 若包内带有明确 Recording MBID，严禁在查不到时回落到曲序误写其他 Recording
            const matched = recGid
                ? (index.byRecordingGid.get(recGid) || null)
                : (index.byPos.get(discNo + ':' + trackNo) || null);
            const title = String(tr.title || (matched && matched.recordingState.recording.name) || ('Track ' + trackNo));
            const planIdx = rawTracks.length;
            const strippedBase = stripVersionSuffix(title);
            const titleKey = normalizeTitleKey(title);
            if (!strippedBase && !baseTrackIdxByTitle.has(titleKey)) {
                baseTrackIdxByTitle.set(titleKey, planIdx);
            }
            rawTracks.push({ tr, discNo, trackNo, recGid, matched, title, strippedBase, planIdx });
        }
        for (const rt of rawTracks) {
            const titleKey = normalizeTitleKey(rt.title);
            if (!baseTrackIdxByTitle.has(titleKey)) {
                baseTrackIdxByTitle.set(titleKey, rt.planIdx);
            }
        }

        const plans = [];
        for (const rt of rawTracks) {
            const { tr, discNo, trackNo, recGid, matched, title, strippedBase, planIdx } = rt;
            const planKey = recGid || (discNo + ':' + trackNo);
            const prev = prevByKey.get(planKey) || null;

            const composers = normalizeRoleEntries(tr.composers, 'composer');
            const lyricists = normalizeRoleEntries(tr.lyricists, 'lyricist');
            const arrangers = normalizeRoleEntries(tr.arrangers, 'arranger');
            const credits = [...composers, ...lyricists, ...arrangers];
            const hasWorkRole = composers.length > 0 || lyricists.length > 0;
            const activeWorks = matched ? getActiveRelatedWorks(matched.recordingState) : [];
            const existingWorks = activeWorks.map((ws) => ({ id: ws.work.id, name: ws.work.name || ('Work #' + ws.work.id) }));

            let defaultExistingWorkId = null;
            if (existingWorks.length > 0) {
                const wantTitle = normalizeTitleKey(title);
                const exact = existingWorks.find((w) => normalizeTitleKey(w.name) === wantTitle);
                defaultExistingWorkId = (exact || existingWorks[0]).id;
            }

            let reuseIndex = null;
            if (existingWorks.length === 0 && strippedBase) {
                const candidateBaseIdx = baseTrackIdxByTitle.get(normalizeTitleKey(strippedBase));
                if (candidateBaseIdx != null && candidateBaseIdx !== planIdx) {
                    reuseIndex = candidateBaseIdx;
                }
            }

            let workMode = 'SKIP';
            if (defaultExistingWorkId != null) {
                workMode = 'EXISTING:' + defaultExistingWorkId;
            } else if (reuseIndex != null) {
                workMode = 'REUSE:' + reuseIndex;
            } else if (hasWorkRole) {
                workMode = 'CREATE';
            }

            // 如果是默认复用另一轨的同曲 Work，避免重复勾该轨的 Work 级词曲（编曲仍保留勾选）
            if (workMode.startsWith('REUSE:')) {
                for (const c of credits) {
                    if (c.role === 'composer' || c.role === 'lyricist') {
                        c.selected = false;
                    }
                }
            }

            // 重新匹配时尽量保留用户既有勾选与未冲突的 Work 模式选择
            if (prev) {
                const prevCreditSel = new Map();
                for (const pc of prev.credits) {
                    prevCreditSel.set(pc.role + '|' + (pc.mbid || pc.name), pc.selected);
                }
                for (const c of credits) {
                    const ck = c.role + '|' + (c.mbid || c.name);
                    if (prevCreditSel.has(ck) && c.mbid) {
                        c.selected = Boolean(prevCreditSel.get(ck));
                    }
                }
                if (prev.workMode) {
                    if (prev.workMode.startsWith('EXISTING:')) {
                        const prevWorkId = Number(prev.workMode.slice(9));
                        if (existingWorks.some((w) => w.id === prevWorkId)) {
                            workMode = prev.workMode;
                        }
                    } else if (existingWorks.length === 0) {
                        workMode = prev.workMode;
                    }
                }
            }

            const trackLyricsLang = typeof tr.lyricsLanguage === 'string' && tr.lyricsLanguage.trim()
                ? tr.lyricsLanguage.trim().toLowerCase()
                : null;

            plans.push({
                index: planIdx,
                planKey,
                discNo,
                trackNo,
                title,
                recordingId: recGid,
                recordingIdMismatch: Boolean(recGid && !matched),
                lyricsLanguage: trackLyricsLang,
                matched,
                existingWorks,
                workMode,
                credits,
                evidenceUrls: Array.isArray(tr.evidenceUrls) ? tr.evidenceUrls.map(safeHttpUrl).filter(Boolean) : [],
                evidenceQuotes: Array.isArray(tr.evidenceQuotes) ? tr.evidenceQuotes.filter(Boolean).map(String) : [],
            });
        }
        return plans;
    }

    function snapshotPlansForRun(payload, plans, fillEditNote) {
        return {
            releaseId: String(payload.releaseId).trim().toLowerCase(),
            albumTitle: String(payload.albumTitle || ''),
            fillEditNote: Boolean(fillEditNote),
            plans: plans.map((p) => ({
                index: p.index,
                planKey: p.planKey,
                discNo: p.discNo,
                trackNo: p.trackNo,
                title: p.title,
                recordingId: p.recordingId,
                recordingDbId: p.matched ? p.matched.recordingState.recording.id : null,
                lyricsLanguage: p.lyricsLanguage,
                workMode: p.workMode,
                credits: p.credits.map((c) => ({
                    role: c.role,
                    mbid: c.mbid,
                    name: c.name,
                    creditedAs: c.creditedAs,
                    needsVerification: c.needsVerification,
                    selected: Boolean(c.selected),
                })),
                evidenceUrls: p.evidenceUrls.slice(),
                evidenceQuotes: p.evidenceQuotes.slice(),
            })),
        };
    }

    function buildCombinedEditNote(appliedTrackPlans) {
        const urls = new Set();
        const quotes = [];
        const quoteSeen = new Set();
        for (const p of appliedTrackPlans) {
            for (const u of p.evidenceUrls) urls.add(u);
            for (const q of p.evidenceQuotes) {
                const t = q.trim();
                if (t && !quoteSeen.has(t) && quotes.length < 6) {
                    quoteSeen.add(t);
                    quotes.push(t);
                }
            }
        }
        const lines = [];
        if (urls.size > 0) {
            lines.push('Sources:');
            for (const u of urls) {
                lines.push('- ' + u);
            }
        }
        if (quotes.length > 0) {
            if (lines.length) lines.push('');
            lines.push('Excerpts:');
            for (const q of quotes) {
                lines.push('- ' + q);
            }
        }
        if (lines.length) lines.push('');
        lines.push('Credits prepared with MusicAutoTagger and reviewed by editor before submission.');
        return lines.join('\n');
    }

    function assertReleaseMatches(expectedReleaseId) {
        const mb = window.MB;
        const currentReleaseGid = String(
            (mb && mb.relationshipEditor && mb.relationshipEditor.state &&
                mb.relationshipEditor.state.entity && mb.relationshipEditor.state.entity.gid) || ''
        ).toLowerCase();
        if (!isValidUuid(expectedReleaseId) || expectedReleaseId.toLowerCase() !== currentReleaseGid) {
            throw new Error(
                '当前页面 Release MBID (' + (currentReleaseGid || 'unknown') +
                ') 与填充包 (' + expectedReleaseId + ') 不一致，已中止写入。'
            );
        }
    }

    async function applySnapshotToEditor(snapshot, onStatus) {
        if (isApplying) return null;
        isApplying = true;
        try {
            assertReleaseMatches(snapshot.releaseId);

            const mb = window.MB;
            const dispatch = mb.relationshipEditor.dispatch;
            const composerLt = findLinkType('artist', 'work', 'composer');
            const lyricistLt = findLinkType('artist', 'work', 'lyricist');
            const arrangerLt = findLinkType('artist', 'recording', 'arranger');
            const perfLt = findRecordingOfLinkType();
            if (!composerLt || !lyricistLt || !arrangerLt || !perfLt) {
                throw new Error('未能从 MB.linkedEntities 读取完整关系类型，请刷新页面后重试。');
            }

            const plans = snapshot.plans;
            const planByIdx = new Map(plans.map((p) => [p.index, p]));

            // 1. 解析共享 Work 依赖与已勾选创作者
            const neededArtistMbids = new Set();
            let selectedCreditCount = 0;
            const baseHasCheckedWorkCredit = new Set();
            const reuseTargetsWithCheckedWorkCredit = new Set();

            for (const p of plans) {
                if (p.recordingDbId == null) continue;
                let trackHasCheckedWorkCredit = false;
                for (const c of p.credits) {
                    if (c.selected && c.mbid) {
                        neededArtistMbids.add(c.mbid);
                        selectedCreditCount++;
                        if (c.role === 'composer' || c.role === 'lyricist') {
                            trackHasCheckedWorkCredit = true;
                        }
                    }
                }
                if (trackHasCheckedWorkCredit) {
                    if (p.workMode === 'CREATE' || p.workMode.startsWith('EXISTING:')) {
                        baseHasCheckedWorkCredit.add(p.index);
                    } else if (p.workMode.startsWith('REUSE:')) {
                        const targetIdx = Number(p.workMode.slice(6));
                        if (!Number.isNaN(targetIdx)) {
                            reuseTargetsWithCheckedWorkCredit.add(targetIdx);
                        }
                    }
                }
            }

            const hasStandaloneReuseLink = plans.some((p) => {
                if (p.recordingDbId == null || !p.workMode.startsWith('REUSE:')) return false;
                const targetIdx = Number(p.workMode.slice(6));
                const base = planByIdx.get(targetIdx);
                return Boolean(base && base.recordingDbId != null && base.workMode.startsWith('EXISTING:'));
            });
            if (selectedCreditCount === 0 && !hasStandaloneReuseLink) {
                onStatus('当前未勾选任何创作者关系。如曲目为「推测」状态，请核对出处后勾选或点击「全选（含推测）」。', 'warn');
                return { noop: true };
            }

            // 2. 拉取全部所需 Artist 实体（带超时与 429/5xx 退避重试）
            const totalArtists = neededArtistMbids.size;
            let fetchedArtists = 0;
            for (const mbid of neededArtistMbids) {
                fetchedArtists++;
                onStatus('正在加载创作者实体 (' + fetchedArtists + '/' + totalArtists + ')…', 'info');
                await fetchArtistEntity(mbid);
            }

            // 首次写入前再次校验 Release MBID，杜绝异步期间切包或跳页
            assertReleaseMatches(snapshot.releaseId);

            // 3. 按单曲歌词语言分组批量创建待提交 Work（仅在基准轨或其复用轨有已勾选词曲，且基准轨尚无有效 Work 时创建）
            const liveIndexBefore = getEditorTrackIndex();
            const newWorkGroups = new Map(); // langKey -> Array<{ plan, recording }>
            const seenNewWorkRecIds = new Set();
            for (const p of plans) {
                if (p.recordingDbId == null || p.workMode !== 'CREATE') continue;
                if (!baseHasCheckedWorkCredit.has(p.index) && !reuseTargetsWithCheckedWorkCredit.has(p.index)) {
                    continue;
                }
                if (seenNewWorkRecIds.has(p.recordingDbId)) continue;
                const live = liveIndexBefore.byRecordingId.get(p.recordingDbId);
                if (!live) continue;
                if (getActiveRelatedWorks(live.recordingState).length > 0) continue;
                seenNewWorkRecIds.add(p.recordingDbId);
                const langKey = p.lyricsLanguage || '';
                if (!newWorkGroups.has(langKey)) newWorkGroups.set(langKey, []);
                newWorkGroups.get(langKey).push({ plan: p, recording: live.recordingState.recording });
            }

            let createdWorkCount = 0;
            const totalToCreate = Array.from(newWorkGroups.values()).reduce((sum, arr) => sum + arr.length, 0);
            if (totalToCreate > 0) {
                onStatus('正在批量创建 ' + totalToCreate + ' 个待提交 Work…', 'info');
                const songWorkTypeId = findSongWorkTypeId();
                for (const [langKey, entries] of newWorkGroups.entries()) {
                    dispatch({ type: 'toggle-select-all-recordings', isSelected: false });
                    await waitForState((st) => st.selectedRecordings && st.selectedRecordings.size === 0, 2000);

                    const wantedIds = new Set(entries.map((e) => e.recording.id));
                    for (const entry of entries) {
                        dispatch({ type: 'toggle-select-recording', isSelected: true, recording: entry.recording });
                    }
                    await waitForState((st) => st.selectedRecordings && st.selectedRecordings.size >= wantedIds.size, 2000);

                    const langEntity = langKey ? findLanguageEntity(langKey) : null;
                    dispatch({
                        type: 'accept-batch-create-works-dialog',
                        attributes: mb.tree.empty,
                        begin_date: null,
                        end_date: null,
                        ended: false,
                        languages: langEntity ? [langEntity] : [],
                        linkType: perfLt,
                        workType: songWorkTypeId,
                    });

                    // 按实际 React 状态确认每条录音均已挂上新 Work
                    await waitForState((st) => {
                        const idx = getEditorTrackIndex(st);
                        for (const id of wantedIds) {
                            const recEntry = idx.byRecordingId.get(id);
                            if (!recEntry || getActiveRelatedWorks(recEntry.recordingState).length === 0) {
                                return false;
                            }
                        }
                        return true;
                    }, 3000);

                    const afterIdx = getEditorTrackIndex();
                    for (const id of wantedIds) {
                        const recEntry = afterIdx.byRecordingId.get(id);
                        if (recEntry && getActiveRelatedWorks(recEntry.recordingState).length > 0) {
                            createdWorkCount++;
                        }
                    }
                }
                dispatch({ type: 'toggle-select-all-recordings', isSelected: false });
                await waitForState((st) => st.selectedRecordings && st.selectedRecordings.size === 0, 1500);
            }

            // 4. 解析每首曲目绑定的目标 Work，并处理 REUSE 共享 Work
            let liveIndex = getEditorTrackIndex();
            const resolvedWorkByPlanIdx = new Map();
            for (const p of plans) {
                if (p.recordingDbId == null) continue;
                const live = liveIndex.byRecordingId.get(p.recordingDbId);
                if (!live) continue;
                if (p.workMode.startsWith('EXISTING:')) {
                    const wantWorkId = Number(p.workMode.slice(9));
                    const ws = findActiveRelatedWorkById(live.recordingState, wantWorkId)
                        || getActiveRelatedWorks(live.recordingState)[0]
                        || null;
                    if (ws && ws.work) resolvedWorkByPlanIdx.set(p.index, ws.work);
                } else if (p.workMode === 'CREATE') {
                    const activeWorks = getActiveRelatedWorks(live.recordingState);
                    if (activeWorks.length > 0) {
                        resolvedWorkByPlanIdx.set(p.index, activeWorks[0].work);
                    }
                }
            }

            const reusePairsToVerify = [];
            const dispatchedPerfDedup = new Set();
            for (const p of plans) {
                if (p.recordingDbId == null || !p.workMode.startsWith('REUSE:')) continue;
                const targetIdx = Number(p.workMode.slice(6));
                const targetWork = resolvedWorkByPlanIdx.get(targetIdx);
                if (!targetWork) continue;
                const live = liveIndex.byRecordingId.get(p.recordingDbId);
                if (!live) continue;
                resolvedWorkByPlanIdx.set(p.index, targetWork);
                if (findActiveRelatedWorkById(live.recordingState, targetWork.id)) {
                    continue;
                }
                const perfKey = p.recordingDbId + ':' + targetWork.id;
                if (dispatchedPerfDedup.has(perfKey)) {
                    continue;
                }
                dispatchedPerfDedup.add(perfKey);
                const rec = live.recordingState.recording;
                dispatch({
                    type: 'update-relationship-state',
                    sourceEntity: rec,
                    batchSelectionCount: undefined,
                    creditsToChangeForSource: '',
                    creditsToChangeForTarget: '',
                    oldRelationshipState: null,
                    newRelationshipState: {
                        _lineage: ['MusicAutoTagger userscript reuse-work'],
                        _original: null,
                        _status: REL_STATUS_ADD,
                        attributes: mb.tree.empty,
                        begin_date: null,
                        editsPending: false,
                        end_date: null,
                        ended: false,
                        entity0: rec,
                        entity0_credit: '',
                        entity1: targetWork,
                        entity1_credit: '',
                        id: mb.relationshipEditor.getRelationshipStateId(null),
                        linkOrder: 0,
                        linkTypeID: perfLt.id,
                    },
                });
                reusePairsToVerify.push({ recId: p.recordingDbId, workId: targetWork.id });
            }

            let linkedReuseCount = 0;
            if (reusePairsToVerify.length > 0) {
                await waitForState((st) => {
                    const idx = getEditorTrackIndex(st);
                    return reusePairsToVerify.every((pair) => {
                        const recEntry = idx.byRecordingId.get(pair.recId);
                        return Boolean(recEntry && findActiveRelatedWorkById(recEntry.recordingState, pair.workId));
                    });
                }, 2500);
                liveIndex = getEditorTrackIndex();
                for (const pair of reusePairsToVerify) {
                    const recEntry = liveIndex.byRecordingId.get(pair.recId);
                    if (recEntry && findActiveRelatedWorkById(recEntry.recordingState, pair.workId)) {
                        linkedReuseCount++;
                    }
                }
            }

            // 5. 对已有数据库 Work 预先补全其懒加载关系，确保查重准确
            const loadedRawRelsByWorkId = new Map();
            const uniqueWorks = new Map();
            for (const w of resolvedWorkByPlanIdx.values()) {
                if (w && !uniqueWorks.has(w.id)) uniqueWorks.set(w.id, w);
            }
            for (const w of uniqueWorks.values()) {
                if (w.id > 0 && !w._fromBatchCreateWorksDialog && !Array.isArray(w.relationships) && isValidUuid(w.gid)) {
                    onStatus('正在校验已有 Work 的既有关系…', 'info');
                    const rels = await ensureExistingWorkRelationshipsLoaded(w);
                    loadedRawRelsByWorkId.set(w.id, rels);
                }
            }
            liveIndex = getEditorTrackIndex();

            // 6. 逐条写入作曲 / 作词（挂 Work）与编曲（挂 Recording），两类均做轮内与既有去重
            const plannedDispatches = [];
            let skippedExistingCount = 0;
            const appliedPlans = [];
            const dispatchedWorkDedup = new Set();
            const dispatchedRecordingDedup = new Set();

            onStatus('正在写入词曲编关系到编辑器…', 'info');
            for (const p of plans) {
                if (p.recordingDbId == null) continue;
                const live = liveIndex.byRecordingId.get(p.recordingDbId);
                if (!live) continue;
                const recState = live.recordingState;
                const rec = recState.recording;
                const targetWork = resolvedWorkByPlanIdx.get(p.index) || null;
                const targetWorkState = targetWork ? findActiveRelatedWorkById(recState, targetWork.id) : null;
                const rawWorkRels = targetWork ? loadedRawRelsByWorkId.get(targetWork.id) : null;
                let planTouched = false;

                for (const c of p.credits) {
                    if (!c.selected || !c.mbid) continue;
                    const artist = artistCache.get(c.mbid);
                    if (!artist) continue;

                    if (c.role === 'composer' || c.role === 'lyricist') {
                        if (!targetWork) continue;
                        const lt = c.role === 'composer' ? composerLt : lyricistLt;
                        const workDedupKey = targetWork.id + ':' + lt.id + ':' + artist.gid.toLowerCase();
                        if (dispatchedWorkDedup.has(workDedupKey)) {
                            skippedExistingCount++;
                            continue;
                        }
                        if ((targetWorkState && hasExistingArtistRelationship(targetWorkState.targetTypeGroups, lt.id, artist.gid))
                                || hasFetchedRawArtistRelationship(rawWorkRels, lt.id, artist.gid)) {
                            skippedExistingCount++;
                            dispatchedWorkDedup.add(workDedupKey);
                            continue;
                        }
                        dispatch({
                            type: 'update-relationship-state',
                            sourceEntity: targetWork,
                            batchSelectionCount: undefined,
                            creditsToChangeForSource: '',
                            creditsToChangeForTarget: '',
                            oldRelationshipState: null,
                            newRelationshipState: {
                                _lineage: ['MusicAutoTagger userscript ' + c.role],
                                _original: null,
                                _status: REL_STATUS_ADD,
                                attributes: mb.tree.empty,
                                begin_date: null,
                                editsPending: false,
                                end_date: null,
                                ended: false,
                                entity0: artist,
                                entity0_credit: c.creditedAs || '',
                                entity1: targetWork,
                                entity1_credit: '',
                                id: mb.relationshipEditor.getRelationshipStateId(null),
                                linkOrder: 0,
                                linkTypeID: lt.id,
                            },
                        });
                        dispatchedWorkDedup.add(workDedupKey);
                        plannedDispatches.push({ kind: 'work', recId: rec.id, workId: targetWork.id, ltId: lt.id, artistGid: artist.gid });
                        planTouched = true;
                    } else if (c.role === 'arranger') {
                        const recDedupKey = rec.id + ':' + arrangerLt.id + ':' + artist.gid.toLowerCase();
                        if (dispatchedRecordingDedup.has(recDedupKey)) {
                            skippedExistingCount++;
                            continue;
                        }
                        if (hasExistingArtistRelationship(recState.targetTypeGroups, arrangerLt.id, artist.gid)) {
                            skippedExistingCount++;
                            dispatchedRecordingDedup.add(recDedupKey);
                            continue;
                        }
                        dispatch({
                            type: 'update-relationship-state',
                            sourceEntity: rec,
                            batchSelectionCount: undefined,
                            creditsToChangeForSource: '',
                            creditsToChangeForTarget: '',
                            oldRelationshipState: null,
                            newRelationshipState: {
                                _lineage: ['MusicAutoTagger userscript arranger'],
                                _original: null,
                                _status: REL_STATUS_ADD,
                                attributes: mb.tree.empty,
                                begin_date: null,
                                editsPending: false,
                                end_date: null,
                                ended: false,
                                entity0: artist,
                                entity0_credit: c.creditedAs || '',
                                entity1: rec,
                                entity1_credit: '',
                                id: mb.relationshipEditor.getRelationshipStateId(null),
                                linkOrder: 0,
                                linkTypeID: arrangerLt.id,
                            },
                        });
                        dispatchedRecordingDedup.add(recDedupKey);
                        plannedDispatches.push({ kind: 'recording', recId: rec.id, ltId: arrangerLt.id, artistGid: artist.gid });
                        planTouched = true;
                    }
                }
                if (planTouched) {
                    appliedPlans.push(p);
                }
            }

            // 按实际提交后的状态树核实新增关系条数
            let verifiedAddedRelCount = 0;
            if (plannedDispatches.length > 0) {
                await waitForState((st) => {
                    const idx = getEditorTrackIndex(st);
                    return plannedDispatches.every((d) => {
                        const recEntry = idx.byRecordingId.get(d.recId);
                        if (!recEntry) return false;
                        if (d.kind === 'recording') {
                            return hasExistingArtistRelationship(recEntry.recordingState.targetTypeGroups, d.ltId, d.artistGid);
                        }
                        const ws = findActiveRelatedWorkById(recEntry.recordingState, d.workId);
                        return Boolean(ws && hasExistingArtistRelationship(ws.targetTypeGroups, d.ltId, d.artistGid));
                    });
                }, 2500);
                const verifyIdx = getEditorTrackIndex();
                for (const d of plannedDispatches) {
                    const recEntry = verifyIdx.byRecordingId.get(d.recId);
                    if (!recEntry) continue;
                    if (d.kind === 'recording') {
                        if (hasExistingArtistRelationship(recEntry.recordingState.targetTypeGroups, d.ltId, d.artistGid)) {
                            verifiedAddedRelCount++;
                        }
                    } else {
                        const ws = findActiveRelatedWorkById(recEntry.recordingState, d.workId);
                        if (ws && hasExistingArtistRelationship(ws.targetTypeGroups, d.ltId, d.artistGid)) {
                            verifiedAddedRelCount++;
                        }
                    }
                }
            }

            // 7. 写入 Edit Note（保留用户已手写内容）
            if (snapshot.fillEditNote && (verifiedAddedRelCount > 0 || createdWorkCount > 0 || linkedReuseCount > 0)) {
                const generatedNote = buildCombinedEditNote(appliedPlans.length ? appliedPlans : plans);
                const existingNote = String(
                    (mb.relationshipEditor.state.editNoteField && mb.relationshipEditor.state.editNoteField.value) || ''
                ).trim();
                const mergedNote = existingNote
                    ? (existingNote.includes(generatedNote) ? existingNote : (existingNote + '\n\n' + generatedNote))
                    : generatedNote;
                dispatch({ type: 'update-edit-note', editNote: mergedNote });
                await waitForState((st) => st.editNoteField && st.editNoteField.value === mergedNote, 1500);
            }

            const summary = {
                noop: false,
                createdWorkCount,
                linkedReuseCount,
                addedRelCount: verifiedAddedRelCount,
                skippedExistingCount,
            };
            onStatus(
                '预填完成：新建 ' + createdWorkCount + ' 个 Work，复用关联 ' + linkedReuseCount + ' 条，确认新增 ' +
                verifiedAddedRelCount + ' 条词曲编关系，跳过 ' + skippedExistingCount +
                ' 条已有关系。请核对页面绿底变更无误后，手动点击底部 Enter edit 提交！',
                'ok'
            );
            return summary;
        } finally {
            isApplying = false;
        }
    }

    function injectStyles() {
        if (document.getElementById('mat-mb-helper-style')) return;
        const css = `
            #mat-mb-helper {
                margin: 12px 0 16px;
                border: 1px solid #cbd5e1;
                border-radius: 8px;
                background: #f8fafc;
                color: #0f172a;
                font-size: 13px;
                line-height: 1.45;
                box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
            }
            #mat-mb-helper .mat-head {
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 10px;
                flex-wrap: wrap;
                padding: 10px 14px;
                border-bottom: 1px solid #e2e8f0;
                background: #eef2ff;
                border-radius: 8px 8px 0 0;
            }
            #mat-mb-helper .mat-title { font-weight: 700; font-size: 13.5px; color: #1e3a8a; }
            #mat-mb-helper .mat-actions { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
            #mat-mb-helper .mat-btn {
                padding: 4px 10px;
                border-radius: 5px;
                border: 1px solid #cbd5e1;
                background: #ffffff;
                color: #0f172a;
                font-size: 12px;
                font-weight: 600;
                cursor: pointer;
            }
            #mat-mb-helper .mat-btn:hover { background: #f1f5f9; }
            #mat-mb-helper .mat-btn.primary { background: #2563eb; border-color: #2563eb; color: #ffffff; }
            #mat-mb-helper .mat-btn.primary:hover { background: #1d4ed8; }
            #mat-mb-helper .mat-btn:disabled { opacity: 0.55; cursor: not-allowed; }
            #mat-mb-helper .mat-body { padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }
            #mat-mb-helper .mat-status {
                padding: 8px 10px;
                border-radius: 6px;
                font-size: 12.5px;
                border: 1px solid #cbd5e1;
                background: #ffffff;
            }
            #mat-mb-helper .mat-status.ok { background: #ecfdf5; border-color: #a7f3d0; color: #065f46; }
            #mat-mb-helper .mat-status.warn { background: #fffbeb; border-color: #fde68a; color: #92400e; }
            #mat-mb-helper .mat-status.err { background: #fef2f2; border-color: #fecaca; color: #991b1b; }
            #mat-mb-helper .mat-status.info { background: #eff6ff; border-color: #bfdbfe; color: #1e40af; }
            #mat-mb-helper .mat-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
            #mat-mb-helper .mat-track {
                border: 1px solid #e2e8f0;
                border-radius: 6px;
                background: #ffffff;
                padding: 9px 11px;
                display: flex;
                flex-direction: column;
                gap: 6px;
            }
            #mat-mb-helper .mat-track-head {
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 8px;
                flex-wrap: wrap;
            }
            #mat-mb-helper .mat-credits { display: flex; flex-wrap: wrap; gap: 8px; }
            #mat-mb-helper .mat-credit {
                display: inline-flex;
                align-items: center;
                gap: 5px;
                padding: 3px 8px;
                border: 1px solid #e2e8f0;
                border-radius: 999px;
                background: #f8fafc;
                font-size: 12px;
            }
            #mat-mb-helper .mat-credit.disabled { opacity: 0.55; }
            #mat-mb-helper .mat-pill {
                display: inline-block;
                padding: 0 6px;
                border-radius: 999px;
                font-size: 11px;
                font-weight: 600;
                border: 1px solid #cbd5e1;
                background: #f1f5f9;
                color: #334155;
            }
            #mat-mb-helper .mat-pill.ok { background: #dcfce7; border-color: #86efac; color: #166534; }
            #mat-mb-helper .mat-pill.warn { background: #fef3c7; border-color: #fcd34d; color: #92400e; }
            #mat-mb-helper .mat-pill.role { background: #e0e7ff; border-color: #c7d2fe; color: #3730a3; }
            #mat-mb-helper .mat-evidence { font-size: 11.5px; color: #475569; padding-top: 4px; border-top: 1px dashed #e2e8f0; }
            #mat-mb-helper .mat-evidence a { color: #2563eb; margin-right: 10px; word-break: break-all; }
            #mat-mb-helper textarea.mat-paste {
                width: 100%;
                min-height: 76px;
                font-family: monospace;
                font-size: 12px;
                padding: 6px;
                border: 1px solid #cbd5e1;
                border-radius: 6px;
                box-sizing: border-box;
            }
        `;
        document.head.appendChild(el('style', { id: 'mat-mb-helper-style', text: css }));
    }

    function roleDisplay(role) {
        if (role === 'composer') return '作曲 (Work)';
        if (role === 'lyricist') return '作词 (Work)';
        if (role === 'arranger') return '编曲 (Rec)';
        return role;
    }

    function mountPanel(initialPayload, initialError) {
        injectStyles();
        const existing = document.getElementById('mat-mb-helper');
        if (existing) existing.remove();

        const container = el('div', { id: 'mat-mb-helper' });
        const body = el('div', { className: 'mat-body' });
        let collapsed = !initialPayload && !initialError;
        body.style.display = collapsed ? 'none' : 'flex';

        const statusBox = el('div', { className: 'mat-status info', style: { display: 'none' } });
        const pasteWrap = el('div', { style: { display: 'none', flexDirection: 'column', gap: '6px' } });
        const pasteInput = el('textarea', {
            className: 'mat-paste',
            placeholder: '在此粘贴从 MusicAutoTagger 复制的 Works 填充包 JSON… / Paste Works JSON payload…',
        });
        const planRoot = el('div', { style: { display: 'flex', flexDirection: 'column', gap: '8px' } });
        let fillEditNote = true;

        function setStatus(msg, level) {
            if (!msg) {
                statusBox.style.display = 'none';
                statusBox.textContent = '';
                return;
            }
            statusBox.style.display = 'block';
            statusBox.className = 'mat-status ' + (level || 'info');
            statusBox.textContent = msg;
        }

        function setHeaderBusy(busy) {
            importClipboardBtn.disabled = busy;
            togglePasteBtn.disabled = busy;
            parsePasteBtn.disabled = busy;
        }

        function loadPayload(payload) {
            if (isApplying) {
                setStatus('正在写入编辑器，请等待当前操作完成后再更换填充包。', 'warn');
                return;
            }
            currentPayload = payload;
            trackPlans = buildTrackPlans(payload, null);
            collapsed = false;
            body.style.display = 'flex';
            pasteWrap.style.display = 'none';
            toggleBtn.textContent = '收起';
            renderPlans(false);
        }

        function countSelections() {
            let verifiedChecked = 0;
            let probableChecked = 0;
            let probableTotal = 0;
            let verifiedTotal = 0;
            for (const p of trackPlans) {
                if (!p.matched) continue;
                for (const c of p.credits) {
                    if (!c.mbid) continue;
                    if (c.needsVerification) {
                        probableTotal++;
                        if (c.selected) probableChecked++;
                    } else {
                        verifiedTotal++;
                        if (c.selected) verifiedChecked++;
                    }
                }
            }
            return { verifiedChecked, probableChecked, probableTotal, verifiedTotal };
        }

        function renderPlans(preserveStatus) {
            planRoot.textContent = '';
            if (!currentPayload) {
                planRoot.appendChild(el('div', {
                    text: '点击上方「从剪贴板导入 / Import」或在 MusicAutoTagger「回馈 MB」页点击「一键预填词曲编（油猴）」即可载入。',
                }));
                return;
            }

            const currentReleaseGid = String(
                (window.MB && window.MB.relationshipEditor && window.MB.relationshipEditor.state &&
                    window.MB.relationshipEditor.state.entity && window.MB.relationshipEditor.state.entity.gid) || ''
            ).toLowerCase();
            const releaseMatches = isValidUuid(currentPayload.releaseId)
                && currentPayload.releaseId.toLowerCase() === currentReleaseGid;

            if (!preserveStatus) {
                if (!releaseMatches) {
                    setStatus(
                        'Release MBID 不匹配：填充包属于 ' + currentPayload.releaseId +
                        '，当前页面为 ' + currentReleaseGid + '。已阻止写入以防误操作。',
                        'err'
                    );
                } else {
                    const counts = countSelections();
                    const hint = '已载入「' + (currentPayload.albumTitle || currentReleaseGid) + '」：' +
                        '已勾选 ' + (counts.verifiedChecked + counts.probableChecked) + ' 条关系（已核验 ' +
                        counts.verifiedChecked + '/' + counts.verifiedTotal + '，推测 ' +
                        counts.probableChecked + '/' + counts.probableTotal + '）。';
                    setStatus(hint, counts.probableTotal > 0 && counts.probableChecked === 0 ? 'warn' : 'info');
                }
            }

            const toolbar = el('div', { className: 'mat-toolbar' }, [
                el('button', {
                    type: 'button',
                    className: 'mat-btn',
                    disabled: isApplying,
                    text: '仅选已核验',
                    onClick: () => {
                        for (const p of trackPlans) {
                            const isReuse = p.workMode.startsWith('REUSE:');
                            for (const c of p.credits) {
                                c.selected = Boolean(c.mbid) && !c.needsVerification
                                    && !(isReuse && (c.role === 'composer' || c.role === 'lyricist'));
                            }
                        }
                        renderPlans(false);
                    },
                }),
                el('button', {
                    type: 'button',
                    className: 'mat-btn',
                    disabled: isApplying,
                    text: '全选（含推测，请复核出处）',
                    onClick: () => {
                        for (const p of trackPlans) {
                            const isReuse = p.workMode.startsWith('REUSE:');
                            for (const c of p.credits) {
                                c.selected = Boolean(c.mbid)
                                    && !(isReuse && (c.role === 'composer' || c.role === 'lyricist'));
                            }
                        }
                        renderPlans(false);
                    },
                }),
                el('button', {
                    type: 'button',
                    className: 'mat-btn',
                    disabled: isApplying,
                    text: '全部取消',
                    onClick: () => {
                        for (const p of trackPlans) {
                            for (const c of p.credits) c.selected = false;
                        }
                        renderPlans(false);
                    },
                }),
                el('button', {
                    type: 'button',
                    className: 'mat-btn',
                    disabled: isApplying,
                    text: '重新匹配曲目',
                    onClick: () => {
                        trackPlans = buildTrackPlans(currentPayload, trackPlans);
                        renderPlans(false);
                    },
                }),
                el('label', { style: { display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px' } }, [
                    el('input', {
                        type: 'checkbox',
                        checked: fillEditNote,
                        disabled: isApplying,
                        onChange: (ev) => { fillEditNote = Boolean(ev.target.checked); },
                    }),
                    '同步写入 Edit Note（附出处）',
                ]),
                el('button', {
                    type: 'button',
                    className: 'mat-btn primary',
                    disabled: !releaseMatches || isApplying,
                    text: '写入关系编辑器（不自动提交）',
                    onClick: async () => {
                        const snapshot = snapshotPlansForRun(currentPayload, trackPlans, fillEditNote);
                        setHeaderBusy(true);
                        renderPlans(true);
                        try {
                            const result = await applySnapshotToEditor(snapshot, setStatus);
                            if (result && !result.noop) {
                                trackPlans = buildTrackPlans(currentPayload, trackPlans);
                            }
                        } catch (e) {
                            setStatus('写入失败：' + (e && e.message ? e.message : String(e)), 'err');
                        } finally {
                            setHeaderBusy(false);
                            renderPlans(true);
                        }
                    },
                }),
            ]);
            planRoot.appendChild(toolbar);

            for (const p of trackPlans) {
                const workSelect = el('select', {
                    style: { fontSize: '12px', padding: '2px 6px', borderRadius: '4px', border: '1px solid #cbd5e1' },
                    disabled: !p.matched || isApplying,
                    onChange: (ev) => {
                        p.workMode = ev.target.value;
                        if (p.workMode.startsWith('REUSE:')) {
                            for (const c of p.credits) {
                                if (c.role === 'composer' || c.role === 'lyricist') c.selected = false;
                            }
                        }
                        renderPlans(false);
                    },
                });
                if (p.existingWorks.length > 0) {
                    for (const ew of p.existingWorks) {
                        workSelect.appendChild(el('option', {
                            value: 'EXISTING:' + ew.id,
                            text: '使用已有 Work: ' + ew.name,
                        }));
                    }
                    workSelect.appendChild(el('option', { value: 'SKIP', text: '跳过 Work（仅编曲）' }));
                } else {
                    workSelect.appendChild(el('option', { value: 'CREATE', text: '新建 Work (Song)' + (p.lyricsLanguage ? ' [' + p.lyricsLanguage + ']' : '') }));
                    for (const other of trackPlans) {
                        if (other.index !== p.index && other.matched && other.workMode !== 'SKIP' && !other.workMode.startsWith('REUSE:')) {
                            workSelect.appendChild(el('option', {
                                value: 'REUSE:' + other.index,
                                text: '复用 ' + other.discNo + '-' + other.trackNo + ' 的 Work',
                            }));
                        }
                    }
                    workSelect.appendChild(el('option', { value: 'SKIP', text: '跳过 Work（仅编曲）' }));
                }
                workSelect.value = p.workMode;

                const creditNodes = [];
                for (const c of p.credits) {
                    const chk = el('input', {
                        type: 'checkbox',
                        checked: c.selected,
                        disabled: !p.matched || !c.mbid || isApplying,
                        onChange: (ev) => {
                            c.selected = Boolean(ev.target.checked);
                            const counts = countSelections();
                            setStatus(
                                '已勾选 ' + (counts.verifiedChecked + counts.probableChecked) + ' 条关系（已核验 ' +
                                counts.verifiedChecked + '/' + counts.verifiedTotal + '，推测 ' +
                                counts.probableChecked + '/' + counts.probableTotal + '）。',
                                'info'
                            );
                        },
                    });
                    const children = [
                        chk,
                        el('span', { className: 'mat-pill role', text: roleDisplay(c.role) }),
                    ];
                    if (c.mbid) {
                        children.push(el('a', {
                            href: 'https://musicbrainz.org/artist/' + encodeURIComponent(c.mbid),
                            target: '_blank',
                            rel: 'noopener noreferrer',
                            text: c.name,
                            style: { fontWeight: '600', color: '#0f172a' },
                        }));
                    } else {
                        children.push(el('span', { text: c.name }));
                    }
                    if (c.creditedAs) {
                        children.push(el('span', { className: 'mat-pill', text: 'as: ' + c.creditedAs }));
                    }
                    if (!c.mbid) {
                        children.push(el('span', { className: 'mat-pill warn', text: '无 MBID' }));
                    } else if (c.needsVerification) {
                        children.push(el('span', { className: 'mat-pill warn', text: '推测' }));
                    } else {
                        children.push(el('span', { className: 'mat-pill ok', text: '已核验' }));
                    }
                    creditNodes.push(el('label', { className: 'mat-credit' + ((!p.matched || !c.mbid) ? ' disabled' : '') }, children));
                }

                const matchBadge = p.matched
                    ? el('span', { className: 'mat-pill ok', text: '已匹配轨' })
                    : el('span', {
                        className: 'mat-pill warn',
                        text: p.recordingIdMismatch
                            ? 'Recording MBID 未命中当前已展开音轨（如是多碟请先展开盘面后点「重新匹配曲目」）'
                            : '未匹配当前页音轨',
                    });

                const trackCardChildren = [
                    el('div', { className: 'mat-track-head' }, [
                        el('div', {}, [
                            el('strong', { text: p.discNo + '-' + p.trackNo + ' ' + p.title }),
                            ' ',
                            matchBadge,
                        ]),
                        workSelect,
                    ]),
                    creditNodes.length
                        ? el('div', { className: 'mat-credits' }, creditNodes)
                        : el('div', { style: { fontSize: '12px', color: '#64748b' }, text: '本轨无可用词曲编数据' }),
                ];

                if (p.evidenceUrls.length || p.evidenceQuotes.length) {
                    const evChildren = [];
                    if (p.evidenceQuotes.length) {
                        evChildren.push(el('div', { text: '“' + p.evidenceQuotes[0] + '”' }));
                    }
                    if (p.evidenceUrls.length) {
                        const linksWrap = el('div', {}, [el('span', { text: '出处：' })]);
                        for (const u of p.evidenceUrls.slice(0, 3)) {
                            linksWrap.appendChild(el('a', {
                                href: u,
                                target: '_blank',
                                rel: 'noopener noreferrer',
                                text: u,
                            }));
                        }
                        evChildren.push(linksWrap);
                    }
                    trackCardChildren.push(el('div', { className: 'mat-evidence' }, evChildren));
                }

                planRoot.appendChild(el('div', { className: 'mat-track' }, trackCardChildren));
            }
        }

        const parsePasteBtn = el('button', {
            type: 'button',
            className: 'mat-btn primary',
            text: '解析并预览',
            onClick: () => {
                try {
                    loadPayload(parsePayloadText(pasteInput.value));
                } catch (e) {
                    setStatus('解析失败：' + (e && e.message ? e.message : String(e)), 'err');
                }
            },
        });
        const pasteActions = el('div', { className: 'mat-actions' }, [
            parsePasteBtn,
            el('button', {
                type: 'button',
                className: 'mat-btn',
                text: '取消',
                onClick: () => { pasteWrap.style.display = 'none'; },
            }),
        ]);
        pasteWrap.appendChild(pasteInput);
        pasteWrap.appendChild(pasteActions);

        const toggleBtn = el('button', {
            type: 'button',
            className: 'mat-btn',
            text: collapsed ? '展开' : '收起',
            onClick: () => {
                collapsed = !collapsed;
                body.style.display = collapsed ? 'none' : 'flex';
                toggleBtn.textContent = collapsed ? '展开' : '收起';
            },
        });

        const importClipboardBtn = el('button', {
            type: 'button',
            className: 'mat-btn',
            text: '从剪贴板导入 / Import',
            onClick: async () => {
                if (isApplying) return;
                collapsed = false;
                body.style.display = 'flex';
                toggleBtn.textContent = '收起';
                try {
                    if (!navigator.clipboard || typeof navigator.clipboard.readText !== 'function') {
                        throw new Error('浏览器不支持直接读剪贴板，请使用「粘贴 JSON」');
                    }
                    const txt = await navigator.clipboard.readText();
                    loadPayload(parsePayloadText(txt));
                } catch (e) {
                    pasteWrap.style.display = 'flex';
                    setStatus('读取剪贴板失败（可直接粘贴到下方输入框）：' + (e && e.message ? e.message : String(e)), 'warn');
                }
            },
        });

        const togglePasteBtn = el('button', {
            type: 'button',
            className: 'mat-btn',
            text: '粘贴 JSON / Paste JSON',
            onClick: () => {
                if (isApplying) return;
                collapsed = false;
                body.style.display = 'flex';
                toggleBtn.textContent = '收起';
                pasteWrap.style.display = pasteWrap.style.display === 'none' ? 'flex' : 'none';
            },
        });

        const head = el('div', { className: 'mat-head' }, [
            el('span', { className: 'mat-title', text: 'MusicAutoTagger · 词曲编预填助手' }),
            el('div', { className: 'mat-actions' }, [importClipboardBtn, togglePasteBtn, toggleBtn]),
        ]);

        body.appendChild(statusBox);
        body.appendChild(pasteWrap);
        body.appendChild(planRoot);
        container.appendChild(head);
        container.appendChild(body);

        const anchor = document.querySelector('#tracklist')
            || document.querySelector('.release-relationship-editor')
            || document.querySelector('#content');
        if (anchor && anchor.parentNode) {
            anchor.parentNode.insertBefore(container, anchor);
        } else {
            document.body.prepend(container);
        }

        if (initialPayload) {
            loadPayload(initialPayload);
        } else if (initialError) {
            setStatus(initialError, 'err');
            renderPlans(true);
        } else {
            renderPlans(false);
        }
    }

    function mountTimeoutNotice(hashPayload, hashError) {
        injectStyles();
        const notice = el('div', { id: 'mat-mb-helper' }, [
            el('div', { className: 'mat-head' }, [
                el('span', { className: 'mat-title', text: 'MusicAutoTagger · 词曲编预填助手（等待编辑器超时）' }),
                el('div', { className: 'mat-actions' }, [
                    el('button', {
                        type: 'button',
                        className: 'mat-btn primary',
                        text: '重试连接编辑器',
                        onClick: async () => {
                            const ok = await waitForEditor(5000);
                            if (ok) {
                                notice.remove();
                                mountPanel(hashPayload, hashError);
                            }
                        },
                    }),
                ]),
            ]),
        ]);
        const target = document.querySelector('#content') || document.body;
        target.prepend(notice);
    }

    async function bootstrap() {
        let hashPayload = null;
        let hashError = null;
        let sawMatHash = false;
        if (window.location.hash && window.location.hash.startsWith(HASH_PREFIX)) {
            sawMatHash = true;
            const rawHash = window.location.hash;
            try {
                hashPayload = parsePayloadText(rawHash);
            } catch (e) {
                hashError = 'URL 预填包解析失败：' + (e && e.message ? e.message : String(e));
            }
            try {
                window.history.replaceState(
                    window.history.state,
                    '',
                    window.location.pathname + window.location.search
                );
            } catch (e) { /* ignore */ }
        }

        const ready = await waitForEditor(15000);
        if (!ready) {
            if (sawMatHash) {
                mountTimeoutNotice(hashPayload, hashError);
            }
            return;
        }
        mountPanel(hashPayload, hashError);
    }

    if (typeof globalThis !== 'undefined' && globalThis.__MAT_ENABLE_TEST_HOOKS__ === true) {
        globalThis.__MAT_WORKS_HELPER_TEST__ = {
            parsePayloadText,
            stripVersionSuffix,
            buildTrackPlans,
            snapshotPlansForRun,
            applySnapshotToEditor,
            fetchArtistEntity,
            clearCaches: () => {
                artistCache.clear();
                fetchedWorkRelsCache.clear();
            },
        };
    } else {
        bootstrap();
    }
})();
