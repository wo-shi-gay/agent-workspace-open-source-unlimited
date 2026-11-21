package com.agentpad.workspace.floating

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ui.AColors

/** Small-window input: text plus send/stop only. Uses the window's adaptive density. */
@Composable
fun CompactFloatingComposer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val actionable = busy || value.isNotBlank()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
            color = AColors.Surface, border = BorderStroke(1.dp, AColors.Line)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 80.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                textStyle = TextStyle(color = AColors.Ink, fontSize = 14.sp, lineHeight = 19.sp),
                cursorBrush = SolidColor(AColors.Accent),
                minLines = 1,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!busy && value.isNotBlank()) onSend() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text("输入消息…", color = AColors.Muted, fontSize = 13.sp)
                        inner()
                    }
                },
            )
        }
        Box(Modifier.size(32.dp).clip(CircleShape)
            .background(if (busy) AColors.Danger else if (actionable) AColors.Accent else AColors.Line)
            .semantics { contentDescription = if (busy) "停止生成" else "发送消息" }
            .clickable(enabled = actionable) { if (busy) onStop() else onSend() },
            contentAlignment = Alignment.Center) {
            if (busy) Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(androidx.compose.ui.graphics.Color.White))
            else Icon(Icons.Outlined.ArrowUpward, null, tint = if (actionable) androidx.compose.ui.graphics.Color.White else AColors.Muted,
                modifier = Modifier.size(18.dp))
        }
    }
}
