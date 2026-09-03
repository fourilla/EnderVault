import type { NotificationPayload } from './settings/types';
import type { BrowserMenuAction } from './shared/browser/browser-menu-context';
import type { BrowserEntry } from './shared/browser/types';

export {};

declare global {
  interface Window {
    CodeMirror?: any;
    markdownit?: any;
    markdownitTaskLists?: any;
    markdownitFootnote?: any;
    DOMPurify?: any;
    hljs?: any;
    mermaid?: any;
    MathJax?: any;
    EnderVaultMarkdownMermaidLoader?: () => Promise<any>;
    EnderVaultMarkdownMathLoader?: () => Promise<any>;
    EnderVaultMarkdown?: {
      renderInto: (target: Element, source: string, sourcePath?: string) => Promise<void>;
    };
    EnderVaultFileTools?: {
      init: (root?: ParentNode) => void;
      destroy: (root?: ParentNode) => void;
    };
    Viewer?: new (image: HTMLImageElement, options: Record<string, unknown>) => any;
    EnderVaultImageViewers?: {
      init: (root?: ParentNode) => void;
      destroy: (root?: ParentNode) => void;
    };
    EnderVault?: {
      requestJson: (url: string, options?: RequestInit) => Promise<any>;
      requestJsonResolvingConflicts: (url: string, options?: RequestInit) => Promise<any>;
      askTextInput: (options: Record<string, unknown>) => Promise<string | null>;
      askConfirmation: (options: {
        title: string;
        message: string;
        confirmLabel: string;
        danger?: boolean;
      }) => Promise<boolean>;
      askFileConflictPolicy?: (options: Record<string, unknown>) => Promise<string>;
      showNotification: (notification: unknown) => void;
      showToast: (type: string, message: string) => void;
      copyText: (text: string) => Promise<boolean>;
      csrfPair: () => { name: string; value: string } | null;
      navigateWithNotification: (body: { notification?: unknown; redirectUrl?: string | null }) => boolean;
      navigate: (url: string) => boolean;
    };
    EnderVaultFileBrowser?: {
      refreshListing: (url?: string) => Promise<void>;
      requestListingRefresh: (url?: string) => void;
      syncToolbarState: () => void;
    };
    EnderVaultActivity?: {
      upsert: (item: Record<string, unknown>) => unknown;
      remove: (id: string) => void;
      scheduleRemoval: (id: string, delayMs?: number) => void;
      cancel: (id: string) => Promise<void>;
      formatBytes: (bytes: number) => string;
      snapshot: () => {
        activeCount: number;
        finishedCount: number;
        totalCount: number;
        items: Array<{
          id: string;
          title: string;
          type: string;
          typeLabel: string;
          status: string;
          percent: number;
          message: string;
          cancelRequested: boolean;
          cancelable: boolean;
        }>;
      };
    };
    EnderVaultResumableUpload?: {
      create: (options: Record<string, unknown>) => EnderVaultUploadHandle;
      fingerprint: (file: File, context: string) => Promise<string>;
    };
    EnderVaultStickyNotes?: {
      setContext: (context: {
        targetType: string;
        targetKey: string;
        surface: string;
        label: string;
      }) => Promise<void> | void;
    };
    EnderVaultContextMenus?: {
      createActionMenu: (options: Record<string, unknown>) => {
        close: () => void;
        dispose: () => void;
      } | null;
    };
    EnderVaultContextMenu?: {
      registerAction: (action: BrowserMenuAction<BrowserEntry>) => BrowserMenuAction<BrowserEntry>;
      registerExtensionAction: (action: BrowserMenuAction<BrowserEntry> & { extensions: string[] }) => void;
      extensionActions: () => Array<BrowserMenuAction<BrowserEntry> & { extensions: string[] }>;
      close: () => void;
    };
    EnderVaultServerTasks?: {
      track: (task: unknown, options?: Record<string, unknown>) => void;
    };
    EnderVaultToasts?: {
      show: (notification: NotificationPayload) => void;
    };
  }

  interface EnderVaultUploadHandle {
    start: () => Promise<any>;
    abort: () => Promise<void>;
  }
}
