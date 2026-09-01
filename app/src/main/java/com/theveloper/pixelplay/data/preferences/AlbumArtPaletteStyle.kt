package com.theveloper.pixelplay.data.preferences

enum class AlbumArtPaletteStyle(
    val storageKey: String,
    val label: String
) {
    TONAL_SPOT("tonal_spot", "Tonal Spot"),
    VIBRANT("vibrant", "Vibrant"),
    EXPRESSIVE("expressive", "Expressive"),
    FRUIT_SALAD("fruit_salad", "Fruit Salad"),

    /**
     * Faithful extraction: keeps the artwork's dominant hue and chroma instead of remapping it
     * onto a fixed tonal spot, giving deep, ambient surfaces in the spirit of YT Music.
     * Default when the app color source is album art.
     */
    EGNUS("egnus", "Egnus");

    companion object {
        val default: AlbumArtPaletteStyle = TONAL_SPOT

        fun fromStorageKey(value: String?): AlbumArtPaletteStyle {
            return entries.firstOrNull { it.storageKey == value } ?: default
        }
    }
}
