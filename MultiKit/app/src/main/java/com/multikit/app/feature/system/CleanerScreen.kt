package com.multikit.app.feature.system

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.ToolScaffold

/** Placeholder screen: the original source for this tool was missing from the project zip. */
@Composable
fun CleanerScreen(onBack: () -> Unit) {
    ToolScaffold(title = "Storage Cleaner", onBack = onBack) {
        SectionCard("Coming soon") {
            Text("This tool is not implemented yet.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
