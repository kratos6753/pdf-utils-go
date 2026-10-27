package site.chirudev.pdfutils.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object HistoryRoute
@Serializable data object AccountRoute

/** [fileIds]: comma-separated server file ids to chain in; [incoming]: take files shared into the app. */
@Serializable data class ToolRoute(val slug: String, val fileIds: String? = null, val incoming: Boolean = false)

@Serializable data object LoginRoute
@Serializable data object SignupRoute
@Serializable data class ForgotRoute(val email: String = "")
@Serializable data class ResetRoute(val token: String)
@Serializable data class VerifyRoute(val token: String)
