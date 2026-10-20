package com.moneylog.core.invitation.exception;

import java.util.UUID;

import com.moneylog.core.auth.exception.InvitationErrorCodes;
import com.moneylog.core.mail.app.EmailDeliveryException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class InvitationExceptions {

    private InvitationExceptions() {
    }

    public static class InvalidInvitationException extends NotFoundException {
        public InvalidInvitationException() {
            super(InvitationErrorCodes.INVALID_INVITATION, "Convite inválido, expirado ou já utilizado.");
        }
    }

    public static class InvitationNotFoundException extends NotFoundException {
        public InvitationNotFoundException() {
            super(InvitationErrorCodes.INVITATION_NOT_FOUND, "Convite não encontrado.");
        }
    }

    public static class InvitationsDisabledException extends NotFoundException {
        public InvitationsDisabledException() {
            super(InvitationErrorCodes.INVITATIONS_DISABLED, "Os convites estão desligados neste ambiente.");
        }
    }

    public static class EmailAlreadyRegisteredException extends ConflictException {
        public EmailAlreadyRegisteredException() {
            super(InvitationErrorCodes.EMAIL_ALREADY_REGISTERED, "Este e-mail já pertence a um usuário.");
        }
    }

    public static class InvitationPendingException extends ConflictException implements HasInvitationId {
        private final transient UUID invitationId;

        public InvitationPendingException(UUID invitationId) {
            super(InvitationErrorCodes.INVITATION_PENDING, "Já existe um convite pendente para este e-mail. Reenvie o existente.");
            this.invitationId = invitationId;
        }

        @Override
        public UUID invitationId() {
            return invitationId;
        }
    }

    public static class UserLimitReachedException extends ConflictException {
        public UserLimitReachedException() {
            super(InvitationErrorCodes.USER_LIMIT_REACHED, "O limite de usuários foi atingido.");
        }
    }

    public static class InvitationNotResendableException extends ConflictException {
        public InvitationNotResendableException() {
            super(InvitationErrorCodes.INVITATION_NOT_RESENDABLE, "Um convite aceito ou revogado não pode ser reenviado.");
        }
    }

    public static class InvitationNotRevocableException extends ConflictException {
        public InvitationNotRevocableException() {
            super(InvitationErrorCodes.INVITATION_NOT_REVOCABLE, "Um convite já aceito não pode ser revogado.");
        }
    }

    public static class InvitationDeliveryFailedException extends EmailDeliveryException implements HasInvitationId {
        private final transient UUID invitationId;

        public InvitationDeliveryFailedException(UUID invitationId) {
            super("Não foi possível enviar o e-mail do convite. Ele foi salvo e pode ser reenviado.");
            this.invitationId = invitationId;
        }

        @Override
        public UUID invitationId() {
            return invitationId;
        }
    }
}
