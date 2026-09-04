export interface FavoriteEntry {
  path: string;
  name: string;
  typeLabel: string;
  iconClass: string;
  openUrl: string;
  directOpenUrl: string;
  detailUrl: string;
  openInNewTab: boolean;
  bookmarkLink: boolean;
  hidden: boolean;
  targetLabel: string;
  createdLabel: string;
}

export interface FavoritesPayload {
  items: FavoriteEntry[];
}
