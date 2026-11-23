export interface FavoriteFolder {
  folderId: string
  name: string
  itemCount: number
  defaultFolder: boolean
  selected: boolean
}

export interface ProjectFavoriteState {
  selected: boolean
}
