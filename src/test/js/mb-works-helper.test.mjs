import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const SCRIPT_PATH = path.resolve('src/main/resources/static/mb-works-helper.user.js');
const SCRIPT_SOURCE = fs.readFileSync(SCRIPT_PATH, 'utf8');

function createMockTree() {
    return {
        empty: [],
        *iterate(t) {
            if (!t) return;
            for (const item of t) yield item;
        },
    };
}

function makeTargetTypeGroups(rels) {
    if (!rels || !rels.length) return [];
    return [['any', [{ phraseGroups: [{ relationships: rels }] }]]];
}

function createHarness({ releaseGid, tracks, existingWorksByRecId = {}, fetchImpl }) {
    let nextNegId = -1;
    const dispatches = [];
    const fetchCalls = [];
    const tree = createMockTree();

    const recStates = tracks.map((tr) => {
        const existing = existingWorksByRecId[tr.id] || [];
        const perfRels = existing.map((w) => ({
            id: nextNegId--,
            _status: w._removed ? 3 : 0,
            linkTypeID: 278,
            entity0: tr.recording,
            entity1: w.work,
        }));
        const workStates = existing.map((w) => ({
            isSelected: false,
            work: w.work,
            targetTypeGroups: makeTargetTypeGroups(w.artistRels || []),
        }));
        return {
            isSelected: false,
            recording: tr.recording,
            relatedWorks: workStates,
            targetTypeGroups: makeTargetTypeGroups(perfRels),
        };
    });

    const medium = {
        position: 1,
        tracks: tracks.map((tr, idx) => ({ position: tr.position || idx + 1, recording: tr.recording })),
    };

    const state = {
        entity: { entityType: 'release', gid: releaseGid, name: 'VAMPY', mediums: [medium] },
        mediums: [[medium, recStates]],
        selectedRecordings: new Set(),
        editNoteField: { value: '' },
    };

    function findRecState(recId) {
        return recStates.find((rs) => rs.recording.id === recId) || null;
    }

    function appendRel(targetGroups, rel) {
        if (!targetGroups.length) {
            targetGroups.push(['any', [{ phraseGroups: [{ relationships: [rel] }] }]]);
        } else {
            targetGroups[0][1][0].phraseGroups[0].relationships.push(rel);
        }
    }

    const linkedEntities = {
        artist: {},
        work: {},
        link_type: {
            168: { id: 168, type0: 'artist', type1: 'work', name: 'composer' },
            165: { id: 165, type0: 'artist', type1: 'work', name: 'lyricist' },
            297: { id: 297, type0: 'artist', type1: 'recording', name: 'arranger' },
            278: { id: 278, type0: 'recording', type1: 'work', name: 'performance' },
            'a3005666-a872-32c3-ad06-98af558e99b0': { id: 278, type0: 'recording', type1: 'work', name: 'performance' },
        },
        work_type: {
            17: { id: 17, name: 'Song' },
        },
        language: {
            198: { id: 198, iso_code_3: 'jpn', name: 'Japanese' },
        },
    };

    function dispatch(action) {
        dispatches.push(action);
        if (action.type === 'toggle-select-all-recordings') {
            if (!action.isSelected) state.selectedRecordings = new Set();
            else state.selectedRecordings = new Set(recStates.map((r) => r.recording));
            return;
        }
        if (action.type === 'toggle-select-recording') {
            const next = new Set(state.selectedRecordings);
            if (action.isSelected) next.add(action.recording);
            else next.delete(action.recording);
            state.selectedRecordings = next;
            return;
        }
        if (action.type === 'accept-batch-create-works-dialog') {
            for (const rec of state.selectedRecordings) {
                const rs = findRecState(rec.id);
                if (!rs) continue;
                const activeWorkCount = rs.relatedWorks.length;
                if (activeWorkCount > 0) continue;
                const newWork = {
                    entityType: 'work',
                    id: nextNegId--,
                    name: rec.name,
                    typeID: action.workType,
                    languages: (action.languages || []).map((l) => ({ language: l })),
                    _fromBatchCreateWorksDialog: true,
                };
                linkedEntities.work[newWork.id] = newWork;
                const perfRel = {
                    id: nextNegId--,
                    _status: 1,
                    linkTypeID: 278,
                    entity0: rec,
                    entity1: newWork,
                };
                appendRel(rs.targetTypeGroups, perfRel);
                rs.relatedWorks.push({
                    isSelected: false,
                    work: newWork,
                    targetTypeGroups: [],
                });
            }
            return;
        }
        if (action.type === 'load-work-relationships') {
            for (const rs of recStates) {
                for (const ws of rs.relatedWorks) {
                    if (ws.work.id === action.work.id) {
                        for (const rel of action.relationships) {
                            appendRel(ws.targetTypeGroups, {
                                id: rel.id || nextNegId--,
                                _status: 0,
                                linkTypeID: rel.linkTypeID,
                                entity0: rel.target,
                                entity1: ws.work,
                            });
                        }
                    }
                }
            }
            return;
        }
        if (action.type === 'update-relationship-state') {
            const rel = action.newRelationshipState;
            assert.ok(rel.entity0.entityType <= rel.entity1.entityType, 'entity0 <= entity1 invariant');
            if (rel.entity0.entityType === 'recording' && rel.entity1.entityType === 'work') {
                const rs = findRecState(rel.entity0.id);
                if (rs) {
                    appendRel(rs.targetTypeGroups, rel);
                    if (!rs.relatedWorks.some((w) => w.work.id === rel.entity1.id)) {
                        // Reuse same targetTypeGroups array across shared Work references
                        let sharedGroups = [];
                        for (const other of recStates) {
                            const found = other.relatedWorks.find((w) => w.work.id === rel.entity1.id);
                            if (found) {
                                sharedGroups = found.targetTypeGroups;
                                break;
                            }
                        }
                        rs.relatedWorks.push({ isSelected: false, work: rel.entity1, targetTypeGroups: sharedGroups });
                    }
                }
            } else if (rel.entity0.entityType === 'artist' && rel.entity1.entityType === 'work') {
                for (const rs of recStates) {
                    for (const ws of rs.relatedWorks) {
                        if (ws.work.id === rel.entity1.id) {
                            appendRel(ws.targetTypeGroups, rel);
                        }
                    }
                }
            } else if (rel.entity0.entityType === 'artist' && rel.entity1.entityType === 'recording') {
                const rs = findRecState(rel.entity1.id);
                if (rs) appendRel(rs.targetTypeGroups, rel);
            }
            return;
        }
        if (action.type === 'update-edit-note') {
            state.editNoteField = { value: action.editNote };
        }
    }

    const sandbox = {
        __MAT_ENABLE_TEST_HOOKS__: true,
        URL,
        TextDecoder,
        atob: (s) => Buffer.from(s, 'base64').toString('binary'),
        setTimeout,
        clearTimeout,
        AbortController,
        fetch: async (url, opts) => {
            fetchCalls.push({ url, method: (opts && opts.method) || 'GET' });
            assert.equal((opts && opts.method) || 'GET', 'GET', 'userscript must NEVER send POST requests');
            return fetchImpl(url, opts);
        },
        MB: {
            constants: { RECORDING_OF_LINK_TYPE_GID: 'a3005666-a872-32c3-ad06-98af558e99b0' },
            tree,
            linkedEntities,
            relationshipEditor: {
                state,
                dispatch,
                getRelationshipStateId: () => nextNegId--,
            },
        },
        window: null,
        document: {
            getElementById: () => null,
            querySelector: () => null,
        },
    };
    sandbox.window = sandbox;
    sandbox.globalThis = sandbox;
    vm.runInNewContext(SCRIPT_SOURCE, sandbox);
    return {
        api: sandbox.__MAT_WORKS_HELPER_TEST__,
        state,
        dispatches,
        fetchCalls,
        linkedEntities,
    };
}

