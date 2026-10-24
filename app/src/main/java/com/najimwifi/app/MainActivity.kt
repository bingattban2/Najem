package com.najimwifi.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.najimwifi.app.backup.LegacyEncryptionCutover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private sealed interface Startup {
        data object Checking : Startup
        data object Ready : Startup
        data class Blocked(val explanation: String) : Startup
    }

    private var startup by mutableStateOf<Startup>(Startup.Checking)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A normal launch must not wait for database migration checks or a
        // second Compose frame. Only a previously armed migration is gated.
        // Never open Room when the pending marker exists.
        val requiresRecovery = LegacyEncryptionCutover.hasScheduledCutover(applicationContext)
        startup = if (requiresRecovery) Startup.Checking else Startup.Ready
        setContent {
            LaunchedEffect(requiresRecovery) {
                if (!requiresRecovery) return@LaunchedEffect
                startup = try {
                    val outcome = withContext(Dispatchers.IO) {
                        val conversion = LegacyEncryptionCutover(applicationContext)
                        val result = conversion.performOnColdStart()
                        result to conversion.hasPendingRequest()
                    }
                    if (outcome.second) {
                        Startup.Blocked(
                            outcome.first.note ?: "التحويل لم يكتمل؛ أغلق التطبيق كاملًا وأعد فتحه"
                        )
                    } else {
                        outcome.first.note?.let {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                        }
                        Startup.Ready
                    }
                } catch (failure: Exception) {
                    Startup.Blocked(failure.message ?: "تعذر التحقق من حالة ترحيل البيانات")
                }
            }
            when (val result = startup) {
                Startup.Checking -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Text("التحقق من سلامة قاعدة البيانات…")
                }
                Startup.Ready -> NajimWifiApp()
                is Startup.Blocked -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("تم إيقاف فتح البيانات لحمايتها", style = MaterialTheme.typography.titleLarge)
                    Text(result.explanation)
                    Text("لا تحذف بيانات التطبيق. أعد تشغيله بعد معالجة سبب التوقف.")
                }
            }
        }
    }
}
