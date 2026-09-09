package com.assignmate.app.core.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.assignmate.app.core.ui.theme.AssignMateTheme

/**
 * 儿童主题大尺寸按钮：全宽 + 高 56dp + 大圆角 + 加粗大字号，适合主操作入口
 * （开始计时、保存作业、登录提交等），兼顾触达面积与趣味性。
 *
 * @param containerColor 可覆写容器色（默认主色），如需要暖色强调可传 secondary
 */
@Composable
fun AssignMateBigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AssignMateBigButtonPreview() {
    AssignMateTheme {
        AssignMateBigButton(text = "开始专注", onClick = {})
    }
}
