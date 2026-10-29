package my.fouriders.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RidersBg = Color(0xFF0C1110)
private val RidersInk = Color(0xFFF4F7F5)
private val RidersMuted = Color(0xFF8E9B95)
private val RidersGreen = Color(0xFF65E6A2)

@Composable
fun RidersHeader(title: String, subtitle: String, status: String, statusColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = RidersInk, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
            Text(subtitle, color = RidersMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Surface(
            color = RidersBg,
            contentColor = statusColor,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, statusColor)
        ) {
            Text(
                text = "●  $status",
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                color = statusColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}
