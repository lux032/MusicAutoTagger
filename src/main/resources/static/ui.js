/**
 * 前端共享 UI 工具模块：统一 5 字符 HTML 转义（防 XSS）、Toast 反馈、
 * 字节/时长/语言区域格式化与高风险按钮防连点锁。
 */
(function (global) {
    const inFlightActions = new Set();

    function escapeHtml(text) {
        if (text == null) return '';
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function toast(msg, opts = {}) {
        const elementId = opts.elementId || 'toast';
        const duration = Number(opts.duration) > 0 ? Number(opts.duration) : 4000;
        const el = document.getElementById(elementId);
        if (!el) return;
        el.textContent = msg == null ? '' : String(msg);
        el.style.background = opts.isError ? 'var(--danger)' : '';
        el.style.display = 'block';
        clearTimeout(el._appToastTimer);
        el._appToastTimer = setTimeout(() => { el.style.display = 'none'; }, duration);
    }

    function formatBytes(value, mbDecimals = 1) {
        const v = Number(value) || 0;
        if (v < 1024) return v + ' B';
        if (v < 1024 * 1024) return (v / 1024).toFixed(mbDecimals > 1 ? 1 : 0) + ' KB';
        return (v / 1024 / 1024).toFixed(mbDecimals) + ' MB';
    }

    function formatDuration(seconds, zeroFallback = null) {
        const s = Number(seconds) || 0;
        if (zeroFallback !== null && s <= 0) return zeroFallback;
        const m = Math.floor(s / 60);
        const r = Math.round(s % 60);
        return m + ':' + String(r).padStart(2, '0');
    }

    function locale(langOverride) {
        const lang = langOverride || (global.I18n && global.I18n.language) || 'en_US';
        return lang === 'zh_CN' ? 'zh-CN' : 'en-US';
    }

    async function withButtonLock(lockKey, btnOrId, fn) {
        if (inFlightActions.has(lockKey)) return;
        inFlightActions.add(lockKey);
        const btn = typeof btnOrId === 'string' ? document.getElementById(btnOrId) : btnOrId;
        if (btn) btn.disabled = true;
        try {
            return await fn();
        } finally {
            inFlightActions.delete(lockKey);
            if (btn) btn.disabled = false;
        }
    }

    global.AppUI = { escapeHtml, toast, formatBytes, formatDuration, locale, withButtonLock };
})(window);
