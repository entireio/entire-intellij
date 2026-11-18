// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installBridge, type EntireBridge, type HostApi } from '../../src/bridge/bridge';

type Sent = Record<string, unknown>;

function acquire(): HostApi {
  return (window as unknown as { acquireVsCodeApi: () => HostApi }).acquireVsCodeApi();
}

describe('the bridge', () => {
  let sent: Sent[];
  let bridge: EntireBridge;

  beforeEach(() => {
    sent = [];
    bridge = installBridge(window, (payload) => sent.push(JSON.parse(payload) as Sent));
  });

  it('gives the panel script a host that sends each message to the IDE as JSON', () => {
    acquire().postMessage({ type: 'requestToolBody', requestId: 3, turn: 1, block: 0 });

    expect(sent).toEqual([{ type: 'requestToolBody', requestId: 3, turn: 1, block: 0 }]);
  });

  it('hands out the same host each time', () => {
    expect(acquire()).toBe(acquire());
  });

  it('keeps no state for the page, and has none to return', () => {
    const api = acquire();
    api.setState({ repoRoot: '/repo', mode: 'checkpoint' });

    expect(api.getState()).toBeUndefined();
    expect(sent).toEqual([]);
  });

  it('delivers a message from the IDE as a message event with the object as its data', () => {
    const seen: unknown[] = [];
    const listener = (event: MessageEvent): void => {
      seen.push(event.data);
    };
    window.addEventListener('message', listener);

    bridge.rx(JSON.stringify({ type: 'loading', title: 'A commit' }));

    window.removeEventListener('message', listener);
    expect(seen).toEqual([{ type: 'loading', title: 'A commit' }]);
  });

  it('puts a message that arrives in parts back together, in any order', () => {
    const seen: unknown[] = [];
    const listener = (event: MessageEvent): void => {
      seen.push(event.data);
    };
    window.addEventListener('message', listener);
    const json = JSON.stringify({ type: 'image', requestId: 1, mediaType: 'image/png', base64: 'QUJD'.repeat(50) });
    const parts = [json.slice(0, 40), json.slice(40, 90), json.slice(90)];

    bridge.rxChunk(7, 2, 3, parts[2] ?? '');
    bridge.rxChunk(7, 0, 3, parts[0] ?? '');
    expect(seen).toEqual([]);
    bridge.rxChunk(7, 1, 3, parts[1] ?? '');

    window.removeEventListener('message', listener);
    expect(seen).toEqual([JSON.parse(json)]);
  });

  it('keeps the parts of two messages apart', () => {
    const seen: unknown[] = [];
    const listener = (event: MessageEvent): void => {
      seen.push(event.data);
    };
    window.addEventListener('message', listener);

    bridge.rxChunk(1, 0, 2, '{"type":"a",');
    bridge.rxChunk(2, 0, 2, '{"type":"b",');
    bridge.rxChunk(2, 1, 2, '"n":2}');
    bridge.rxChunk(1, 1, 2, '"n":1}');

    window.removeEventListener('message', listener);
    expect(seen).toEqual([
      { type: 'b', n: 2 },
      { type: 'a', n: 1 },
    ]);
  });

  it('ignores a part whose position makes no sense', () => {
    const seen: unknown[] = [];
    const listener = (event: MessageEvent): void => {
      seen.push(event.data);
    };
    window.addEventListener('message', listener);

    bridge.rxChunk(1, 0, 0, '{}');
    bridge.rxChunk(1, 2, 2, '{}');
    bridge.rxChunk(1, -1, 2, '{}');
    bridge.rxChunk(1, 0.5, 2, '{}');
    bridge.rxChunk(1, 0, 100_000, '{}');

    window.removeEventListener('message', listener);
    expect(seen).toEqual([]);
  });

  it('sets theme variables on the document, and replaces a value set before', () => {
    bridge.theme({ '--vscode-foreground': '#111111', '--vscode-editor-background': '#ffffff' });
    bridge.theme({ '--vscode-foreground': '#eeeeee' });

    const style = document.documentElement.style;
    expect(style.getPropertyValue('--vscode-foreground')).toBe('#eeeeee');
    expect(style.getPropertyValue('--vscode-editor-background')).toBe('#ffffff');
  });

  it('sets nothing that is not a custom property', () => {
    bridge.theme({ color: 'red', 'background-image': 'url(https://example.com/x.png)', '--ok': '1' });

    const style = document.documentElement.style;
    expect(style.getPropertyValue('color')).toBe('');
    expect(style.getPropertyValue('background-image')).toBe('');
    expect(style.getPropertyValue('--ok')).toBe('1');
  });
});

describe('the panel script on the bridge', () => {
  let sent: Sent[];
  let bridge: EntireBridge;

  /** Loads the panel script the way the document does: after the bridge, with the host as a global. */
  async function load(view: 'detail' | 'trail'): Promise<void> {
    document.body.innerHTML = `<div id="app" data-view="${view}"></div>`;
    sent = [];
    bridge = installBridge(window, (payload) => sent.push(JSON.parse(payload) as Sent));
    vi.stubGlobal('acquireVsCodeApi', (window as unknown as { acquireVsCodeApi: () => HostApi }).acquireVsCodeApi);
    vi.resetModules();
    await import('../../src/webview/main');
  }

  afterEach(() => {
    vi.unstubAllGlobals();
    document.body.innerHTML = '';
  });

  it('tells the IDE that the detail page is ready, with the protocol it speaks', async () => {
    await load('detail');

    expect(sent).toEqual([{ type: 'ready', protocol: 1 }]);
  });

  it('tells the IDE that the trail page is ready', async () => {
    await load('trail');

    expect(sent).toEqual([{ type: 'ready', protocol: 1 }]);
  });

  it('draws what the IDE sends', async () => {
    await load('detail');

    bridge.rx(JSON.stringify({ type: 'error', message: 'The repository for this tab is not open in this window.', canRetry: true }));

    expect(document.getElementById('app')?.textContent).toContain('The repository for this tab is not open in this window.');
  });

  it('sends a click on Retry back to the IDE', async () => {
    await load('detail');
    bridge.rx(JSON.stringify({ type: 'error', message: 'It failed.', canRetry: true }));
    sent.length = 0;

    const retry = [...document.querySelectorAll('button')].find((button) => button.textContent === 'Retry');
    retry?.click();

    expect(sent).toEqual([{ type: 'retry' }]);
  });
});
