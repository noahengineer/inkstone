package dev.inkstone.app.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.SyncAccount

/**
 * SPEC §7.1 and §7.6's UI, in one screen: accounts, the login flow, and the remote
 * folder browser that *"Save to Nextcloud…"* lands in.
 *
 * One screen rather than three because the three are one journey — a user binding their
 * first note signs in, picks a folder and confirms without ever choosing a menu item —
 * and because backing out of any part of it has the same meaning: cancel, change nothing.
 */
@Composable
fun SyncScreen(state: SyncUiState, onBack: () -> Unit, actions: SyncActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Nextcloud", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.outboxCount > 0) {
                Text(
                    "${state.outboxCount} waiting to upload",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(onClick = actions.onSyncNow) { Text("Sync now") }
        }
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        HorizontalDivider()

        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when {
            state.browser != null -> FolderBrowser(state, actions)
            else -> AccountsPane(state, actions)
        }
    }

    // SPEC §7.1: the poll runs while the user is in the browser, for up to 1200 s. The
    // dialog is what makes cancelling possible — and cancelling is one of only three ways
    // out, because a 404 cannot be distinguished from "expired" (rule 2).
    state.pendingLogin?.let {
        AlertDialog(
            onDismissRequest = actions.onCancelLogin,
            title = { Text("Waiting for approval") },
            text = {
                Text(
                    "Approve Inkstone in the browser that just opened. It will appear in your " +
                        "Nextcloud security settings as “Inkstone/1.0 (Android)”, where you can " +
                        "revoke it at any time.",
                )
            },
            confirmButton = { TextButton(onClick = actions.onCancelLogin) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccountsPane(state: SyncUiState, actions: SyncActions) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (account in state.accounts) {
            AccountCard(account, actions)
        }

        Text("Add an account", style = MaterialTheme.typography.titleSmall)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = actions.onServerUrlChange,
                singleLine = true,
                label = { Text("Nextcloud address") },
                placeholder = { Text("cloud.example.com") },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = actions.onStartLogin, enabled = !state.busy) { Text("Sign in") }
        }
        Text(
            // SPEC §14 and §7.1 rule 4, said once, where it is relevant.
            "Inkstone connects over HTTPS only, and never sees your password — you sign in " +
                "in your browser and Nextcloud issues an app password just for this device.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AccountCard(account: SyncAccount, actions: SyncActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(account.displayName ?: account.loginName, style = MaterialTheme.typography.titleSmall)
            Text(account.serverUrl, style = MaterialTheme.typography.bodySmall)
            when (account.state) {
                // SPEC §7.2 / CLAUDE.md rule 5 — this is the *only* thing that happens after
                // a confirmed 401. No retry, no background attempt, just this line.
                AccountState.NEEDS_REAUTH -> Text(
                    "Signed out by the server. Sign in again to resume syncing — " +
                        "Inkstone will not retry on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                AccountState.NEEDS_UID -> Text(
                    "Finishing sign-in the next time the server can be reached.",
                    style = MaterialTheme.typography.bodySmall,
                )

                AccountState.ACTIVE -> account.lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { actions.onChooseAccount(account.id) }) { Text("Browse folders") }
                TextButton(onClick = { actions.onSignOut(account.id) }) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun FolderBrowser(state: SyncUiState, actions: SyncActions) {
    val browser = requireNotNull(state.browser)
    var naming by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = actions.onGoUp, enabled = browser.path.isNotEmpty()) { Text("↑ Up") }
            Text(
                "/" + browser.path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { naming = true }) { Text("New folder") }
        }

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(browser.folders, key = { it.path }) { folder ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A folder the user may not write to is still shown, and still
                        // openable — SPEC §7.3 says to check `oc:permissions` before
                        // *offering the action*, which is what the confirm button does.
                        .clickable { actions.onBrowseTo(browser.accountId, folder.path) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("📁  ${folder.name}", style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
            }
        }

        if (state.bindingNote != null) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onBindHere) { Text("Save here") }
                TextButton(onClick = actions.onCancelBrowse) { Text("Cancel") }
            }
        } else {
            Row(modifier = Modifier.padding(16.dp)) {
                TextButton(onClick = actions.onCancelBrowse) { Text("Done") }
            }
        }
    }

    if (naming) {
        NewFolderDialog(
            onDismiss = { naming = false },
            onCreate = { name ->
                actions.onCreateFolder(name)
                naming = false
            },
        )
    }
}

/**
 * The name goes through [dev.inkstone.core.model.RemoteName] on the way out — the user is
 * shown the name they will actually get rather than finding it changed later.
 */
@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A parameter object, for the reason `LibraryActions` is one: ten same-typed lambdas. */
data class SyncActions(
    val onServerUrlChange: (String) -> Unit,
    val onStartLogin: () -> Unit,
    val onCancelLogin: () -> Unit,
    val onSignOut: (String) -> Unit,
    val onChooseAccount: (String) -> Unit,
    val onBrowseTo: (String, String) -> Unit,
    val onGoUp: () -> Unit,
    val onCreateFolder: (String) -> Unit,
    val onBindHere: () -> Unit,
    val onCancelBrowse: () -> Unit,
    val onSyncNow: () -> Unit,
)
