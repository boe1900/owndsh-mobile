/**
 * [INPUT]: 依赖锁定的 PageController 与宿主传入的动作、请求 ID 和页面快照
 * [OUTPUT]: 在当前页面提供可轮询的读页、点击、输入和滚动结果
 * [POS]: 独立浏览器注入脚本，仅接触网页 DOM，不访问原生接口或 DSH 会话
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
(() => {
  const controller = new PageController({ viewportExpansion: 0 });
  let snapshot;
  let pending;
  const completed = data => ({ ok: true, data: { status: 'completed', ...data } });
  const failed = (code, message) => ({ ok: false, error: { code, message } });
  const secretInput = 'input:is([type=password],[type=file],[type=hidden])';

  async function act(request, action, args) {
    if (action === 'read') {
      await controller.updateTree();
      if (pending !== request) return failed('cancelled', 'The browser request was cancelled.');
      for (const node of Object.values(controller.flatTree.map)) {
        if (node.attributes) delete node.attributes.value;
      }
      const content = flatTreeToString(controller.flatTree);
      const url = new URL(location.href);
      snapshot = { id: crypto.randomUUID(), location: location.href };
      return completed({ url: url.origin + url.pathname, title: document.title.slice(0, 300),
        text: content.length > 19800 ? content.slice(0, 19800) + '\n[Page output truncated]' : content, snapshot: snapshot.id });
    }
    if (action === 'scroll') {
      snapshot = undefined;
      window.scrollBy(0, window.innerHeight * (args.direction === 'up' ? -0.8 : 0.8));
      return completed({ note: 'Scrolled the page. Read again before selecting an element.' });
    }
    if (action !== 'click' && action !== 'type') return failed('invalid_argument', 'Unknown browser action.');
    const current = snapshot;
    snapshot = undefined;
    if (!current || args.snapshot !== current.id || location.href !== current.location) {
      return failed('stale_snapshot', 'Read the current page before using an element number.');
    }
    const element = controller.selectorMap.get(args.index)?.ref;
    if (!element?.isConnected || element.matches(':disabled')) return failed('stale_snapshot', 'The numbered element is no longer available.');
    const autocomplete = element.getAttribute('autocomplete') ?? '';
    if (element.matches(secretInput) || /(?:password|one-time-code|webauthn|cc-)/i.test(autocomplete)) {
      return failed('takeover', 'Use the browser takeover button to operate this field.');
    }
    if (action === 'type' && !element.matches('textarea,input:is(:not([type]),[type=text],[type=search],[type=tel],[type=email],[type=url],[type=number])')
        && !element.isContentEditable) return failed('invalid_argument', 'The numbered element does not accept text.');

    const active = () => pending === request && element.isConnected && location.href === current.location;
    const result = action === 'click'
      ? await controller.clickElement(args.index, active)
      : await controller.inputText(args.index, args.text, active);
    if (!result.success) return failed('action_failed', 'The page controller could not complete the action. Read the page again.');
    return completed({ note: action === 'click' ? 'Clicked the element.' : 'Entered text into the element.' });
  }

  globalThis.__ownDshPageController = Object.freeze({
    start(id, action, args) {
      if (pending) return false;
      const request = { id, reply: null };
      pending = request;
      act(request, action, args).then(reply => { request.reply = reply; }, () => {
        request.reply = failed('unavailable', 'The current page could not be processed.');
      });
      return true;
    },
    result(id) {
      if (pending?.id !== id || pending.reply === null) return null;
      const { reply } = pending;
      pending = undefined;
      return reply;
    },
    reset() {
      pending = undefined;
      snapshot = undefined;
      controller.cleanUpHighlights();
    },
  });
})();
