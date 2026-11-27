/**
 * 前端共享错误码映射与人类可读错误文案模块（所有子页面共用，消除多页重复维护导致的键名漂移）。
 */
(function (global) {
    const t = (k, d) => (global.I18n && typeof global.I18n.t === 'function' ? global.I18n.t(k, d) : (d !== undefined ? d : k));
    const tf = (k, ...a) => (global.I18n && typeof global.I18n.tf === 'function' ? global.I18n.tf(k, ...a) : k);

    const ERROR_KEYS = Object.freeze({
        // 认证与通用
        'csrf.invalid': 'review.error.csrf',
        'item.not.found': 'review.error.item.not.found',
        'item.already.resolved': 'review.error.already.resolved',
        'item.not.rejected': 'review.error.item.not.rejected',
        'folder.already.pending': 'review.error.folder.already.pending',
        'unreject.log.failed': 'review.error.unreject.log.failed',
        'files.missing': 'review.error.files.missing',
        'candidate.not.found': 'review.error.candidate.not.found',
        'release.id.required': 'review.error.release.required',
        'candidates.expand.failed': 'review.error.expand.failed',
        // LLM 与联网识别
        'llm.judge.disabled': 'review.error.llm.disabled',
        'llm.not.configured': 'review.error.llm.not.configured',
        'llm.call.failed': 'review.error.llm.call.failed',
        'llm.web.search.unavailable': 'review.error.web.search.unavailable',
        'online.destination.exists': 'review.error.destination.exists',
        'online.duplicate.exists': 'review.duplicate.title',
        'online.track.credits.invalid': 'review.online.credits.invalid',
        // 恢复与回收站
        'recovery.unavailable': 'review.error.recovery.unavailable',
        'recovery.already.running': 'review.error.online.already.running',
        'recovery.no.audio.files': 'review.error.no.audio.files',
        'recovery.item.not.found': 'recovery.error.item.not.found',
        'recovery.llm.partial.only': 'recovery.error.llm.partial.only',
        'recovery.directory.not.configured': 'recovery.error.directory.not.configured',
        'trash.restore.target.exists': 'recovery.error.restore.target.exists',
        'trash.manifest.missing': 'recovery.error.manifest.missing',
        'trash.manifest.invalid': 'recovery.error.manifest.invalid',
        'trash.entry.not.found': 'recovery.error.entry.not.found',
        'trash.id.invalid': 'recovery.error.id.invalid',
        // 本地标签归档
        'local.archive.stale': 'review.local.error.stale',
        'local.archive.metadata.required': 'review.local.error.required',
        'local.archive.tracks.invalid': 'review.local.error.tracks',
        'local.archive.write.failed': 'review.local.error.write',
        // 缺少封面工作流
        'cover.selection.expired': 'review.error.cover.expired',
        'cover.upload.empty': 'covers.err.upload.empty',
        'cover.upload.too.large': 'covers.err.upload.too.large',
        'cover.upload.invalid': 'covers.err.upload.invalid',
        'cover.url.required': 'covers.err.url.required',
        'cover.url.fetch.failed': 'covers.err.url.fetch.failed',
        'cover.selection.required': 'covers.err.selection.required',
        'cover.embed.partial.failed': 'covers.err.embed.partial.failed',
        'cover.apply.write.failed': 'covers.err.apply.write.failed',
        'cover.scan.already.running': 'covers.err.scan.already.running',
        'cover.search.unavailable': 'covers.err.search.unavailable',
        'search.queue.closed': 'covers.err.queue.closed',
        'unsafe.output.root': 'covers.err.unsafe.root',
        'missing.id': 'covers.err.missing.id',
        // 处理记录页
        'records.no.target': 'records.error.no.target',
        // 初始化引导
        'setup.required': 'setup.error.required',
        'setup.monitor.directory.missing': 'setup.error.monitor.missing',
        'setup.output.directory.missing': 'setup.error.output.missing',
        'setup.output.directory.unwritable': 'setup.error.output.unwritable',
        'setup.activation.failed': 'setup.error.activation.failed',
        'field.readonly': 'setup.error.readonly',
        'secret.required.on.endpoint.change': 'setup.error.secret.reentry',
        'api.key.required.on.url.change': 'setup.error.secret.reentry',
        'proxy.invalid': 'setup.s2.proxy.needHost'
    });

    function humanError(code, overrides) {
        if (!code) return t('review.action.failed', 'Error');
        const msg = String(code);
        const coverage = /^online\.track\.coverage\.insufficient:(\d+)\/(\d+)$/.exec(msg);
        if (coverage) {
            return tf('review.error.online.coverage.insufficient', coverage[1], coverage[2]);
        }
        const mismatch = /^recovery\.workspace\.count\.mismatch:(\d+)\/(\d+)$/.exec(msg);
        if (mismatch) {
            return tf('recovery.workspace.count.mismatch', mismatch[1], mismatch[2]);
        }
        const key = (overrides && overrides[msg]) || ERROR_KEYS[msg];
        return key ? t(key, msg) : msg;
    }

    global.AppErrors = { ERROR_KEYS, humanError };
})(window);
