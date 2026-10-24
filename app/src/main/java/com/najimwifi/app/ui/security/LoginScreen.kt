package com.najimwifi.app.ui.security

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.najimwifi.app.security.LockPolicy

/** Lock gate and temporary security controls inside the existing Settings tab. */
@Composable
fun LoginScreen(
    viewModel: SecurityViewModel,
    modifier: Modifier = Modifier,
    embedded: Boolean = false
) {
    val state by viewModel.state.collectAsState()
    var username by remember(state.username, state.locked) {
        mutableStateOf(state.username)
    }
    // Never save a password with rememberSaveable or persistent UI state.
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newUsername by remember(state.username) { mutableStateOf(state.username) }
    var policy by remember(state.policy) {
        mutableStateOf(if (state.policy == LockPolicy.OFF) LockPolicy.IMMEDIATE else state.policy)
    }
    var policyExpanded by remember { mutableStateOf(false) }
    var updatePolicySelection by remember(state.policy) { mutableStateOf(state.policy) }
    var updatePolicyExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            password = ""
            confirm = ""
            currentPassword = ""
        }
    }

    Column(
        modifier = modifier.then(if (embedded) Modifier.fillMaxWidth()
            else Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            .padding(if (embedded) 2.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (state.locked) "فتح قفل إداره مبيعات الشبكة" else "حماية التطبيق",
            style = MaterialTheme.typography.headlineSmall
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        when {
            state.locked -> {
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("اسم المستخدم") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
                    colors = softOutlinedTextFieldColors()
)
                SecretField("كلمة المرور", password) { password = it }
                Button(
                    onClick = { viewModel.unlock(username, password) },
                    enabled = !state.busy && username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) {
                    Text(if (state.busy) "جارٍ التحقق..." else "فتح القفل")
                }
            }

            !state.configured -> {
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("اسم المستخدم الجديد") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
                    colors = softOutlinedTextFieldColors()
)
                SecretField("كلمة مرور جديدة", password) {
                    password = it
                }
                SecretField("تأكيد كلمة المرور", confirm) { confirm = it }
                OutlinedButton(
                    onClick = { policyExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, Modifier.size(20.dp))
                        Text("  القفل: ${policy.label}", modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                    }
                }
                DropdownMenu(
                    expanded = policyExpanded,
                    onDismissRequest = { policyExpanded = false }
                ) {
                    LockPolicy.entries.filter { it.isEnabled }.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                            policy = choice
                            policyExpanded = false
                        })
                    }
                }
                Button(
                    onClick = { viewModel.activate(username, password, confirm, policy) },
                    enabled = !state.busy && username.isNotBlank() &&
                        password.isNotEmpty() && confirm.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) { Text("تفعيل تسجيل الدخول المحلي") }
            }

            else -> {
                Text("اسم المستخدم الحالي: ${state.username}")
                Text("إعداد القفل الحالي: ${state.policy.label}")
                if (state.policy.isEnabled) {
                    OutlinedButton(
                        onClick = viewModel::lockNow,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) { Text("قفل التطبيق الآن") }
                }

                Card(modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text("تغيير مدة القفل أو إيقافه", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(
                            onClick = { updatePolicyExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Schedule, null, Modifier.size(20.dp))
                                Text("  ${updatePolicySelection.label}", modifier = Modifier.weight(1f))
                                Icon(Icons.Rounded.ArrowDropDown, null)
                            }
                        }
                        DropdownMenu(
                            expanded = updatePolicyExpanded,
                            onDismissRequest = { updatePolicyExpanded = false }
                        ) {
                            LockPolicy.entries.forEach { choice ->
                                DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                    updatePolicySelection = choice
                                    updatePolicyExpanded = false
                                })
                            }
                        }
                        SecretField("كلمة المرور الحالية", currentPassword) {
                            currentPassword = it
                        }
                        Button(
                            onClick = {
                                viewModel.updatePolicy(currentPassword, updatePolicySelection)
                            },
                            enabled = !state.busy && currentPassword.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) { Text("حفظ إعداد القفل") }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text("تحديث اسم المستخدم وكلمة المرور", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = newUsername,
                            onValueChange = { newUsername = it },
                            label = { Text("اسم المستخدم الجديد") },
                            leadingIcon = { Icon(Icons.Rounded.Person, null) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedCornerShape(16.dp),
                            colors = softOutlinedTextFieldColors()
)
                        SecretField("كلمة مرور جديدة", password) { password = it }
                        SecretField("تأكيد كلمة المرور الجديدة", confirm) { confirm = it }
                        SecretField("كلمة المرور الحالية", currentPassword) {
                            currentPassword = it
                        }
                        Button(
                            onClick = {
                                viewModel.updateCredentials(
                                    currentPassword, newUsername, password, confirm
                                )
                            },
                            enabled = !state.busy && currentPassword.isNotEmpty() &&
                                password.isNotEmpty() && confirm.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) { Text("تحديث بيانات الدخول") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    onChange: (String) -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = { Icon(if ("استرداد" in label) Icons.Rounded.Key else Icons.Rounded.Lock,
            contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور")
            }
        },
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = softOutlinedTextFieldColors()
)
}