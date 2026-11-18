import { installBridge, type EntireBridge } from './bridge';

declare global {
  interface Window {
    /** Written into the document by the IDE, before this script. It carries one string to the IDE. */
    __entireSend?: (payload: string) => void;
    __entire?: EntireBridge;
  }
}

// This script runs before the panel script. Without the IDE's function there
// is no host, and the panel script then fails where it asks for one.
const send = window.__entireSend;
if (typeof send === 'function') window.__entire = installBridge(window, send);