const REL_ID = '11111111-1111-4111-8111-111111111111';
const OTHER_REL_ID = '22222222-2222-4222-8222-222222222222';
const REC1_ID = 'aaaa1111-1111-4111-8111-111111111111';
const REC2_ID = 'aaaa2222-2222-4222-8222-222222222222';
const REC3_ID = 'aaaa3333-3333-4333-8333-333333333333';
const ART1_ID = 'bbbb1111-1111-4111-8111-111111111111';
const ART2_ID = 'bbbb2222-2222-4222-8222-222222222222';

test('rejects missing or mismatched releaseId with zero dispatches', async () => {
    const h = createHarness({
        releaseGid: REL_ID,
        tracks: [{ id: 101, recording: { entityType: 'recording', id: 101, gid: REC1_ID, name: 'VAMPY' } }],
        fetchImpl: async () => { throw new Error('should not fetch'); },
    });

    assert.throws(() => h.api.parsePayloadText(JSON.stringify({ v: 1, tracks: [] })), /releaseId/);
    assert.throws(() => h.api.parsePayloadText(JSON.stringify({ v: 1, releaseId: null, tracks: [] })), /releaseId/);

    const payload = h.api.parsePayloadText(JSON.stringify({
        v: 1,
        releaseId: OTHER_REL_ID,
        tracks: [{ discNo: 1, trackNo: 1, title: 'VAMPY', recordingId: REC1_ID, composers: [{ mbid: ART1_ID, name: 'A', needsVerification: false }] }],
    }));
    const plans = h.api.buildTrackPlans(payload);
    const snap = h.api.snapshotPlansForRun(payload, plans, true);
    await assert.rejects(() => h.api.applySnapshotToEditor(snap, () => {}), /不一致/);
    assert.equal(h.dispatches.length, 0);
    assert.equal(h.fetchCalls.length, 0);
});

