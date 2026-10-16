package com.moneylog.core.profile.exception;

import com.moneylog.core.sk.exception.NotFoundException;

public final class ProfileExceptions {

    private ProfileExceptions() {
    }

    public static class ProfilePhotoNotFoundException extends NotFoundException {
        public ProfilePhotoNotFoundException() {
            super("PROFILE_PHOTO_NOT_FOUND", "Você ainda não tem foto de perfil.");
        }
    }
}
