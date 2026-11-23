/**
 * 子页面（待确认 / 重新识别 / 处理记录）共用的侧栏行为。
 * 主面板 index.html 有自己的登录态管理与计数刷新，不加载本文件。
 *
 * - 窄屏菜单展开 / 收起
 * - 「待确认」积压计数：与主面板同一规则 —— 功能关闭且队列为空时隐藏入口，
 *   关闭但有遗留条目时仍显示，否则那些条目就没地方处置了。
 */
(function (global) {
    let stopped = false;
    let pollTimer = null;

    function toggleNav() {
        const side = document.getElementById('appSide');
        if (side) side.classList.toggle('open');
    }

    function handleUnauthorized() {
        if (stopped) return;
        stopped = true;
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
        if (global.SearchQueue && typeof global.SearchQueue.stop === 'function') {
            global.SearchQueue.stop();
        }
        location.href = 'index.html';
    }

    async function refreshReviewCount() {
        if (stopped) return;
        const link = document.getElementById('navReview');
        const badge = document.getElementById('reviewCount');
        if (!link || !badge) return;
        try {
            const resp = await fetch('/api/review/stats', { credentials: 'same-origin' });
            if (resp.status === 401) { handleUnauthorized(); return; }
            if (!resp.ok) throw new Error('review stats unavailable');
            const data = await resp.json();
            const pending = Number(data.pending) || 0;
            const visible = data.enabled !== false || pending > 0;
            // 当前就在待确认页时始终显示入口，否则侧栏里找不到自己在哪
            link.style.display = (visible || link.classList.contains('active')) ? '' : 'none';
            badge.textContent = pending > 99 ? '99+' : String(pending);
            badge.classList.toggle('show', pending > 0);
        } catch (e) {
            // 统计接口不可用时保留入口（当前页面可能就是待确认页），只隐藏计数
            badge.classList.remove('show');
        }
    }

    // 「重新识别」积压：部分识别 + 识别失败；有失败项时用红色（走轻量 summary 接口避免全量扫描）
    async function refreshRecoveryCount() {
        if (stopped) return;
        const badge = document.getElementById('recoveryCount');
        if (!badge) return;
        try {
            const resp = await fetch('/api/recovery/summary', { credentials: 'same-origin' });
            if (resp.status === 401) { handleUnauthorized(); return; }
            if (!resp.ok) throw new Error('recovery summary unavailable');
            const data = await resp.json();
            const failed = Number(data.failed) || 0;
            const partial = Number(data.partial) || 0;
            const total = Number(data.total != null ? data.total : (partial + failed)) || 0;
            badge.textContent = total > 99 ? '99+' : String(total);
            badge.classList.toggle('show', total > 0);
            badge.classList.toggle('is-bad', failed > 0);
        } catch (e) {
            badge.classList.remove('show');
        }
    }

    // 「缺少封面」积压计数
    async function refreshCoversCount() {
        if (stopped) return;
        const badge = document.getElementById('coversCount');
        if (!badge) return;
        try {
            const resp = await fetch('/api/covers/stats', { credentials: 'same-origin' });
            if (resp.status === 401) { handleUnauthorized(); return; }
            if (!resp.ok) throw new Error('covers stats unavailable');
            const data = await resp.json();
            const pending = Number(data.pending) || 0;
            badge.textContent = pending > 99 ? '99+' : String(pending);
            badge.classList.toggle('show', pending > 0);
        } catch (e) {
            badge.classList.remove('show');
        }
    }

    function refreshCounts() {
        if (stopped || document.hidden) return;
        refreshReviewCount();
        refreshCoversCount();
        refreshRecoveryCount();
    }

    document.addEventListener('DOMContentLoaded', () => {
        refreshCounts();
        pollTimer = setInterval(() => {
            if (!document.hidden && !stopped) refreshCounts();
        }, 30000);
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden && !stopped) refreshCounts();
        });
    });

    global.Shell = { toggleNav, refreshReviewCount, refreshCoversCount, refreshRecoveryCount, refreshCounts };
})(window);
