package com.moneylog.infra.note.controller;

import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.moneylog.core.note.app.NoteQuery;
import com.moneylog.core.note.app.NoteSort;
import com.moneylog.core.note.app.NoteStatus;
import com.moneylog.core.note.app.usecase.ListNotesUseCase;
import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.note.controller.NoteResponses.NoteSummaryResponse;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

@RestController
@RequestMapping("/v1/links")
@Validated
public class LinkController {

    private final ListNotesUseCase list;

    public LinkController(ListNotesUseCase list) {
        this.list = list;
    }

    @GetMapping
    public SliceResponse<NoteSummaryResponse> notesLinkedTo(@CurrentUser UUID userId, @RequestParam @NotNull NoteLinkType targetType,
                                                            @RequestParam(required = false) UUID targetId,
                                                            @RequestParam(required = false) @Size(max = 7) String targetKey, PageQuery page) {
        NoteController.requireLinkFilter(targetType, targetId, targetKey, "targetType", "targetId", "targetKey");
        return SliceResponse.of(list.handle(new NoteQuery(userId, null, null, null, targetType, targetId, targetKey, NoteStatus.ACTIVE,
                NoteSort.UPDATED, page)), NoteSummaryResponse::of);
    }
}
