package com.studyflow.app

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// IBM Plex Sans (SIL Open Font License 1.1). Files are fetched at build time
// into res/font by the CI workflow (see .github/workflows/build.yml).
val Plex = FontFamily(
    Font(R.font.plex_sans_light, FontWeight.Light),
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_semibold, FontWeight.Bold),
)
