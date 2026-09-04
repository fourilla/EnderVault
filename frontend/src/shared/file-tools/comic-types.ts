export interface ComicManifest {
  pageCount: number;
  metadata: {
    present: boolean;
    truncated: boolean;
    rawText: string;
    entries: Array<{ name: string; value: string }>;
  };
}
