package dev.inkstone.app.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.TextChip
import dev.inkstone.app.ui.rememberDotGridBrush
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.DavResource
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.SyncAccount

/**
 * SPEC §7.1 and §7.6's UI, in one screen: accounts, the login flow, and the remote
 * folder browser that *"Save to Nextcloud…"* lands in.
 *
 * One screen rather than three because the three are one journey — a user binding their
 * first note signs in, picks a folder and confirms without ever choosing a menu item —
 * and because backing out of any part of it has the same meaning: cancel, change nothing.
 *
 * ⭐ ROADMAP M10 — restyled onto the glass design system. The screen's shape is unchanged: a header,
 * a status line, and then *either* the accounts or the browser. What changed is that each of those
 * became one of the design's two containers — a pill of chips for the things you press, a panel for
 * the things you read — over the canvas and its dot grid, so this screen and the note editor are
 * visibly the same app.
 *
 * ⚠️ **Sync's own wording is untouched.** Colour here is an *addition* to the sentence, never a
 * replacement for it: [Ink.Danger] marks a state that has stopped syncing and needs a person, and
 * every explanation of *why* — above all SPEC §7.2's "signed out by the server, and nothing is
 * retried" (CLAUDE.md rule 5) — is still spelled out in words, because a red label does not tell
 * anybody that retrying would get their tablet blocked by their own server.
 */
@Composable
fun SyncScreen(state: SyncUiState, onBack: () -> Unit, actions: SyncActions, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Ink.Canvas)) {
        // ⭐ M10 — the design's *"Deep Charcoal with a subtle dot-grid pattern"*, as one tiled draw
        // call. See `rememberDotGridBrush` for why it is a shader and not seven thousand circles.
        Box(modifier = Modifier.fillMaxSize().background(rememberDotGridBrush()))

        Column(
            modifier = Modifier.fillMaxSize().padding(InkDims.EdgePadding),
            verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            SyncHeader(state, onBack, actions)

            // Material's own indicator, kept: the design system has no progress element, and
            // `InkstoneTheme` maps `primary` onto Ink.Focus — which is the colour the brief gives
            // live state anyway, so this arrives correct without a call-site override.
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    // Focus while something is actually in flight, muted once it has settled. The
                    // *string* is whatever the view model set; only its register changes.
                    color = if (state.busy) Ink.Focus else Ink.TextNormal,
                    modifier = Modifier.padding(horizontal = InkDims.ChipPaddingH),
                )
            }

            when {
                state.browser != null -> FolderBrowser(state, actions)
                else -> AccountsPane(state, actions)
            }
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
            confirmButton = { TextChip(label = "Cancel", onClick = actions.onCancelLogin) },
        )
    }
}

/**
 * The design's header: one pill, read left to right — where back is, what this screen is, what is
 * owed, and the one button that acts on all of it.
 *
 * ⚠️ No `weight` anywhere in here. [GlassBar] scrolls horizontally by default, which measures its
 * children against an infinite width, and a weighted child in that row does not merely look wrong —
 * it cannot be measured. Ordering the chips is how this bar arranges itself.
 */
@Composable
private fun SyncHeader(state: SyncUiState, onBack: () -> Unit, actions: SyncActions) {
    GlassBar(modifier = Modifier.fillMaxWidth()) {
        TextChip(label = "‹ Back", onClick = onBack)
        ChipDivider()
        MicroLabel("Nextcloud", color = Ink.TextStrong)
        if (state.outboxCount > 0) {
            ChipDivider()
            Readout("${state.outboxCount} waiting to upload", color = Ink.Focus)
        }
        // ⭐ M12 §11 — the audio, counted separately and **with its size**, because the number
        // that matters about a queue of lectures is how much of the tablet it is holding.
        if (state.pendingRecordings > 0) {
            ChipDivider()
            Readout(
                "${state.pendingRecordings} recording(s), ${megabytes(state.pendingRecordingBytes)}",
                color = Ink.Focus,
            )
        }
        ChipDivider()
        TextChip(label = "Sync now", onClick = actions.onSyncNow)
    }
}

