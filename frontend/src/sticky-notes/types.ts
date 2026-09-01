export interface StickyNoteCatalogItem {
  id: string;
  content: string;
  summary: string;
  contextLabel: string;
  targetType: string;
  surfaceLabel: string;
  updatedLabel: string;
  targetExists: boolean;
  openUrl: string | null;
}

export interface StickyNoteCatalogPayload {
  notes: StickyNoteCatalogItem[];
}

export interface StickyNoteDeletePayload {
  ok: boolean;
  notification?: unknown;
  deletedId: string;
}
