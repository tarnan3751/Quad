package com.campusmarket.app.feature.browse

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Material "tune" (filter sliders) icon; not part of material-icons-core, so drawn here from its 24 dp path. */
internal val TuneIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Tune") {
        materialPath {
            // M3,17v2h6v-2H3z
            moveTo(3f, 17f); verticalLineToRelative(2f); horizontalLineToRelative(6f); verticalLineToRelative(-2f); horizontalLineTo(3f); close()
            // M3,5v2h10V5H3z
            moveTo(3f, 5f); verticalLineToRelative(2f); horizontalLineToRelative(10f); verticalLineTo(5f); horizontalLineTo(3f); close()
            // M13,21v-2h8v-2h-8v-2h-2v6h2z
            moveTo(13f, 21f); verticalLineToRelative(-2f); horizontalLineToRelative(8f); verticalLineToRelative(-2f)
            horizontalLineToRelative(-8f); verticalLineToRelative(-2f); horizontalLineToRelative(-2f); verticalLineToRelative(6f)
            horizontalLineToRelative(2f); close()
            // M7,9v2H3v2h4v2h2V9H7z
            moveTo(7f, 9f); verticalLineToRelative(2f); horizontalLineTo(3f); verticalLineToRelative(2f); horizontalLineToRelative(4f)
            verticalLineToRelative(2f); horizontalLineToRelative(2f); verticalLineTo(9f); horizontalLineTo(7f); close()
            // M21,13v-2H11v2h10z
            moveTo(21f, 13f); verticalLineToRelative(-2f); horizontalLineTo(11f); verticalLineToRelative(2f); horizontalLineToRelative(10f); close()
            // M15,9h2V7h4V5h-4V3h-2v6z
            moveTo(15f, 9f); horizontalLineToRelative(2f); verticalLineTo(7f); horizontalLineToRelative(4f); verticalLineTo(5f)
            horizontalLineToRelative(-4f); verticalLineTo(3f); horizontalLineToRelative(-2f); verticalLineToRelative(6f); close()
        }
    }
}