@Composable
private fun AccountsPane(state: SyncUiState, actions: SyncActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
    ) {
        for (account in state.accounts) {
            AccountPanel(account, actions)
        }
        AddAccountPanel(state, actions)
    }
}

/**
 * One signed-in (or remembered) account.
 *
 * The heading is coloured rather than reworded — see [tint]. The name stays in the sentence case the
 * server gave it, because [MicroLabel] uppercases and a person's display name is not a legend.
 */
@Composable
private fun AccountPanel(account: SyncAccount, actions: SyncActions) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        val heading = if (account.provider == ProviderKind.ONEDRIVE) "OneDrive" else "Account"
        MicroLabel(heading, color = account.state.tint())
        Text(
            account.displayName ?: account.loginName,
            style = MaterialTheme.typography.titleSmall,
            color = Ink.TextStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // A OneDrive row's "server" is Microsoft's API address, which says nothing a person needs.
        if (account.provider != ProviderKind.ONEDRIVE) Readout(account.serverUrl, color = Ink.TextMuted, maxLines = 2)
        AccountStateLine(account)
        Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
            // NEEDS_REAUTH gets the same one-tap way back as SIGNED_OUT: the card said "sign in
            // again" and offered only Browse and Sign out, neither of which can.
            if (account.state == AccountState.SIGNED_OUT || account.state == AccountState.NEEDS_REAUTH) {
                // No URL to retype: the address is on the row above, and `signInAgain`
                // uses it. The browser approval still happens — SPEC §7.1 is the only way
                // to get an app password — but that is one tap and a confirm, not a
                // keyboard and then a note-by-note re-bind.
                //
                // Inverted (`selected`) because it is the way *out* of this state, which is what the
                // `OutlinedButton` here used to say before the design had only chips to say it with.
                TextChip(
                    label = "Sign in again",
                    selected = true,
                    onClick = { actions.onSignInAgain(account.id) },
                )
                // `danger`, and correctly so: this forgets the account and unbinds its notes with no
                // confirmation in front of it.
                TextChip(label = "Remove", danger = true, onClick = { actions.onForgetAccount(account.id) })
            } else {
                TextChip(label = "Browse folders", onClick = { actions.onChooseAccount(account.id) })
                TextChip(label = "Sign out", onClick = { actions.onSignOut(account.id) })
            }
        }
    }
}

/** What this account's state means, in the words SPEC §7.2 and M6 chose, tinted by [tint]. */
@Composable
private fun AccountStateLine(account: SyncAccount) {
    when (account.state) {
        // SPEC §7.2 / CLAUDE.md rule 5 — this is the *only* thing that happens after
        // a confirmed 401. No retry, no background attempt, just this line.
        AccountState.NEEDS_REAUTH -> StateLine(
            if (account.provider == ProviderKind.ONEDRIVE) {
                if (account.lastError == OneDriveAccounts.MISSING_TOKEN_REASON) {
                    // Nobody refused anything: the saved sign-in is simply not on this device any more.
                    "Signed out — ${OneDriveAccounts.MISSING_TOKEN_REASON} Sign in again to resume syncing; " +
                        "your notes are still linked."
                } else {
                    "Microsoft signed this device out. Sign in again to resume syncing — " +
                        "Inkstone will not retry on its own."
                }
            } else {
                "Signed out by the server. Sign in again to resume syncing — " +
                    "Inkstone will not retry on its own."
            },
            Ink.Danger,
        )

        AccountState.NEEDS_UID -> StateLine(
            "Finishing sign-in the next time the server can be reached.",
            Ink.TextNormal,
        )

        // ⭐ M6 — remembered, not gone. The point of the state is that getting back
        // costs one tap, so the card says so rather than looking like a dead row the
        // user is expected to clean up. Muted rather than red for exactly that reason.
        AccountState.SIGNED_OUT -> StateLine(
            "Signed out. Your notes are still linked to this account — sign in again " +
                "and they pick up where they left off.",
            Ink.TextMuted,
        )

        AccountState.ACTIVE -> account.lastError?.let { StateLine(it, Ink.Danger) }
    }
}

