export interface SharedBootstrap {
  token: string;
  targetType: 'FILE' | 'DIRECTORY';
  rootUrl: string;
}

export interface SharedBreadcrumb {
  label: string;
  path: string;
  url: string;
}

export interface SharedEntry {
  name: string;
  path: string;
  directory: boolean;
  hidden: boolean;
  sizeLabel: string;
  modifiedLabel: string;
  mediaType: string;
  typeLabel: string;
  openUrl: string;
  detailUrl: string | null;
  downloadUrl: string | null;
  previewLandingUrl: string | null;
}

export interface SharedListing {
  targetType: 'DIRECTORY';
  view: 'listing';
  path: string;
  parentPath: string | null;
  rootUrl: string;
  upUrl: string | null;
  breadcrumbs: SharedBreadcrumb[];
  entries: SharedEntry[];
  downloadZipUrl: string;
}

export interface SharedText {
  loaded: boolean;
  content: string | null;
  message: string | null;
}

export interface SharedDetail {
  targetType: 'FILE' | 'DIRECTORY';
  view: 'detail';
  path: string;
  parentPath: string | null;
  name: string;
  mediaType: string;
  sizeLabel: string;
  modifiedLabel: string;
  extension: string;
  rootUrl: string;
  upUrl: string | null;
  downloadUrl: string;
  toolType: string | null;
  toolLabel: string | null;
  previewEnabled: boolean;
  previewContentUrl: string | null;
  comicManifestUrl: string | null;
  text: SharedText | null;
}

export type SharedView = SharedListing | SharedDetail;