test('does not fall back to track position when recordingId mismatches and never misclassifies (Midnight)', () => {
    const h = createHarness({
        releaseGid: REL_ID,
        tracks: [{ id: 101, recording: { entityType: 'recording', id: 101, gid: REC1_ID, name: 'Midnight' } }],
        fetchImpl: async () => { throw new Error('unused'); },
    });

    assert.equal(h.api.stripVersionSuffix('Song (Midnight)'), null);
    assert.equal(h.api.stripVersionSuffix('VAMPY (Latin Night Version)'), 'VAMPY');

    const payload = h.api.parsePayloadText(JSON.stringify({
        v: 1,
        releaseId: REL_ID,
        tracks: [{
            discNo: 1,
            trackNo: 1,
            title: 'Other',
            recordingId: REC2_ID, // does not match REC1_ID on track 1!
            composers: [{ mbid: ART1_ID, name: 'Artist 1' }], // omitted needsVerification -> default true
        }],
    }));
    const plans = h.api.buildTrackPlans(payload);
    assert.equal(plans[0].matched, null, 'must NOT fall back to track position when recordingId mismatches');
    assert.equal(plans[0].recordingIdMismatch, true);
    assert.equal(plans[0].credits[0].needsVerification, true, 'missing needsVerification must default to true');
    assert.equal(plans[0].credits[0].selected, false);
});

test('creates base Work when only REUSE track has checked composer, handles 429 Retry-After, and batches per-track lyricsLanguage', async () => {
    let art1Attempts = 0;
    const h = createHarness({
        releaseGid: REL_ID,
        tracks: [
            { id: 101, position: 1, recording: { entityType: 'recording', id: 101, gid: REC1_ID, name: 'VAMPY' } },
            { id: 102, position: 2, recording: { entityType: 'recording', id: 102, gid: REC2_ID, name: 'VAMPY (Latin Night Version)' } },
            { id: 103, position: 3, recording: { entityType: 'recording', id: 103, gid: REC3_ID, name: 'きみキミ血ぅ毒' } },
        ],
        fetchImpl: async (url) => {
            if (url.includes(ART1_ID)) {
                art1Attempts++;
                if (art1Attempts === 1) {
                    return { ok: false, status: 429, headers: new Map([['Retry-After', '0']]) };
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({ entityType: 'artist', id: 5001, gid: ART1_ID, name: 'カワイヒデヒロ' }),
                };
            }
            if (url.includes(ART2_ID)) {
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({ entityType: 'artist', id: 5002, gid: ART2_ID, name: 'tepe' }),
                };
            }
            throw new Error('unexpected url: ' + url);
        },
    });

    const payload = h.api.parsePayloadText(JSON.stringify({
        v: 1,
        releaseId: REL_ID,
        albumTitle: 'VAMPY',
        tracks: [
            {
                discNo: 1,
                trackNo: 1,
                title: 'VAMPY',
                recordingId: REC1_ID,
                lyricsLanguage: null, // instrumental or unknown
                composers: [{ mbid: ART1_ID, name: 'カワイヒデヒロ', needsVerification: true }],
            },
            {
                discNo: 1,
                trackNo: 2,
                title: 'VAMPY (Latin Night Version)',
                recordingId: REC2_ID,
                composers: [{ mbid: ART1_ID, name: 'カワイヒデヒロ', needsVerification: true }],
            },
            {
                discNo: 1,
                trackNo: 3,
                title: 'きみキミ血ぅ毒',
                recordingId: REC3_ID,
                lyricsLanguage: 'jpn',
                composers: [{ mbid: ART2_ID, name: 'tepe', needsVerification: false }],
                arrangers: [{ mbid: ART2_ID, name: 'tepe', needsVerification: false }],
                evidenceUrls: ['https://prtimes.jp/main/html/rd/p/000000700.000044525.html'],
            },
        ],
    }));

    const plans = h.api.buildTrackPlans(payload);
    assert.equal(plans[0].workMode, 'CREATE');
    assert.equal(plans[1].workMode, 'REUSE:0');
    // Uncheck base track 1, manually check composer ONLY on reuse track 2!
    plans[0].credits[0].selected = false;
    plans[1].credits[0].selected = true;

    const snap = h.api.snapshotPlansForRun(payload, plans, true);
    const res = await h.api.applySnapshotToEditor(snap, () => {});

    assert.equal(art1Attempts, 2, '429 response should trigger retry');
    assert.ok(h.linkedEntities.artist[5001], 'artist must be registered in live linkedEntities.artist');
    assert.equal(res.createdWorkCount, 2, 'both VAMPY (for reuse track) and track 3 should create Works');
    assert.equal(res.linkedReuseCount, 1, 'Latin Night Version should link to VAMPY Work');
    assert.equal(res.addedRelCount, 3, 'composer on shared VAMPY + composer & arranger on track 3');

    // Verify per-track lyricsLanguage batching: track 1 has [] languages, track 3 has [jpn]
    const batchCreateActions = h.dispatches.filter((d) => d.type === 'accept-batch-create-works-dialog');
    assert.equal(batchCreateActions.length, 2, 'tracks with distinct lyricsLanguage must batch separately');
    const langCounts = batchCreateActions.map((a) => a.languages.length).sort();
    assert.deepEqual(langCounts, [0, 1]);
    assert.ok(h.state.editNoteField.value.includes('https://prtimes.jp/main/html/rd/p/000000700.000044525.html'));
});