@Composable
private fun StateLine(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun AddAccountPanel(state: SyncUiState, actions: SyncActions) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        MicroLabel("Add an account")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            // Stays an `OutlinedTextField`: the design has no text input of its own, and this one
            // already reads the palette through `InkstoneTheme` (focused border = Ink.Focus).
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = actions.onServerUrlChange,
                singleLine = true,
                label = { Text("Nextcloud address") },
                placeholder = { Text("cloud.example.com") },
                modifier = Modifier.weight(1f),
            )
            TextChip(label = "Sign in", selected = true, enabled = !state.busy, onClick = actions.onStartLogin)
        }
        Text(
            // SPEC §14 and §7.1 rule 4, said once, where it is relevant.
            "Inkstone connects over HTTPS only, and never sees your password — you sign in " +
                "in your browser and Nextcloud issues an app password just for this device.",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextMuted,
        )
        OneDriveSignIn(actions)
    }
}

/**
 * docs/CLOUD-PROVIDERS.md §5 — the second provider, below the first.
 *
 * When this build has no Microsoft client id the button is replaced by the sentence that says so,
 * because a button that opens a page which immediately fails reads as a broken app. The redirect URI
 * is shown either way: it is the one string a person has to paste into the Entra registration, and it
 * depends on the key this APK was signed with, so only the installed app can tell them what it is.
 */
@Composable
private fun OneDriveSignIn(actions: SyncActions) {
    MicroLabel("Or use OneDrive")
    if (actions.oneDriveConfigured) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            TextChip(label = "Sign in to OneDrive", selected = true, onClick = actions.onSignInOneDrive)
        }
        Text(
            "You sign in on Microsoft’s own page; Inkstone never sees your password. Only the " +
                "files Inkstone saves are written, and nothing is ever deleted for good.",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextMuted,
        )
    } else {
        Text(
            "OneDrive needs a Microsoft app registration, and this build was not given one.",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextMuted,
        )
    }
    Readout("Redirect: ${actions.oneDriveRedirect}", color = Ink.TextMuted, maxLines = 3)
}

@Composable
private fun FolderBrowser(state: SyncUiState, actions: SyncActions) {
    val browser = requireNotNull(state.browser)
    var naming by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
    ) {
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            MicroLabel("Folder")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
            ) {
                TextChip(label = "↑ Up", enabled = browser.path.isNotEmpty(), onClick = actions.onGoUp)
                Readout("/" + browser.path, color = Ink.TextStrong, modifier = Modifier.weight(1f))
                TextChip(label = "New folder", onClick = { naming = true })
            }
        }

        BrowserNotices(browser)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
        ) {
            items(browser.folders, key = { it.path }) { folder ->
                FolderRow(folder.name, folder.pending) { actions.onBrowseTo(browser.accountId, folder.path) }
            }
            // Only when the browser is *for* files. Every other flow is choosing a folder, and a
            // list of documents underneath the folders would be a list of things it cannot pick.
            if (browser.pickingPdf) {
                items(browser.pdfs, key = { it.path }) { pdf ->
                    PdfRow(pdf) { actions.onPickPdf(pdf) }
                }
            }
        }

        BrowserConfirmRow(state, actions)
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
 * One remote folder.
 *
 * A plain row on [Ink.PanelSolid] rather than a [TextChip], because a chip uppercases and tracks out
 * its label — which is right for a verb the app chose and wrong for a folder name the *user* chose.
 * `Documents` must not come back as `D O C U M E N T S`.
 *
 * A folder the user may not write to is still shown, and still openable — SPEC §7.3 says to check
 * `oc:permissions` before *offering the action*, which is what the confirm row does.
 */
