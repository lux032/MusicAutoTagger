/**
 * 前端共享 API 客户端模块：统一携带 credentials / CSRF Token / JSON Content-Type，
 * 并在 HTTP 401 时停止后台轮询与队列、执行回调或跳转登录页。
 */
(function (global) {
    function isBinaryBody(body) {
        return (typeof File !== 'undefined' && body instanceof File)
            || (typeof Blob !== 'undefined' && body instanceof Blob)
            || (typeof FormData !== 'undefined' && body instanceof FormData);
    }

    function stopBackgroundPolling() {
        if (global.SearchQueue && typeof global.SearchQueue.stop === 'function') {
            global.SearchQueue.stop();
        }
    }

    /**
     * 通用 fetch 封装。
     * @param {string} path 请求路径
     * @param {object} options fetch 选项 + 扩展项：
     *   - csrfToken / getCsrf: 提供 CSRF Token
     *   - onUnauthorized: 自定义 401 处理函数（若省略则跳转 redirectOn401）
     *   - redirectOn401: 默认 '/index.html'
     *   - rawResponse: true 时返回原生 Response 对象（供 index.html authFetch 使用）
     *   - mapError: 可选错误文案转换函数 (code, data, status) => string
     */
    async function request(path, options = {}) {
        const {
            csrfToken,
            getCsrf,
            onUnauthorized,
            redirectOn401 = '/index.html',
            rawResponse = false,
            mapError,
            ...fetchOpts
        } = options;

        const headers = { ...(fetchOpts.headers || {}) };
        const method = String(fetchOpts.method || 'GET').toUpperCase();
        const token = typeof getCsrf === 'function' ? getCsrf() : csrfToken;
        if (token && method !== 'GET' && method !== 'HEAD') {
            headers['X-CSRF-Token'] = token;
        }
        if (fetchOpts.body && !isBinaryBody(fetchOpts.body) && !headers['Content-Type']) {
            headers['Content-Type'] = 'application/json';
        }

        const resp = await fetch(path, { credentials: 'same-origin', ...fetchOpts, headers });
        if (resp.status === 401) {
            stopBackgroundPolling();
            if (typeof onUnauthorized === 'function') {
                await onUnauthorized(resp);
            } else if (redirectOn401) {
                location.href = redirectOn401;
            }
            const err = new Error('unauthorized');
            err.status = 401;
            throw err;
        }
        if (rawResponse) {
            return resp;
        }

        let data = {};
        try { data = await resp.json(); } catch (e) { /* ignore non-JSON body */ }
        if (!resp.ok) {
            const rawCode = data.error || ('HTTP ' + resp.status);
            const message = typeof mapError === 'function' ? mapError(rawCode, data, resp.status) : rawCode;
            const err = new Error(message);
            err.status = resp.status;
            err.code = rawCode;
            err.data = data;
            throw err;
        }
        return data;
    }

    global.AppApi = { request, stopBackgroundPolling };
})(window);
