import type { NotificationPayload } from './settings/types';

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
      showNotification: (notification: unknown) => void;
      showToast: (type: string, message: string) => void;
      copyText: (text: string) => Promise<boolean>;
      csrfPair: () => { name: string; value: string } | null;
    };
    EnderVaultFileBrowser?: {
      refreshListing: (url?: string) => Promise<void>;
      requestListingRefresh: (url?: string) => void;
      syncToolbarState: () => void;
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
      claimPageScope: (owner: string) => boolean;
      createActionMenu: (options: Record<string, unknown>) => {
        close: () => void;
      } | null;
    };
    EnderVaultContextMenu?: {
      registerAction: (action: Record<string, unknown>) => Record<string, unknown>;
      registerExtensionAction: (action: Record<string, unknown>) => void;
      extensionActions: () => Array<Record<string, unknown>>;
      close: () => void;
    };
    EnderVaultServerTasks?: {
      track: (task: unknown, options?: Record<string, unknown>) => void;
    };
    EnderVaultFavorites?: {
      toggleBookmark: (id: string) => Promise<{ active?: boolean }>;
    };
    EnderVaultToasts?: {
      show: (notification: NotificationPayload) => void;
    };
  }
}
