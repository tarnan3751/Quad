package com.campusmarket.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
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
import com.campusmarket.app.ui.components.CodeField
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.EmailField
import com.campusmarket.app.ui.components.FormColumn
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PasswordField
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.QuadAppIcon
import com.campusmarket.app.ui.components.QuadMark
import com.campusmarket.app.ui.components.QuadWordmark
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.ServerSettingsDialog
import com.campusmarket.app.ui.components.formSubmit
import com.campusmarket.app.ui.components.onFocusLost
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.Limits
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private val FormMaxWidth = 480.dp

// ---------------------------------------------------------------------------------------------------- welcome

@Composable
fun WelcomeScreen(nav: NavController) {
    val session: SessionManager = koinInject()
    val server: ServerConfig = koinInject()
    val signOutReason by session.signOutReason.collectAsStateWithLifecycle()
    val baseUrl by server.baseUrl.collectAsStateWithLifecycle()
    var showServer by remember { mutableStateOf(false) }

    val actions: @Composable ColumnScope.() -> Unit = {
        signOutReason?.let { SignOutNotice(it, Modifier.widthIn(max = FormMaxWidth).fillMaxWidth()) }
        PrimaryButton(
            "Create account",
            onClick = { nav.navigate(RegisterRoute) },
            modifier = Modifier.widthIn(max = FormMaxWidth).fillMaxWidth(),
        )
        SecondaryButton(
            "Sign in",
            onClick = { nav.navigate(LoginRoute()) },
            modifier = Modifier.widthIn(max = FormMaxWidth).fillMaxWidth(),
        )
        Text(
            "Only verified campus (.edu) emails can join.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Debug builds only (release ignores overrides): which server this build talks to, and a way to change it.
        if (server.canOverride) {
            TextButton(
                onClick = { showServer = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text("Dev server · ${baseUrl.substringAfter("://")}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    // The hero's colour runs to the screen edge, under a display cutout or a side navigation bar; what's on it stays clear.
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    // Fills the screen, but scrolls on short ones (landscape, large text, a sign-out notice) instead of squashing.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (maxHeight < 480.dp && maxWidth > maxHeight) {
            // A phone in landscape: the hero beside the buttons instead of pushing them off the screen. It starts
            // below the status bar, whose other half is over the plain background.
            Row(Modifier.fillMaxSize().statusBarsPadding()) {
                WelcomeHero(
                    compact = true,
                    shape = RoundedCornerShape(topEnd = 36.dp, bottomEnd = 36.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentModifier = Modifier.windowInsetsPadding(sides.only(WindowInsetsSides.Start)).navigationBarsPadding(),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(sides.only(WindowInsetsSides.End))
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
                    content = actions,
                )
            }
        } else {
            // The hero's sky is amber at the top in both themes, so the status-bar icons over it are dark.
            StatusBarIcons(darkIcons = true)
            // On a short screen the hero gets a fixed, smaller size and the page scrolls; otherwise it takes what's left.
            val compact = maxHeight < 600.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
                WelcomeHero(
                    compact = compact,
                    shape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp),
                    modifier = Modifier.fillMaxWidth().then(if (compact) Modifier.height(280.dp) else Modifier.weight(1f)),
                    contentModifier = Modifier.statusBarsPadding().windowInsetsPadding(sides),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(sides)
                        .navigationBarsPadding()
                        .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xl, bottom = Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    content = actions,
                )
            }
        }
    }
    if (showServer) ServerSettingsDialog(onDismiss = { showServer = false })
}

/**
 * Golden Hour hero: the mark in the sky, the wordmark and tagline on the berry "horizon" (white type stays on the
 * darker half of the gradient).
 */
@Composable
private fun WelcomeHero(compact: Boolean, shape: Shape, modifier: Modifier, contentModifier: Modifier) {
    Column(
        modifier
            .background(CampusTheme.heroGradient, shape)
            .then(contentModifier)
            .padding(horizontal = Spacing.xl, vertical = if (compact) Spacing.lg else Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        QuadMark(size = if (compact) 64.dp else 120.dp, modifier = Modifier.semantics { contentDescription = "Quad" })
        Spacer(Modifier.weight(1f))
        QuadWordmark(fontSize = if (compact) 48.sp else 64.sp)
        Text(
            "Buy and sell with people on your campus",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 272.dp).padding(top = Spacing.xs),
        )
    }
}

// ---------------------------------------------------------------------------------------------------- register

@Composable
fun RegisterScreen(nav: NavController) {
    val vm = koinViewModel<RegisterViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.events.flow) { nav.navigate(VerifyEmailRoute(it.email, it.devCode)) }

    AuthFormScaffold(onBack = { nav.popBackStack() }) {
        AuthHeader("Join Quad", "Use your school email. We'll send you a code to confirm it's you.")
        EmailField(
            value = state.email,
            onValueChange = vm::onEmail,
            error = state.fieldErrors["email"],
            supportingText = "Your school address, ending in .edu",
            modifier = Modifier.onFocusLost(vm::checkEmail),
        )
        AppTextField(
            value = state.displayName,
            onValueChange = vm::onDisplayName,
            label = "Display name",
            error = state.fieldErrors["displayName"],
            supportingText = "Shown to buyers and sellers",
            maxLength = Limits.DISPLAY_NAME_MAX,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            contentType = ContentType.PersonFullName,
            modifier = Modifier.onFocusLost(vm::checkDisplayName),
        )
        PasswordField(
            value = state.password,
            onValueChange = vm::onPassword,
            error = state.fieldErrors["password"],
            supportingText = "At least ${Limits.PASSWORD_MIN} characters",
            onImeAction = vm::submit,
            newPassword = true,
            modifier = Modifier.onFocusLost(vm::checkPassword),
        )
        InlineError(state.formError, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.xs))
        PrimaryButton("Continue", onClick = vm::submit, loading = state.submitting, modifier = Modifier.fillMaxWidth().formSubmit())
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
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { codeFocus.requestFocus() } }

    AuthFormScaffold(onBack = { nav.popBackStack() }) {
        AuthHeader("Check your email") {
            Text(
                buildAnnotatedString {
                    append("Enter the ${Limits.VERIFICATION_CODE_LENGTH}-digit code we sent to ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) { append(state.email) }
                    append(". It expires in 15 minutes.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CodeField(
            value = state.code,
            onValueChange = vm::onCode,
            length = Limits.VERIFICATION_CODE_LENGTH,
            error = state.fieldErrors["code"],
            onDone = vm::submit,
            modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
        )
        state.devCode?.let { DevCodeHint(it, onUse = vm::useDevCode) }
        state.info?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = CampusTheme.colors.success)
        }
        InlineError(state.formError, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.xs))
        PrimaryButton("Verify", onClick = vm::submit, loading = state.submitting, modifier = Modifier.fillMaxWidth().formSubmit())
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = vm::resend, enabled = !state.resending && state.resendCooldown == 0) {
                Text(
                    when {
                        state.resending -> "Sending…"
                        state.resendCooldown > 0 -> "Resend code in ${state.resendCooldown}s"
                        else -> "Didn't get it? Resend code"
                    },
                )
            }
            TextButton(
                onClick = { nav.popBackStack() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) { Text("Use a different email") }
        }
    }
}

/** Dev servers return the code in the response; a quiet hint offers it so nobody has to read the server log. */
@Composable
private fun DevCodeHint(code: String, onUse: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(start = Spacing.md, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append("Dev code ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface, letterSpacing = 1.sp)) { append(code) }
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUse) { Text("Use code") }
    }
}

// ---------------------------------------------------------------------------------------------------- login

@Composable
fun LoginScreen(route: LoginRoute, nav: NavController) {
    val vm = koinViewModel<LoginViewModel> { parametersOf(route) }
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.events.flow) { nav.navigate(VerifyEmailRoute(it.email, it.devCode)) }

    AuthFormScaffold(onBack = { nav.popBackStack() }) {
        AuthHeader("Welcome back", "Sign in with your campus email.")
        EmailField(
            value = state.email,
            onValueChange = vm::onEmail,
            error = state.fieldErrors["email"],
            modifier = Modifier.onFocusLost(vm::checkEmail),
        )
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
        Spacer(Modifier.height(Spacing.xs))
        PrimaryButton("Sign in", onClick = vm::submit, loading = state.submitting, modifier = Modifier.fillMaxWidth().formSubmit())
        TextButton(
            onClick = { nav.navigate(RegisterRoute) { popUpTo(WelcomeRoute) } },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("New to Quad? Create an account") }
    }
}

// ---------------------------------------------------------------------------------------------------- shared

/**
 * Scrollable, keyboard-aware, width-capped form column under a bare back bar (the screen's headline is the title),
 * headed by the Quad app icon.
 */
@Composable
private fun AuthFormScaffold(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    Scaffold(
        topBar = { AppTopBar("", onBack = onBack) },
        modifier = Modifier.background(MaterialTheme.colorScheme.background).windowInsetsPadding(sides),
    ) { padding ->
        FormColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = FormMaxWidth).fillMaxWidth().padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                QuadAppIcon(size = 48.dp, modifier = Modifier.padding(bottom = Spacing.sm))
                content()
            }
        }
    }
}

/** The form's title (a heading for screen readers) and a short line under it. */
@Composable
private fun AuthHeader(title: String, subtitle: String) = AuthHeader(title) {
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AuthHeader(title: String, subtitle: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        subtitle()
    }
}
