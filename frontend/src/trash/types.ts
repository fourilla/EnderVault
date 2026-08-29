export interface TrashItem {
  id: string;
  originalName: string;
  originalPath: string;
  directory: boolean;
  typeLabel: string;
  sizeLabel: string;
  deletedLabel: string;
  expiresLabel: string;
}

export interface TrashPayload {
  items: TrashItem[];
}
