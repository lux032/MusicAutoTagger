/**
 * 统一 LLM 联网搜索队列跨页模块（全局状态条 + 侧栏入口 + 右侧队列抽屉 + 自适应轮询）。
 * 所有页面（概览 / 待确认 / 重新识别 / 处理记录）均可引入并共享实时队列状态。
 */
(function (global) {
    const SEEN_KEY = 'mat.sq.seenFinished';
    const ACTIVE_POLL_MS = 1500;
    const IDLE_POLL_MS = 15000;

    const LIST_ICON = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>';
    const OK_ICON = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--success)" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>';
    const ERR_ICON = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--danger)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16h.01"/></svg>';

    let initialized = false;
    let pollTimer = null;
    let tickTimer = null;
    let getCsrfFn = () => null;
    let onNavigateReviewFn = null;
    let onToastFn = null;
    let snapshot = null;
    let snapshotReceivedAt = 0;
    let drawerOpen = false;
    let highlightTaskId = null;
    let seenFinished = loadSeen();

    const t = (k, d) => (global.I18n ? global.I18n.t(k, d) : (d || k));
    const tf = (k, ...a) => (global.I18n ? global.I18n.tf(k, ...a) : k);

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function loadSeen() {
        try {
            const raw = sessionStorage.getItem(SEEN_KEY);
            const arr = raw ? JSON.parse(raw) : [];
            return new Set(Array.isArray(arr) ? arr : []);
        } catch (e) {
            return new Set();
        }
    }

    function saveSeen() {
        try {
            const list = [...seenFinished].slice(-120);
            sessionStorage.setItem(SEEN_KEY, JSON.stringify(list));
        } catch (e) { /* ignore */ }
    }

    function isUnread(taskId) {
        return !!taskId && !seenFinished.has(taskId);
    }

    function markRead(taskId) {
        if (!taskId || seenFinished.has(taskId)) return;
        seenFinished.add(taskId);
        saveSeen();
        renderChrome();
    }

    function notify(msg) {
        if (onToastFn) { onToastFn(msg); return; }
        const el = document.getElementById('toast');
        if (!el) return;
        el.textContent = msg;
        el.style.display = 'block';
        clearTimeout(el._sqTimer);
        el._sqTimer = setTimeout(() => { el.style.display = 'none'; }, 3600);
    }

    async function request(path, opt = {}) {
        opt.headers = opt.headers || {};
        const csrf = getCsrfFn ? getCsrfFn() : null;
        if (csrf && opt.method && opt.method.toUpperCase() !== 'GET') {
            opt.headers['X-CSRF-Token'] = csrf;
        }
        if (opt.body) opt.headers['Content-Type'] = 'application/json';
        const resp = await fetch(path, { credentials: 'same-origin', ...opt });
        let data = {};
        try { data = await resp.json(); } catch (e) { /* ignore */ }
        if (!resp.ok) {
            const err = new Error(data.error || ('HTTP ' + resp.status));
            err.status = resp.status;
            err.data = data;
            throw err;
        }
        return data;
    }

    function elapsedSeconds(task) {
        if (!task) return 0;
        let ms = Number(task.elapsedMs) || 0;
        if (task.status === 'RUNNING' && snapshotReceivedAt > 0) {
            ms += Math.max(0, Date.now() - snapshotReceivedAt);
        }
        return Math.max(0, Math.round(ms / 1000));
    }

    function stageText(task) {
        if (!task) return '';
        const code = task.stageCode || 'QUEUED';
        const cur = Number(task.stageCurrent) || 0;
        const tot = Number(task.stageTotal) || 0;
        const cit = Number(task.citationsCount) || 0;
        const files = Number(task.fileCount) || 0;
        switch (code) {
            case 'COLLECTING': return tf('sq.stage.COLLECTING', files || tot || 0);
            case 'ROUND1_QUERIES': return t('sq.stage.ROUND1_QUERIES');
            case 'ROUND1_WEB_SEARCH': return tf('sq.stage.ROUND1_WEB_SEARCH', cur, tot || 1, cit);
            case 'ROUND1_SUMMARIZING': return tf('sq.stage.ROUND1_SUMMARIZING', cit);
            case 'ROUND1_NATIVE_SEARCH': return tf('sq.stage.ROUND1_NATIVE_SEARCH', cur || 1, tot || 1);
            case 'ROUND1_NATIVE_RETRY': return tf('sq.stage.ROUND1_NATIVE_RETRY', cur || 1, cit || 1);
            case 'ROUND1_MATCHING': return tf('sq.stage.ROUND1_MATCHING', files || tot || 0);
            case 'ROUND1_SAVED': return t('sq.stage.ROUND1_SAVED');
            case 'ROUND2_QUERIES': return t('sq.stage.ROUND2_QUERIES');
            case 'ROUND2_WEB_SEARCH': return tf('sq.stage.ROUND2_WEB_SEARCH', cur, tot || 1, cit);
            case 'ROUND2_SUMMARIZING': return t('sq.stage.ROUND2_SUMMARIZING');
            case 'ROUND2_NATIVE_SEARCH': return t('sq.stage.ROUND2_NATIVE_SEARCH');
            case 'ROUND2_NATIVE_RETRY': return tf('sq.stage.ROUND2_NATIVE_RETRY', cur || 1, cit || 1);
            case 'ROUND2_MATCHING': return t('sq.stage.ROUND2_MATCHING');
            default: return t('sq.stage.QUEUED');
        }
    }

    function shortStage(task) {
        if (!task) return '';
        const step = Number(task.stepIndex) || 0;
        if (step <= 0) return t('sq.short.collecting');
        if (step === 1) {
            if (task.stageCode === 'ROUND1_WEB_SEARCH' && task.stageTotal > 0) {
                return `${t('sq.short.r1')} ${task.stageCurrent}/${task.stageTotal}`;
            }
            return t('sq.short.r1');
        }
        if (step === 2) return t('sq.short.matching');
        if (task.stageCode === 'ROUND2_WEB_SEARCH' && task.stageTotal > 0) {
            return `${t('sq.short.r2')} ${task.stageCurrent}/${task.stageTotal}`;
        }
        return t('sq.short.r2');
    }

    function resultSummary(task) {
        if (!task) return '';
        const sec = elapsedSeconds(task);
        if (task.status === 'SUCCEEDED') {
            if ((task.candidateCount || 0) > 0) {
                const cov = Math.round((Number(task.bestCoverage) || 0) * 100) + '%';
                return tf('sq.result.found', task.candidateCount, cov, sec);
            }
            return tf('sq.result.clues', task.clueCount || 0, sec);
        }
        if (task.status === 'CANCELLED') {
            return task.round1Saved ? t('sq.result.cancelled.kept') : t('sq.result.cancelled');
        }
        return task.errorMessage || t('sq.status.failed');
    }

    function stepTrackHtml(task) {
        const step = Number(task?.stepIndex) || 0;
        const inRound2 = Number(task?.round) >= 2 || step >= 3;
        return '<div class="sq-step-track">' + [0, 1, 2, 3].map(idx => {
            if (idx === 3 && !inRound2) return '<span class="sq-step-seg opt"></span>';
            if (step > idx) return '<span class="sq-step-seg done"></span>';
            if (step === idx) return '<span class="sq-step-seg active"></span>';
            return '<span class="sq-step-seg"></span>';
        }).join('') + '</div>';
    }

    function originLabel(task) {
        return t('sq.origin.' + (task.origin || 'REVIEW_MANUAL'), task.origin || '');
    }

    function providerLabel(provider) {
        return provider === 'tavily' ? t('sq.provider.tavily') : t('sq.provider.native');
    }

    // ==================== DOM 挂载 ====================
    function ensureChrome() {
        const main = document.querySelector('main.main');
        if (main && !document.getElementById('sqGlobalBar')) {
            const bar = document.createElement('div');
            bar.id = 'sqGlobalBar';
            bar.className = 'global-task-bar';
            bar.hidden = true;
            main.insertBefore(bar, main.firstChild);
        }

        const side = document.getElementById('appSide') || document.querySelector('aside.side');
        if (side && !document.getElementById('sqSideBtn')) {
            let foot = side.querySelector('.side-foot');
            if (!foot) {
                foot = document.createElement('div');
                foot.className = 'side-foot';
                side.appendChild(foot);
            }
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.id = 'sqSideBtn';
            btn.className = 'side-queue-btn';
            btn.innerHTML = `<span class="sq-icon" id="sqSideIcon">${LIST_ICON}</span>`
                + `<span data-i18n="sq.nav.title">${esc(t('sq.nav.title'))}</span>`
                + `<span class="q-pill" id="sqSidePill">${esc(t('sq.nav.idle'))}</span>`;
            btn.addEventListener('click', () => openDrawer());
            foot.insertBefore(btn, foot.firstChild);
        }

        if (!document.getElementById('sqDrawer')) {
            const mask = document.createElement('div');
            mask.id = 'sqDrawerMask';
            mask.className = 'sq-drawer-mask';
            mask.addEventListener('click', closeDrawer);

            const drawer = document.createElement('aside');
            drawer.id = 'sqDrawer';
            drawer.className = 'sq-drawer';
            drawer.setAttribute('aria-label', t('sq.drawer.title'));
            drawer.innerHTML = `
                <div class="sq-drawer-head">
                    <div>
                        <div class="sq-drawer-title" id="sqDrawerTitle">${esc(t('sq.drawer.title'))}</div>
                        <div class="sq-drawer-sub" id="sqDrawerSub"></div>
                    </div>
                    <button type="button" class="btn plain sm" style="margin-left:auto" id="sqDrawerClose">${esc(t('sq.drawer.close'))}</button>
                </div>
                <div class="sq-drawer-body" id="sqDrawerBody"></div>`;
            document.body.append(mask, drawer);
            document.getElementById('sqDrawerClose').addEventListener('click', closeDrawer);
            drawer.addEventListener('click', handleActionClick);
            if (document.getElementById('sqGlobalBar')) {
                document.getElementById('sqGlobalBar').addEventListener('click', handleActionClick);
            }
            document.addEventListener('keydown', e => {
                if (e.key === 'Escape' && drawerOpen) closeDrawer();
            });
        }
    }

    function handleActionClick(e) {
        const btn = e.target.closest('[data-sq-act]');
        if (!btn || btn.disabled) return;
        const act = btn.dataset.sqAct;
        const taskId = btn.dataset.taskId;
        const reviewId = btn.dataset.reviewId;
        if (act === 'open-drawer') { openDrawer(taskId || null); return; }
        if (act === 'goto-review' && reviewId) { navigateReview(reviewId, taskId); return; }
        if (act === 'prioritize' && taskId) { prioritize(taskId); return; }
        if (act === 'cancel' && taskId) { cancel(taskId); return; }
        if (act === 'retry' && taskId) { retry(taskId); return; }
        if (act === 'clear-queued') { clearQueued(); return; }
        if (act === 'clear-finished') { clearFinished(); return; }
    }

    function navigateReview(reviewItemId, taskId) {
        if (taskId) markRead(taskId);
        closeDrawer();
        if (onNavigateReviewFn) {
            onNavigateReviewFn(reviewItemId, taskId);
        } else {
            location.href = 'review.html#item=' + encodeURIComponent(reviewItemId);
        }
    }

    // ==================== 渲染 ====================
    function renderChrome() {
        ensureChrome();
        if (!snapshot) return;
        const running = snapshot.running || [];
        const queued = snapshot.queued || [];
        const finished = snapshot.finished || [];
        const unreadDone = finished.filter(x => x.status === 'SUCCEEDED' && isUnread(x.id));
        const failed = finished.filter(x => x.status === 'FAILED');

        // 侧栏按钮
        const sideBtn = document.getElementById('sqSideBtn');
        const sideIcon = document.getElementById('sqSideIcon');
        const sidePill = document.getElementById('sqSidePill');
        if (sideBtn && sideIcon && sidePill) {
            const active = running.length > 0 || queued.length > 0;
            sideBtn.classList.toggle('is-active', active);
            sideIcon.innerHTML = active ? '<span class="sq-spinner"></span>' : LIST_ICON;
            if (running.length > 0 && queued.length > 0) {
                sidePill.textContent = tf('sq.nav.active.both', running.length, queued.length);
            } else if (running.length > 0) {
                sidePill.textContent = tf('sq.nav.active.run', running.length);
            } else if (queued.length > 0) {
                sidePill.textContent = tf('sq.bar.chip.wait', queued.length);
            } else if (finished.length > 0) {
                sidePill.textContent = tf('sq.nav.recent', finished.length);
            } else {
                sidePill.textContent = t('sq.nav.idle');
            }
        }

        // 全局顶部轻量条（空闲自动收起）
        const bar = document.getElementById('sqGlobalBar');
        if (bar) {
            const otherActive = (snapshot.recoveryOtherJobs || []).filter(j => j.status === 'RUNNING' || j.status === 'QUEUED');
            if (!running.length && !queued.length && !unreadDone.length && !failed.length && !otherActive.length) {
                bar.hidden = true;
            } else {
                bar.hidden = false;
                let cls = 'global-task-bar';
                let leadHtml = '';
                if (running.length > 0) {
                    const first = running[0];
                    const headline = running.length > 1
                        ? tf('sq.bar.running.more', first.folderName || '', running.length)
                        : tf('sq.bar.running', first.folderName || '');
                    leadHtml = `<span class="sq-spinner"></span>
                        <div class="gtb-text">
                            <div class="gtb-headline">${esc(headline)}</div>
                            <div class="gtb-sub">
                                <span>${esc(stageText(first))}</span>
                                <span>· <span data-sq-elapsed="${esc(first.id)}">${esc(tf('sq.elapsed.used', elapsedSeconds(first)))}</span></span>
                                <span class="gtb-progress-mini"><i style="width:${Math.min(100, Math.max(4, Number(first.percent) || 0))}%"></i></span>
                            </div>
                        </div>`;
                } else if (otherActive.length > 0) {
                    leadHtml = `<span class="sq-spinner"></span>
                        <div class="gtb-text">
                            <div class="gtb-headline">${esc(otherActive[0].relativePath || '')}</div>
                            <div class="gtb-sub"><span>${esc(t('recovery.job.processing'))}</span></div>
                        </div>`;
                } else if (failed.length > 0 && !unreadDone.length) {
                    cls += ' is-fail-only';
                    leadHtml = `${ERR_ICON}
                        <div class="gtb-text">
                            <div class="gtb-headline">${esc(tf('sq.bar.failed', failed.length))}</div>
                            <div class="gtb-sub">${esc(failed[0].folderName || '')} — ${esc(failed[0].errorMessage || '')}</div>
                        </div>`;
                } else {
                    cls += ' is-done-only';
                    const latest = unreadDone[0];
                    leadHtml = `${OK_ICON}
                        <div class="gtb-text">
                            <div class="gtb-headline">${esc(tf('sq.bar.done', unreadDone.length))}</div>
                            <div class="gtb-sub">${esc(tf('sq.bar.done.sub', latest.folderName || '', resultSummary(latest)))}</div>
                        </div>`;
                }

                const maxC = snapshot.maxConcurrency || 1;
                const chips = [
                    running.length ? `<span class="sq-pill run">${esc(tf('sq.bar.chip.run', running.length, maxC))}</span>` : '',
                    queued.length ? `<span class="sq-pill wait">${esc(tf('sq.bar.chip.wait', queued.length))}</span>` : '',
                    unreadDone.length ? `<span class="sq-pill ok">${esc(tf('sq.bar.chip.ok', unreadDone.length))}</span>` : '',
                    failed.length ? `<span class="sq-pill fail">${esc(tf('sq.bar.chip.fail', failed.length))}</span>` : ''
                ].join('');
                const quickBtn = !running.length && unreadDone.length && unreadDone[0].reviewItemId
                    ? `<button type="button" class="btn soft sm" data-sq-act="goto-review" data-review-id="${esc(unreadDone[0].reviewItemId)}" data-task-id="${esc(unreadDone[0].id)}">${esc(t('sq.bar.goto.latest'))}</button>` : '';

                bar.className = cls;
                bar.innerHTML = `<div class="gtb-lead">${leadHtml}</div>
                    <div class="gtb-metrics">${chips}</div>
                    ${quickBtn}
                    <button type="button" class="btn ghost sm" data-sq-act="open-drawer">${esc(t('sq.bar.expand'))}</button>`;
            }
        }

        if (drawerOpen) renderDrawer();
    }

    function renderDrawer() {
        const sub = document.getElementById('sqDrawerSub');
        const body = document.getElementById('sqDrawerBody');
        if (!sub || !body || !snapshot) return;

        const maxC = snapshot.maxConcurrency || 1;
        sub.textContent = tf('sq.drawer.sub', maxC, providerLabel(snapshot.provider));

        const running = snapshot.running || [];
        const queued = snapshot.queued || [];
        const finished = snapshot.finished || [];
        const otherJobs = (snapshot.recoveryOtherJobs || []).slice(0, 10);

        const runHtml = running.length ? running.map(task => {
            const sec = elapsedSeconds(task);
            return `<div class="sq-task run ${highlightTaskId === task.id ? 'highlight' : ''}">
                <div class="sq-task-r1">
                    <span class="sq-spinner" style="margin-top:3px"></span>
                    <div class="sq-task-name">${esc(task.folderName || '')}</div>
                    <span class="sq-pill run" data-sq-elapsed-short="${esc(task.id)}">${esc(tf('sq.elapsed', sec))}</span>
                </div>
                <div class="sq-task-meta">
                    <span class="sq-pill">${esc(originLabel(task))}</span>
                    <span>${esc(tf('sq.files.count', task.fileCount || 0))}</span>
                    <span>· ${esc(providerLabel(task.engine))}</span>
                </div>
                <div class="sq-task-stage">
                    <strong>${esc(stageText(task))}</strong>
                    <span>${Math.min(100, Math.max(0, Number(task.percent) || 0))}%</span>
                </div>
                ${stepTrackHtml(task)}
                <div class="sq-task-foot">
                    ${task.reviewItemId ? `<button type="button" class="btn ghost xs" data-sq-act="goto-review" data-review-id="${esc(task.reviewItemId)}" data-task-id="${esc(task.id)}">${esc(t('sq.act.view.item'))}</button>` : ''}
                    <button type="button" class="btn danger-soft xs" data-sq-act="cancel" data-task-id="${esc(task.id)}" ${task.cancelRequested ? 'disabled' : ''}>${esc(task.cancelRequested ? t('sq.act.aborting') : t('sq.act.abort'))}</button>
                </div>
            </div>`;
        }).join('') : `<div class="sq-empty">${esc(t('sq.drawer.empty.running'))}</div>`;

        const waitHtml = queued.length ? queued.map((task, idx) => {
            const pos = task.queuePosition || (idx + 1);
            const isManual = Number(task.priority) === 1;
            return `<div class="sq-task ${highlightTaskId === task.id ? 'highlight' : ''}">
                <div class="sq-task-r1">
                    <span class="sq-task-pos">#${pos}</span>
                    <div class="sq-task-name">${esc(task.folderName || '')}</div>
                    <span class="sq-pill ${isManual ? 'info' : ''}">${esc(t(isManual ? 'sq.priority.manual' : 'sq.priority.auto'))}</span>
                </div>
                <div class="sq-task-meta">
                    <span>${esc(originLabel(task))}</span>
                    <span>· ${esc(tf('sq.files.count', task.fileCount || 0))}</span>
                </div>
                <div class="sq-task-foot">
                    ${pos > 1 || !isManual ? `<button type="button" class="btn ghost xs" data-sq-act="prioritize" data-task-id="${esc(task.id)}">${esc(t('sq.act.prioritize'))}</button>` : ''}
                    <button type="button" class="btn danger-soft xs" data-sq-act="cancel" data-task-id="${esc(task.id)}">${esc(t('sq.act.remove'))}</button>
                </div>
            </div>`;
        }).join('') : `<div class="sq-empty">${esc(t('sq.drawer.empty.queued'))}</div>`;

        const doneHtml = finished.length ? finished.map(task => {
            const isOk = task.status === 'SUCCEEDED';
            const isFail = task.status === 'FAILED';
            const pillCls = isOk ? 'ok' : (isFail ? 'fail' : 'wait');
            const statusKey = 'sq.status.' + String(task.status || '').toLowerCase();
            return `<div class="sq-task ${isFail ? 'fail' : ''}">
                <div class="sq-task-r1">
                    <div class="sq-task-name">${esc(task.folderName || '')}</div>
                    <span class="sq-pill ${pillCls}">${esc(t(statusKey, task.status))}</span>
                </div>
                <div class="sq-task-meta">${esc(resultSummary(task))}</div>
                <div class="sq-task-foot">
                    ${(isOk || (task.status === 'CANCELLED' && task.round1Saved)) && task.reviewItemId
                        ? `<button type="button" class="btn soft xs" data-sq-act="goto-review" data-review-id="${esc(task.reviewItemId)}" data-task-id="${esc(task.id)}">${esc(t('sq.act.goto.review'))}</button>` : ''}
                    ${isFail || task.status === 'CANCELLED'
                        ? `<button type="button" class="btn soft xs" data-sq-act="retry" data-task-id="${esc(task.id)}">${esc(t('sq.act.retry'))}</button>` : ''}
                </div>
            </div>`;
        }).join('') : `<div class="sq-empty">${esc(t('sq.drawer.empty.finished'))}</div>`;

        const otherHtml = otherJobs.length ? `<div>
            <div class="sq-sec-head"><span class="sq-sec-title">${esc(t('sq.drawer.sec.other'))} <span class="sq-sec-cnt">${otherJobs.length}</span></span></div>
            ${otherJobs.map(j => {
                const st = j.status || '';
                const pillCls = st === 'RUNNING' ? 'run' : (st === 'QUEUED' ? 'wait' : (st === 'SUCCEEDED' ? 'ok' : (st === 'FAILED' ? 'fail' : 'info')));
                return `<div class="sq-task">
                    <div class="sq-task-r1">
                        <div class="sq-task-name">${esc(j.relativePath || '')}</div>
                        <span class="sq-pill ${pillCls}">${esc(t('recovery.status.' + (st === 'WAITING_REVIEW' ? 'waiting' : st.toLowerCase()), st))}</span>
                    </div>
                    <div class="sq-task-meta">${esc(j.message || t('recovery.job.processing'))}</div>
                    ${j.reviewItemId ? `<div class="sq-task-foot"><button type="button" class="btn soft xs" data-sq-act="goto-review" data-review-id="${esc(j.reviewItemId)}">${esc(t('sq.act.goto.review'))}</button></div>` : ''}
                </div>`;
            }).join('')}
        </div>` : '';

        body.innerHTML = `
            <div>
                <div class="sq-sec-head"><span class="sq-sec-title">${esc(t('sq.drawer.sec.running'))} <span class="sq-sec-cnt">${running.length} / ${maxC}</span></span></div>
                ${runHtml}
            </div>
            <div>
                <div class="sq-sec-head">
                    <span class="sq-sec-title">${esc(t('sq.drawer.sec.queued'))} <span class="sq-sec-cnt">${queued.length}</span></span>
                    ${queued.length > 0 ? `<button type="button" class="btn plain xs" data-sq-act="clear-queued">${esc(t('sq.drawer.clear.queued'))}</button>` : ''}
                </div>
                ${waitHtml}
            </div>
            <div>
                <div class="sq-sec-head">
                    <span class="sq-sec-title">${esc(t('sq.drawer.sec.finished'))} <span class="sq-sec-cnt">${finished.length}</span></span>
                    ${finished.length > 0 ? `<button type="button" class="btn plain xs" data-sq-act="clear-finished">${esc(t('sq.drawer.clear.finished'))}</button>` : ''}
                </div>
                ${doneHtml}
            </div>
            ${otherHtml}`;
    }

    function openDrawer(taskId = null) {
        ensureChrome();
        drawerOpen = true;
        highlightTaskId = taskId;
        document.getElementById('sqDrawerMask')?.classList.add('open');
        document.getElementById('sqDrawer')?.classList.add('open');
        renderDrawer();
        refresh();
    }

    function closeDrawer() {
        drawerOpen = false;
        highlightTaskId = null;
        document.getElementById('sqDrawerMask')?.classList.remove('open');
        document.getElementById('sqDrawer')?.classList.remove('open');
    }

    // ==================== 自适应轮询与本地秒表 ====================
    function schedulePoll() {
        clearTimeout(pollTimer);
        if (document.hidden) return;
        const counts = snapshot?.counts || {};
        const otherActive = (snapshot?.recoveryOtherJobs || []).some(j => j.status === 'RUNNING' || j.status === 'QUEUED');
        const active = (counts.totalActive || 0) > 0 || otherActive;
        pollTimer = setTimeout(() => refresh(), active ? ACTIVE_POLL_MS : IDLE_POLL_MS);
    }

    async function refresh() {
        clearTimeout(pollTimer);
        try {
            const prevFinishedIds = new Set((snapshot?.finished || []).map(x => x.id));
            const next = await request('/api/search-queue/status');
            snapshot = next;
            snapshotReceivedAt = Date.now();
            renderChrome();
            const newlyFinished = (next.finished || []).filter(x => !prevFinishedIds.has(x.id));
            global.dispatchEvent(new CustomEvent('mat:search-queue', {
                detail: { snapshot: next, newlyFinished }
            }));
            if (newlyFinished.length && global.Shell) {
                global.Shell.refreshCounts();
            }
        } catch (e) {
            // 静默重试
        } finally {
            schedulePoll();
        }
    }

    function tickElapsed() {
        if (!snapshot || !(snapshot.running || []).length) return;
        for (const task of snapshot.running) {
            const sec = elapsedSeconds(task);
            document.querySelectorAll(`[data-sq-elapsed="${CSS.escape(task.id)}"]`).forEach(el => {
                el.textContent = tf('sq.elapsed.used', sec);
            });
            document.querySelectorAll(`[data-sq-elapsed-short="${CSS.escape(task.id)}"]`).forEach(el => {
                el.textContent = tf('sq.elapsed', sec);
            });
        }
    }

    // ==================== 查询辅助 ====================
    function allTasks() {
        if (!snapshot) return [];
        return [...(snapshot.running || []), ...(snapshot.queued || []), ...(snapshot.finished || [])];
    }

    function findForReview(reviewItemId) {
        if (!reviewItemId || !snapshot) return null;
        for (const t of (snapshot.running || [])) if (t.reviewItemId === reviewItemId) return t;
        for (const t of (snapshot.queued || [])) if (t.reviewItemId === reviewItemId) return t;
        for (const t of (snapshot.finished || [])) if (t.reviewItemId === reviewItemId) return t;
        return null;
    }

    function findForRecovery(sourceType, relativePath) {
        if (!sourceType || !relativePath || !snapshot) return null;
        const match = t => t.sourceType === sourceType && t.relativePath === relativePath;
        for (const t of (snapshot.running || [])) if (match(t)) return t;
        for (const t of (snapshot.queued || [])) if (match(t)) return t;
        for (const t of (snapshot.finished || [])) if (match(t)) return t;
        return null;
    }

    // ==================== 写操作 ====================
    async function enqueueReview(ids, analyzeCover = false) {
        const list = Array.isArray(ids) ? ids : [ids];
        const res = await request('/api/search-queue/enqueue-review', {
            method: 'POST',
            body: JSON.stringify({ ids: list, analyzeCover })
        });
        await refresh();
        return res;
    }

    async function enqueueRecovery(items, analyzeCover = false) {
        const list = Array.isArray(items) ? items : [items];
        const res = await request('/api/search-queue/enqueue-recovery', {
            method: 'POST',
            body: JSON.stringify({ items: list, analyzeCover })
        });
        await refresh();
        return res;
    }

    async function prioritize(taskId) {
        try {
            await request('/api/search-queue/prioritize', { method: 'POST', body: JSON.stringify({ taskId }) });
            notify(t('sq.toast.prioritized'));
            await refresh();
        } catch (e) { notify(e.message); }
    }

    async function cancel(taskId) {
        try {
            const wasRunning = (snapshot?.running || []).some(x => x.id === taskId);
            await request('/api/search-queue/cancel', { method: 'POST', body: JSON.stringify({ taskId }) });
            notify(t(wasRunning ? 'sq.toast.aborting' : 'sq.toast.removed'));
            await refresh();
        } catch (e) { notify(e.message); }
    }

    async function retry(taskId) {
        try {
            await request('/api/search-queue/retry', { method: 'POST', body: JSON.stringify({ taskId }) });
            notify(t('sq.toast.retried'));
            await refresh();
        } catch (e) { notify(e.message); }
    }

    async function clearQueued() {
        try {
            const res = await request('/api/search-queue/clear-queued', { method: 'POST', body: JSON.stringify({}) });
            notify(tf('sq.toast.cleared.queued', res.cleared || 0));
            await refresh();
        } catch (e) { notify(e.message); }
    }

    async function clearFinished() {
        try {
            await request('/api/search-queue/clear-finished', { method: 'POST', body: JSON.stringify({}) });
            await refresh();
        } catch (e) { notify(e.message); }
    }

    function init(options = {}) {
        if (options.getCsrf) getCsrfFn = options.getCsrf;
        else if (options.csrfToken) { const tok = options.csrfToken; getCsrfFn = () => tok; }
        if (options.onNavigateReview) onNavigateReviewFn = options.onNavigateReview;
        if (options.onToast) onToastFn = options.onToast;
        ensureChrome();
        if (!initialized) {
            initialized = true;
            document.addEventListener('visibilitychange', () => {
                if (!document.hidden) refresh();
                else clearTimeout(pollTimer);
            });
            tickTimer = setInterval(tickElapsed, 1000);
        }
        refresh();
    }

    global.SearchQueue = {
        init,
        refresh,
        openDrawer,
        closeDrawer,
        getSnapshot: () => snapshot,
        allTasks,
        findForReview,
        findForRecovery,
        isUnread,
        markRead,
        elapsedSeconds,
        stageText,
        shortStage,
        resultSummary,
        stepTrackHtml,
        originLabel,
        providerLabel,
        enqueueReview,
        enqueueRecovery,
        prioritize,
        cancel,
        retry,
        clearQueued,
        clearFinished
    };
})(window);