@Composable
private fun FolderRow(name: String, pending: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkDims.ChipCorner)
            .background(Ink.PanelSolid)
            .clickable(onClick = onClick)
            .padding(horizontal = InkDims.ChipPaddingH, vertical = FolderRowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
    ) {
        Text(
            "📁  $name",
            style = MaterialTheme.typography.bodyLarge,
            color = Ink.TextStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // ⚠️ A folder made with no network is a real destination and is **not on the server
        // yet**. Drawing it exactly like the others would be the picker's one silent lie — the
        // user would go looking for it in Nextcloud Files and not find it.
        if (pending) {
            Text(
                "not created yet",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.TextMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * What this level of the browser has to say for itself.
 *
 * ⚠️ Both lines are *said* rather than left as empty space. A picker showing three folders and
 * nothing else is indistinguishable from one that failed to list, and the user's next move — go
 * up, or give up — depends on which it was.
 */
@Composable
private fun BrowserNotices(browser: RemoteBrowser) {
    // ⭐ Offline. Chrome rather than an error, because everything this browser is for still
    // works: binding, pinning and the default folder all write rows and enqueue MKCOL + UPLOAD
    // without touching the network.
    browser.remembered?.let { remembered ->
        BrowserNotice(
            OfflineBrowsing.notice(
                listedAtEpochMillis = remembered.listedAtEpochMillis,
                folderCount = browser.folders.size,
                nowEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    if (browser.pickingPdf && browser.pdfs.isEmpty()) {
        BrowserNotice(
            if (browser.remembered != null) {
                // The one thing on this screen that genuinely needs a connection: adopting a file
                // is a GET of its bytes. Remembering names would only offer documents that cannot
                // be opened.
                "Files can't be listed without a connection — only folders are remembered."
            } else {
                "No PDFs in this folder. Open one of the folders above to look inside it."
            },
        )
    }
}

/**
 * A line of explanation under the breadcrumb.
 *
 * Muted body text rather than an error colour: every state that reaches this is one the user can
 * carry on working in, and a red line asks them to fix something that is not broken.
 */
@Composable
private fun BrowserNotice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Ink.TextMuted,
        modifier = Modifier.padding(horizontal = InkDims.ChipPaddingH),
    )
}

/**
 * What confirming the folder browser means, which depends entirely on why it was opened.
 *
 * Three cases: pinning a folder sets an ongoing rule, binding a note is a one-off, and neither
 * is a plain visit. "Always save here" appears in the last two because that is where a user who
 * has just navigated somewhere is standing when they realise they want to keep going there.
 *
 * ⭐ M10 — a pill rather than a loose row, and the chip that *is* the reason the browser opened is
 * inverted. Progressive disclosure is already how this reads: the chips a case cannot act on are
 * absent, not greyed.
 */
@Composable
private fun BrowserConfirmRow(state: SyncUiState, actions: SyncActions) {
    GlassBar(modifier = Modifier.fillMaxWidth()) {
        when {
            state.movingNote != null -> {
                TextChip(label = "Move here", selected = true, onClick = actions.onMoveHere)
                TextChip(label = "Cancel", onClick = actions.onCancelBrowse)
            }

            state.pinningFolder != null -> {
                // Worded as the ongoing rule it sets rather than as a one-off save, which is the
                // whole difference from "Save here".
                TextChip(label = "Always sync here", selected = true, onClick = actions.onPinFolderHere)
                TextChip(label = "Cancel", onClick = actions.onCancelBrowse)
            }

            state.pickingPdf -> {
                // No "choose here": what is being chosen is the file under the finger, not the
                // folder in the breadcrumb, so a confirm chip would have nothing to confirm.
                TextChip(label = "Cancel", onClick = actions.onCancelBrowse)
            }

            state.bindingNote != null -> {
                TextChip(label = "Save here", selected = true, onClick = actions.onBindHere)
                TextChip(label = "Always save here", onClick = actions.onSetDefaultFolderHere)
                TextChip(label = "Cancel", onClick = actions.onCancelBrowse)
            }

            else -> {
                TextChip(label = "Always save here", onClick = actions.onSetDefaultFolderHere)
                TextChip(label = "Done", onClick = actions.onCancelBrowse)
            }
        }
    }
}

/**
 * One remote PDF, offered as something to write on.
 *
 * ⚠️ A file the user may not **write** is still listed, and still openable — it simply comes in
 * as an ordinary local note instead of one bound to that file ([RemotePdfPick]). Hiding it would
 * be worse than the restriction: the handout is the thing they were looking for, and a picker that
 * silently omits the file you can see in Nextcloud Files is indistinguishable from a broken
 * listing. The row says which it is; the message after the import says it again.
 */
@Composable
private fun PdfRow(file: DavResource, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkDims.ChipCorner)
            .background(Ink.PanelSolid)
            .clickable(onClick = onClick)
            .padding(horizontal = InkDims.ChipPaddingH, vertical = FolderRowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
    ) {
        Text(
            "\uD83D\uDCC4  " + file.name,
            style = MaterialTheme.typography.bodyLarge,
            color = Ink.TextStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!file.mayWrite()) {
            Text("read only", style = MaterialTheme.typography.bodySmall, color = Ink.TextMuted)
        }
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
            TextChip(label = "Create", selected = true, enabled = name.isNotBlank(), onClick = { onCreate(name) })
        },
        dismissButton = { TextChip(label = "Cancel", onClick = onDismiss) },
    )
}

/**
 * ⭐ M10 — the colour an account's heading is painted in.
 *
 * [Ink.Danger] is reserved for the one state that has *stopped* and needs a person; [Ink.Focus] for
 * an account that is working; [Ink.TextMuted] for one that is idle by the user's own choice. A
 * signed-out account is deliberately **not** red — M6 made it a state you return from in one tap,
 * and painting it as a fault would undo that.
 */
private fun AccountState.tint(): Color = when (this) {
    AccountState.ACTIVE -> Ink.Focus
    AccountState.NEEDS_UID -> Ink.TextNormal
    AccountState.SIGNED_OUT -> Ink.TextMuted
    AccountState.NEEDS_REAUTH -> Ink.Danger
}

/** A parameter object, for the reason `LibraryActions` is one: ten same-typed lambdas. */
data class SyncActions(
    val onServerUrlChange: (String) -> Unit,
    val onStartLogin: () -> Unit,
    val onCancelLogin: () -> Unit,
    val onSignOut: (String) -> Unit,
    /** M6 — sign back into a remembered account without retyping its address. */
    val onSignInAgain: (String) -> Unit,
    /** M6 — the irreversible one: forget the account and unbind its notes. */
    val onForgetAccount: (String) -> Unit,
    val onChooseAccount: (String) -> Unit,
    val onBrowseTo: (String, String) -> Unit,
    val onGoUp: () -> Unit,
    val onCreateFolder: (String) -> Unit,
    /** M6 — make the folder being browsed this account's default for new notes. */
    val onSetDefaultFolderHere: () -> Unit,
    val onBindHere: () -> Unit,
    /** Confirm a relocation of a synced note (a MOVE). */
    val onMoveHere: () -> Unit,
    /** M6 — confirm a folder pin (SPEC §9's mirror). */
    val onPinFolderHere: () -> Unit,
    /** Write on a PDF that is already on the server. Tapping the file *is* the confirmation. */
    val onPickPdf: (DavResource) -> Unit,
    val onCancelBrowse: () -> Unit,
    val onSyncNow: () -> Unit,
    /** docs/CLOUD-PROVIDERS.md §5 — sign in to OneDrive in a Custom Tab. */
    val onSignInOneDrive: () -> Unit,
    /** False when this build has no Microsoft client id. Fixed for the install, so it is not in the UI state. */
    val oneDriveConfigured: Boolean,
    /** The `msauth://` redirect this install must be registered under. */
    val oneDriveRedirect: String,
)

/** Whole megabytes. A lecture is tens of them, so a decimal place would be noise. */
private fun megabytes(bytes: Long): String = "${bytes / BYTES_PER_MEGABYTE} MB"

/** Taller than a chip, because a list line is scrolled past rather than aimed at. */
private val FolderRowPaddingV = 12.dp

private const val BYTES_PER_MEGABYTE = 1024L * 1024
