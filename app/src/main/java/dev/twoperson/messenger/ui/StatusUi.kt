package dev.twoperson.messenger.ui

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Contact
import dev.twoperson.messenger.engine.ConnectionState
import dev.twoperson.messenger.engine.ConnectionStatus
import dev.twoperson.messenger.engine.DirectKind

/** Shared mapping between engine states and the labels/colours used across screens. */
object StatusUi {

    data class Style(@StringRes val label: Int, @ColorRes val color: Int)

    fun of(status: ConnectionStatus): Style = when (status.state) {
        ConnectionState.NOT_PAIRED -> Style(R.string.state_not_paired, R.color.status_idle)
        ConnectionState.IDLE -> Style(R.string.state_idle, R.color.status_idle)
        ConnectionState.DISCOVERING -> Style(R.string.state_discovering, R.color.status_warn)
        ConnectionState.CONNECTING -> Style(R.string.state_connecting, R.color.status_warn)
        ConnectionState.CONNECTED -> Style(
            if (status.direct == DirectKind.LOCAL_NETWORK) {
                R.string.state_connected_lan
            } else {
                R.string.state_connected_internet
            },
            R.color.status_ok
        )

        ConnectionState.DISCONNECTED -> Style(R.string.state_disconnected, R.color.status_warn)
        ConnectionState.UNABLE_TO_CONNECT -> Style(R.string.state_unable, R.color.status_error)
    }

    /** A compact state for a contacts-list chip. */
    @StringRes
    fun contactState(contact: Contact, activeId: String?): Int = when {
        contact.id == activeId && contact.isOnKnot -> R.string.contacts_state_active
        contact.isPaired -> R.string.contacts_state_paired
        contact.hasInvitation -> R.string.contacts_state_invitation
        else -> R.string.contacts_state_local
    }

    fun connectionLine(contact: Contact, active: Boolean, status: ConnectionStatus): Int =
        if (active && contact.hasInvitation) {
            of(status).label
        } else if (contact.isPaired) {
            R.string.contacts_state_paired
        } else if (contact.hasInvitation) {
            R.string.contacts_state_invitation
        } else {
            R.string.state_not_paired
        }
}
