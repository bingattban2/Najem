package com.najimwifi.app.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najimwifi.app.ui.theme.StitchTag
import kotlinx.coroutines.launch

private data class WelcomePage(val icon: String, val title: String, val description: String)
private val pages = listOf(
    WelcomePage("◈", "نظّم مبيعاتك", "تتبع حركة بيع كروت الواي فاي بكل سهولة وتعرف على الكروت الأكثر مبيعاً في شبكتك."),
    WelcomePage("▣", "أدِر نقاط البيع", "راقب حسابات المحلات والموزعين بدقة، وتعرف على إيراداتك ومستحقاتك بضغطة زر."),
    WelcomePage("↗", "ضاعف أرباحك", "احصل على تقارير وإحصائيات دقيقة تساعدك على اتخاذ القرارات الصحيحة لنمو شبكتك. ابدأ الآن!")
)

@Composable
fun WelcomeScreen(busy: Boolean, onFinish: () -> Unit) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            StitchTag("إدارة مبيعات الشبكة")
            Text("${pager.currentPage + 1} / ${pages.size}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge)
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            val page = pages[index]
            Column(Modifier.fillMaxSize().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(178.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(page.icon, fontSize = 88.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(32.dp))
                Text(page.title, style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text(page.description, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(pages.size) { index ->
                Box(Modifier.size(if (index == pager.currentPage) 12.dp else 8.dp)
                    .background(if (index == pager.currentPage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant, CircleShape))
            }
        }
        Button(
            onClick = {
                if (pager.currentPage == pages.lastIndex) onFinish()
                else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(19.dp)
        ) { Text(if (pager.currentPage == pages.lastIndex) "ابدأ الآن" else "التالي") }
        if (pager.currentPage < pages.lastIndex) {
            OutlinedButton(onClick = onFinish, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(19.dp)) { Text("تخطي المقدمة") }
        }
    }
}
