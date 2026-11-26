package com.openminis.app.ui.chat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun RenderMarkdownCodeBlock(block: MdBlock.CodeBlock, colors: MdColors) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    val codeShape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp)
            .background(colors.codeBg, codeShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), codeShape)
            .clip(codeShape),
    ) {
        // M3 Expressive code card header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.045f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.ifEmpty { "code" },
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = MdCodeLangColor,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
                    .clip(CircleShape)
                    .clickable {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(block.code))
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy code",
                    tint = if (copied) Color(0xFF10B981) else Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = if (copied) "Copied" else "Copy",
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (copied) Color(0xFF10B981) else Color.White.copy(alpha = 0.72f),
                )
            }
        }
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(vScroll),
        ) {
            Box(
                modifier = Modifier
                    .horizontalScroll(hScroll)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = block.code,
                    fontSize = BaseFontSize * 0.85f,
                    fontFamily = FontFamily.Monospace,
                    color = colors.codeText,
                    lineHeight = BaseLineHeight * 0.92f,
                )
            }
        }
    }
}
