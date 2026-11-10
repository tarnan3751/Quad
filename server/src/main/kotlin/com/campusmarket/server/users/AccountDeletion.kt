package com.campusmarket.server.users

import com.campusmarket.server.conflict
import com.campusmarket.server.queryOne
import com.campusmarket.server.unauthorized
import com.campusmarket.server.update
import java.sql.Connection
import java.util.UUID

/**
 * Account deletion (app-store requirement): soft-delete, anonymise, end all sessions, take down live listings.
 * Refused (409) while money is in flight on one of the user's orders (awaiting payment, paid, or disputed).
 *
 * The one implementation behind both `DELETE /me` and the credential-authenticated `POST /auth/delete-account`
 * (which suspended users, who can't hold a session, use). Runs in the caller's transaction.
 */
internal fun deleteAccount(c: Connection, userId: UUID) {
    c.queryOne("SELECT 1 FROM users WHERE id = ? AND deleted_at IS NULL FOR UPDATE", userId) { true } ?: unauthorized()
    // PAID and DISPUTED hold the buyer's money; PENDING_PAYMENT too, as a checkout could still complete for a deleted party.
    val openOrders = c.queryOne(
        "SELECT count(*) FROM orders WHERE (buyer_id = ? OR seller_id = ?) AND status IN ('PENDING_PAYMENT', 'PAID', 'DISPUTED')",
        userId, userId,
    ) { it.getInt(1) } ?: 0
    if (openOrders > 0) {
        conflict("You have orders in progress. Complete or cancel them before deleting your account.")
    }
    c.update(
        """UPDATE users SET deleted_at = now(),
                            email = ?, display_name = 'Deleted user', bio = '', avatar_photo_id = NULL,
                            password_hash = '!deleted',
                            verification_code_hash = NULL, verification_expires_at = NULL, verification_attempts = 0
           WHERE id = ?""",
        "deleted+$userId@deleted.invalid", userId,
    )
    c.update("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId)
    c.update(
        "UPDATE listings SET status = 'DELETED', updated_at = now() WHERE seller_id = ? AND status IN ('ACTIVE', 'RESERVED')",
        userId,
    )
    c.update("DELETE FROM favorites WHERE user_id = ?", userId)
    c.update("DELETE FROM blocks WHERE blocker_id = ?", userId)
}
