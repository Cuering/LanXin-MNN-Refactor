package com.lanxin.refactor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanxin.refactor.paths.ModelFolderAccess
import java.io.File

/**
 * \u6a21\u578b\u8def\u5f84\u884c\uff1a\u663e\u793a\u5f53\u524d\u76ee\u5f55 + \u626b\u63cf\u5019\u9009 chip + \u300c\u9009\u6587\u4ef6\u5939\u300d\u3002
 * \u4e0d\u624b\u586b\u8def\u5f84\u3002
 */
@Composable
fun ModelPathPickerRow(
    title: String,
    path: String,
    scanRoots: List<File>,
    onPathPicked: (String) -> Unit,
    onPickFailed: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val candidates = remember(scanRoots, path) {
        scanRoots.flatMap { ModelFolderAccess.listCandidateDirs(it) }
            .distinctBy { it.absolutePath }
            .take(12)
    }
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        ModelFolderAccess.takePersistableRead(context, uri)
        val abs = ModelFolderAccess.treeUriToAbsolutePath(uri)
        if (abs.isNullOrBlank()) {
            onPickFailed("\u65e0\u6cd5\u89e3\u6790\u8be5\u76ee\u5f55\u8def\u5f84\uff08\u8bf7\u9009\u5185\u7f6e\u5b58\u50a8\u4e0b\u7684\u6587\u4ef6\u5939\uff09")
        } else {
            onPathPicked(abs)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = if (path.isBlank()) "\u672a\u9009\u62e9\u76ee\u5f55" else path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { treePicker.launch(null) }) {
                Text("\u9009\u6587\u4ef6\u5939")
            }
            OutlinedButton(
                onClick = {
                    // \u5237\u65b0\uff1a\u82e5\u5f53\u524d path \u7236\u76ee\u5f55\u6709\u5019\u9009\uff0c\u4fdd\u6301\uff1b\u5426\u5219\u53d6\u7b2c\u4e00\u4e2a\u5019\u9009
                    val first = candidates.firstOrNull()?.absolutePath
                    if (first != null) onPathPicked(path.ifBlank { first })
                },
                enabled = candidates.isNotEmpty()
            ) {
                Text("\u626b\u63cf ${candidates.size}")
            }
        }
        if (candidates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                candidates.forEach { dir ->
                    val selected = dir.absolutePath == path
                    AssistChip(
                        onClick = { onPathPicked(dir.absolutePath) },
                        label = {
                            Text(
                                ModelFolderAccess.shortLabel(dir.absolutePath),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = if (selected) {
                            { Text("\u2713") }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}