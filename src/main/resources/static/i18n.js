/**
 * 子页面共用的国际化工具。
 * 主面板 index.html 有自己的一套实现（历史原因），这里给 review.html / recovery.html 用。
 */
(function (global) {
    let messages = {};
    let language = 'en_US';

    /** 取翻译文本，缺 key 时回落到页面上的原文（一般是中文），不会直接暴露 key */
    function t(key, fallback) {
        const value = messages[key];
        if (value !== undefined && value !== null && value !== '') return value;
        return fallback !== undefined ? fallback : key;
    }

    /** 带 {0} {1} 占位符的翻译 */
    function tf(key, ...args) {
        return String(t(key)).replace(/\{(\d+)\}/g, (m, i) => {
            const v = args[Number(i)];
            return v === undefined || v === null ? '' : String(v);
        });
    }

    async function load() {
        try {
            const resp = await fetch('/api/i18n');
            if (!resp.ok) throw new Error('i18n unavailable');
            const data = await resp.json();
            messages = data.messages || {};
            language = data.language || 'en_US';
        } catch (e) {
            messages = {};
        }
        return language;
    }

    /** 把 data-i18n / data-i18n-placeholder / data-i18n-title 应用到静态标签上 */
    function apply(root) {
        const scope = root || document;
        scope.querySelectorAll('[data-i18n]').forEach(el => {
            el.textContent = t(el.getAttribute('data-i18n'), el.textContent.trim());
        });
        scope.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
            el.setAttribute('placeholder',
                t(el.getAttribute('data-i18n-placeholder'), el.getAttribute('placeholder') || ''));
        });
        scope.querySelectorAll('[data-i18n-title]').forEach(el => {
            el.setAttribute('title', t(el.getAttribute('data-i18n-title'), el.getAttribute('title') || ''));
        });
        if (scope === document) {
            document.documentElement.lang = language === 'zh_CN' ? 'zh-CN' : 'en';
            const titleKey = document.documentElement.getAttribute('data-i18n-title-key');
            if (titleKey) document.title = t(titleKey, document.title);
        }
    }

    global.I18n = { t, tf, load, apply, get language() { return language; } };
})(window);
