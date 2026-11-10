package com.campusmarket.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.platform.StatusBarIcons
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.EmailField
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PasswordField
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.QuadAppIcon
import com.campusmarket.app.ui.components.QuadMark
import com.campusmarket.app.ui.components.QuadWordmark
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.ServerSettingsDialog
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.Limits
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------------------------------------------- welcome

@Composable
fun WelcomeScreen(nav: NavController) {
    val session: SessionManager = koinInject()
    val server: ServerConfig = koinInject()
    val signOutReason by session.signOutReason.collectAsStateWithLifecycle()
    val baseUrl by server.baseUrl.collectAsStateWithLifecycle()
    var showServer by remember { mutableStateOf(false) }

    // The hero's sky is amber at the top in both themes, so the status-bar icons over it are dark.
    StatusBarIcons(darkIcons = true)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Golden Hour hero: the mark in the sky, the wordmark and tagline on the berry "horizon" (white type stays
        // on the darker half of the gradient).
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(CampusTheme.heroGradient, RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .statusBarsPadding()
                .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            QuadMark(size = 120.dp, modifier = Modifier.semantics { contentDescription = "Quad" })
            Spacer(Modifier.weight(1f))
            QuadWordmark(fontSize = 64.sp)
            Text(
                "Buy and sell with people on your campus",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 272.dp).padding(top = Spacing.xs),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            signOutReason?.let { SignOutNotice(it, Modifier.fillMaxWidth().widthIn(max = 480.dp)) }
            PrimaryButton(
                "Create account",
                onClick = { nav.navigate(RegisterRoute) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
            SecondaryButton(
                "Sign in",
                onClick = { nav.navigate(LoginRoute()) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
            Text(
                "Only verified campus (.edu) emails can join.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (server.canOverride) {
                TextButton(onClick = { showServer = true }) {
                    Text("Server: $baseUrl", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (showServer) ServerSettingsDialog(onDismiss = { showServer = false })
}

// ---------------------------------------------------------------------------------------------------- register

@Composable
fun RegisterScreen(nav: NavController) {
    val vm = koinViewModel<RegisterViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.events.flow) { nav.navigate(VerifyEmailRoute(it.email, it.devCode)) }

    AuthFormScaffold(title = "Create account", onBack = { nav.popBackStack() }) {
        Text("Join Quad", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Use your school email. We'll send a code to confirm it's you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        EmailField(
            value = state.email,
            onValueChange = vm::onEmail,
            error = state.fieldErrors["email"],
            supportingText = "Must be your school address (e.g. ends in .edu)",
        )
        AppTextField(
            value = state.displayName,
            onValueChange = vm::onDisplayName,
            label = "Display name",
            error = state.fieldErrors["displayName"],
            supportingText = "Shown to buyers and sellers",
            maxLength = Limits.DISPLAY_NAME_MAX,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        PasswordField(
            value = state.password,
            onValueChange = vm::onPassword,
            error = state.fieldErrors["password"],
            supportingText = "At least ${Limits.PASSWORD_MIN} characters",
            onImeAction = vm::submit,
        )
        InlineError(state.formError, Modifier.fillMaxWidth())
        PrimaryButton("Continue", onClick = vm::submit, loading = state.submitting, modifier = Modifier.fillMaxWidth())
        LegalFooter()
        TextButton(
            onClick = { nav.navigate(LoginRoute(state.email.ifBlank { null })) { popUpTo(WelcomeRoute) } },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("Already have an account? Sign in") }
    }
}

/** "By creating an account you agree to the Terms and Community Guidelines", both tappable (open in the browser). */
@Composable
private fun LegalFooter() {
    val links: ExternalLinks = koinInject()
    val server: ServerConfig = koinInject()
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val text = buildAnnotatedString {
        append("By creating an account you agree to the ")
        withLink(LinkAnnotation.Clickable("terms", linkStyle) { links.open(server.url(ApiRoutes.LEGAL_TERMS)) }) { append("Terms") }
        append(" and ")
        withLink(LinkAnnotation.Clickable("guidelines", linkStyle) { links.open(server.url(ApiRoutes.LEGAL_GUIDELINES)) }) { append("Community Guidelines") }
        append(".")
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------------------------------- verify

@Composable
fun VerifyEmailScreen(route: VerifyEmailRoute, nav: NavController) {
    val vm = koinViewModel<VerifyEmailViewModel>(key = "verify:${route.email}") { parametersOf(route) }
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.events.flow) {
        nav.navigate(LoginRoute(it.email, it.notice)) { popUpTo(WelcomeRoute) }
    }

    AuthFormScaffold(title = "Verify email", onBack = { nav.popBackStack() }) {
        Text("Check your inbox", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Enter the ${Limits.VERIFICATION_CODE_LENGTH}-digit code we sent to ${state.email}. It expires in 15 minutes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.devCode?.let { code ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dev mode", style = MaterialTheme.typography.labelMedium)
                        Text(code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = vm::useDevCode) { Text("Use code") }
                }
            }
        }
        AppTextField(
            value = state.code,
            onValueChange = vm::onCode,
            label = "Verification code",
            error = state.fieldErrors["code"],
            placeholder = "123456",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.submit() }),
        )
        state.info?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        InlineError(state.formError, Modifier.fillMaxWidth())
        PrimaryButton("Verify", onClick = vm::submit, loading = state.submitting, modifier = Modifier.fillMaxWidth())
        TextButton(
            onClick = vm::resend,
            enabled = !state.resending && state.resendCooldown == 0,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                when {
                    state.resending -> "Sending…"
                    state.resendCooldown > 0 -> "Resend code in ${state.resendCooldown}s"
                    else -> "Resend code"
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------- login

@Composable
fun LoginScreen(route: LoginRoute, nav: NavController) {
    val vm = koinViewModel<LoginViewModel> { parametersOf(route) }
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.events.flow) { nav.navigate(VerifyEmailRoute(it.email, it.devCode)) }

    AuthFormScaffold(title = "Sign in", onBack = { nav.popBackStack() }) {
        Text("Welcome back", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.sm))
        EmailField(value = state.email, onValueChange = vm::onEmail, error = state.fieldErrors["email"])
        PasswordField(
            value = state.password,
            onValueChange = vm::onPassword,
            error = state.fieldErrors["password"],
            onImeAction = vm::submit,
        )
        InlineError(state.formError, Modifier.fillMaxWidth())
        state.suspended?.let { suspended ->
            TextButton(onClick = vm::showSuspendedDialog, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Delete my account") }
            if (state.showSuspended) SuspendedAccountDialog(suspended, state.email, onDismiss = vm::dismissSuspended)
        }
        PrimaryButton("Sign in", onClick = vm::submit, loading = state.submitting, modifier = Modifier.fillMaxWidth())
        TextButton(
            onClick = { nav.navigate(RegisterRoute) { popUpTo(WelcomeRoute) } },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("New here? Create an account") }
    }
}

// ---------------------------------------------------------------------------------------------------- shared

/** Scrollable, keyboard-aware, width-capped form column with a back top bar, headed by the Quad app icon. */
@Composable
private fun AuthFormScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { AppTopBar(title, onBack = onBack) }) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                QuadAppIcon(size = 48.dp, modifier = Modifier.padding(bottom = Spacing.xs))
                content()
            }
        }
    }
}
