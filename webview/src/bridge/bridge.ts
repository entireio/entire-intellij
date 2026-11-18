/**
 * The page's side of the bridge to the IDE.
 *
 * The panel script was written for a VS Code webview. It asks for its host
 * with `acquireVsCodeApi()`, posts messages to it, and listens for `message`
 * events on the window. This file supplies those three things inside the
 * IDE's embedded browser, so the panel script runs unchanged.
 */

/** What `acquireVsCodeApi()` returns. The panel script calls `postMessage` and `setState`. */
export interface HostApi {
  postMessage(message: unknown): void;
  setState(state: unknown): void;
  getState(): unknown;
}

/** What the IDE calls, by running a line of script in the page. */
export interface EntireBridge {
  /** Delivers one message, as JSON text, to the panel script. */
  rx(json: string): void;
  /**
   * Delivers one message in parts. The IDE splits a large message, such as an
   * image, so that no single line of script has to carry all of it.
   */
  rxChunk(id: number, index: number, total: number, text: string): void;
  /** Sets theme variables on the document, replacing the values set before. */
  theme(vars: Record<string, string>): void;
}

/** A CSS custom property name. Nothing else may be set through `theme`. */
const VARIABLE = /^--[A-Za-z0-9_-]+$/;

/** The most parts one message may arrive in. */
const MAX_PARTS = 4096;

export function installBridge(win: Window, send: (payload: string) => void): EntireBridge {
  const api: HostApi = {
    postMessage(message) {
      send(JSON.stringify(message));
    },
    // The IDE keeps what a tab shows, because it is the IDE that sent it.
    setState() {},
    getState() {
      return undefined;
    },
  };
  (win as unknown as { acquireVsCodeApi: () => HostApi }).acquireVsCodeApi = () => api;

  const deliver = (json: string): void => {
    win.dispatchEvent(new MessageEvent('message', { data: JSON.parse(json) as unknown }));
  };

  const partial = new Map<number, { parts: string[]; received: number }>();

  return {
    rx: deliver,
    rxChunk(id, index, total, text) {
      if (!Number.isInteger(total) || total < 1 || total > MAX_PARTS) return;
      if (!Number.isInteger(index) || index < 0 || index >= total) return;
      let entry = partial.get(id);
      if (!entry || entry.parts.length !== total) {
        entry = { parts: new Array<string>(total), received: 0 };
        partial.set(id, entry);
      }
      if (entry.parts[index] === undefined) entry.received++;
      entry.parts[index] = text;
      if (entry.received === total) {
        partial.delete(id);
        deliver(entry.parts.join(''));
      }
    },
    theme(vars) {
      const style = win.document.documentElement.style;
      for (const [name, value] of Object.entries(vars)) {
        if (VARIABLE.test(name) && typeof value === 'string') style.setProperty(name, value);
      }
    },
  };
}
