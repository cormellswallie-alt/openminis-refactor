package com.openminis.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.R
import com.openminis.app.ui.components.ImageGalleryItem
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.sandbox.FileItem
import java.io.File

/**
 * T185: Floating "Move to…" capsule pinned to the top-right corner of the composer card
 * when the composer holds injected Android share content.
 */
@Composable
internal fun MoveToSessionOverlayCapsule(
    visible: Boolean,
    onClick: () -> Unit,
) {
    if (!visible) return

    val density = LocalDensity.current
    val rightInsetPx = with(density) { 12.dp.roundToPx() }
    val topInsetPx = with(density) { 6.dp.roundToPx() }

    Popup(
        popupPositionProvider = remember(rightInsetPx, topInsetPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = (anchorBounds.right - popupContentSize.width - rightInsetPx)
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val y = (anchorBounds.top + topInsetPx).coerceAtLeast(0)
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = {},
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier.clickable(onClick = onClick),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .border(1.dp, MaterialTheme.colorScheme.onSecondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "Move to…",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * [T-android-paste-placeholder] Horizontal chip tray for folded long pastes inside the composer.
 */
@Composable
internal fun PastedTextChipsRow(
    pastedTexts: List<PastedText>,
    inputFieldValue: TextFieldValue,
    onInputFieldValueChange: (TextFieldValue) -> Unit,
    viewModel: ChatViewModel,
) {
    if (pastedTexts.isEmpty()) return

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pastedTexts, key = { it.id }) { pasted ->
            PastedTextChip(
                pasted = pasted,
                onRemove = {
                    val marker = pasted.placeholder
                    val cur = inputFieldValue.text
                    val at = cur.indexOf(marker)
                    if (at >= 0) {
                        val stripped = cur.substring(0, at) + cur.substring(at + marker.length)
                        onInputFieldValueChange(
                            TextFieldValue(text = stripped, selection = TextRange(at)),
                        )
                        viewModel.setInputText(stripped)
                    }
                    viewModel.removePastedText(pasted.id)
                },
            )
        }
    }
}

/**
 * Horizontal tray of pending composer attachments (images, files, staged shares).
 */
@Composable
internal fun AttachmentChipsRow(
    attachments: List<InputAttachment>,
    context: Context,
    viewModel: ChatViewModel,
    onPreviewAttachment: (FileItem) -> Unit,
    onOpenImageGallery: (List<ImageGalleryItem>, Int) -> Unit,
    onOpenWebAppSheet: (InputAttachment) -> Unit,
) {
    if (attachments.isEmpty()) return

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            val isHtmlAttachment = attachment.fileName
                .substringAfterLast('.', "")
                .lowercase()
                .let { it == "html" || it == "htm" }
            var webAppMenuExpanded by remember(attachment.id) { mutableStateOf(false) }

            Box {
                AttachmentChip(
                    attachment = attachment,
                    onRemove = { viewModel.removeAttachment(attachment.id) },
                    // TODO(webapp-hidden): restore `if (isHtmlAttachment)` when WebApp entry point is enabled.
                    onLongClick = if (false && isHtmlAttachment) {
                        { webAppMenuExpanded = true }
                    } else null,
                    onClick = {
                        if (attachment.isImage) {
                            val imageChips = attachments.filter { it.isImage }
                            val startIdx = imageChips.indexOfFirst { it.id == attachment.id }
                                .coerceAtLeast(0)
                            onOpenImageGallery(
                                imageChips.map { ic ->
                                    ImageGalleryItem(model = ic.uri, caption = ic.fileName)
                                },
                                startIdx,
                            )
                        } else {
                            openNonImageAttachment(context, attachment, onPreviewAttachment)
                        }
                    },
                )
                if (false && isHtmlAttachment) {
                    MinisMenu(
                        expanded = webAppMenuExpanded,
                        onDismissRequest = { webAppMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.webapp_add_to_home)) },
                            leadingIcon = { Icon(Icons.Default.AppShortcut, contentDescription = null) },
                            onClick = {
                                webAppMenuExpanded = false
                                onOpenWebAppSheet(attachment)
                            },
                        )
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}

/**
 * T162: Routes `file://` attachments (e.g. staged shares) to the in-app file previewer to avoid
 * `FileUriExposedException` on API 24+, falling back to `Intent.ACTION_VIEW` for `content://` URIs.
 */
private fun openNonImageAttachment(
    context: Context,
    attachment: InputAttachment,
    onPreviewAttachment: (FileItem) -> Unit,
) {
    val uri = attachment.uri
    val asFile = if (uri.scheme == "file") uri.path?.let(::File) else null
    if (asFile != null && asFile.exists()) {
        onPreviewAttachment(
            FileItem(
                file = asFile,
                name = attachment.fileName,
                isDirectory = false,
                isSymlink = false,
                size = asFile.length(),
                modifiedMs = asFile.lastModified(),
            ),
        )
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, attachment.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "No app available to open this attachment.",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
