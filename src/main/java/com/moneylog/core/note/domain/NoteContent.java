package com.moneylog.core.note.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import com.moneylog.core.note.exception.NoteExceptions.NoteFrontmatterReservedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteTooLargeException;
import com.moneylog.core.sk.text.PlainText;

public record NoteContent(String text, String frontmatterTitle, Set<String> tags, List<Wikilink> wikilinks, int bodyStart) {

    public static NoteContent analyze(String text) {
        validateText(text);
        Frontmatter.Parsed frontmatter = Frontmatter.parse(text);
        if (frontmatter.hasMoneylogKey()) {
            throw new NoteFrontmatterReservedException();
        }
        int bodyStart = frontmatter.bodyStart();
        List<Wikilink> wikilinks = WikilinkParser.parse(text, bodyStart);
        Set<String> tags = NoteTags.extract(text, bodyStart, frontmatter.rawTags(), wikilinks);
        String title = frontmatter.title().map(NoteContent::requireSafeTitle).map(NoteContent::cap).orElse(null);
        return new NoteContent(text, title, tags, wikilinks, bodyStart);
    }

    public static void validateText(String text) {
        if (text.length() > NoteLimits.NOTE_MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > NoteLimits.NOTE_MAX_BYTES) {
            throw new NoteTooLargeException(NoteLimits.NOTE_MAX_BYTES);
        }
        PlainText.requireClean(text, "body", true);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new PlainText.TextInvalidCharactersException("body");
            }
        }
    }

    public String titleFor(NotePath path) {
        return frontmatterTitle != null ? frontmatterTitle : cap(path.name());
    }

    private static String requireSafeTitle(String title) {
        if (!PlainText.isSafeSingleLine(title)) {
            throw new PlainText.TextInvalidCharactersException("title");
        }
        return title;
    }

    private static String cap(String title) {
        return CodePoints.truncate(title, NoteLimits.MAX_TITLE_LENGTH);
    }
}
