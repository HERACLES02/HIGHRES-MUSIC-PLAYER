// Pure functions: the same input produces the same output, without changing
// the songs. This makes filtering/sorting easy to test independently of the UI.
window.LibraryTools = {
  filterAndSort(items, options = {}) {
    const query = String(options.query || "").trim().toLocaleLowerCase();
    const result = items.filter((track) => {
      if (query && !`${track.title} ${track.artist} ${track.album} ${track.folder || ""}`.toLocaleLowerCase().includes(query)) return false;
      if (options.format && !String(track.relpath || "").toLowerCase().endsWith(`.${options.format}`)) return false;
      if (options.status === "liked" && !options.likedIds?.has(track.id)) return false;
      if (options.status === "missing-cover" && track.hasCover) return false;
      if (options.status === "weak-tags" && ![track.artist, track.album].some((value) => !value || /^unknown (artist|album)$/i.test(value))) return false;
      return true;
    });
    const sort = options.sort || "default";
    if (sort === "added") result.sort((a, b) => (b.addedAt || 0) - (a.addedAt || 0) || a.id.localeCompare(b.id));
    else if (["title", "artist", "album"].includes(sort)) {
      result.sort((a, b) => String(a[sort]).localeCompare(String(b[sort]), undefined, { numeric: true, sensitivity: "base" })
        || a.title.localeCompare(b.title, undefined, { numeric: true }) || a.id.localeCompare(b.id));
    }
    return result;
  },
};
