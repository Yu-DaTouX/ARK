package app.ark.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** material-icons-core 里没有的几个图标（Material Symbols 的路径，Apache-2.0）。 */
object ArkIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { b.addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
        return b.build()
    }

    val Chat = icon("Chat", "M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM6,9h12v2H6V9zM14,14H6v-2h8V14zM18,8H6V6h12V8z")
    val Book = icon("Book", "M18,2H6C4.9,2 4,2.9 4,4v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C20,2.9 19.1,2 18,2zM6,4h5v8l-2.5,-1.5L6,12V4z")
    val Map = icon("Map", "M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5c0,0.28 0.22,0.5 0.5,0.5l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 0.36,-0.48V3.5C21,3.22 20.78,3 20.5,3zM15,19l-6,-2.11V5l6,2.11V19z")
    val Bookmark = icon("Bookmark", "M17,3H7c-1.1,0 -2,0.9 -2,2v16l7,-3 7,3V5C19,3.9 18.1,3 17,3z")
    val BookmarkBorder = icon("BookmarkBorder", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5C19,3.9 18.1,3 17,3zM17,18l-5,-2.18L7,18V5h10V18z")
    val Stop = icon("Stop", "M6,6h12v12H6z")
    val Copy = icon("Copy", "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z")
    val Download = icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    val Upload = icon("Upload", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z")
    val Chevron = icon("Chevron", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
    val Tools = icon("Tools", "M22.7,19l-9.1,-9.1c0.9,-2.3 0.4,-5 -1.5,-6.9 -2,-2 -5,-2.4 -7.4,-1.3L9,6 6,9 1.9,4.9C0.8,7.3 1.2,10.3 3.2,12.2c1.9,1.9 4.6,2.4 6.9,1.5l9.1,9.1c0.4,0.4 1,0.4 1.4,0l2.1,-2.1c0.5,-0.4 0.5,-1 0.1,-1.4z")
    val ChevronBack = icon("ChevronBack", "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z")
}
