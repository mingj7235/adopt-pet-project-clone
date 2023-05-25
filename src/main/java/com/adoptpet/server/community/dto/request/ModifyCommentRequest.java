package com.adoptpet.server.community.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Getter
public class ModifyCommentRequest {

    @Min(value = 0)
    @JsonProperty("id")
    private Integer commentNo;

    @NotNull
    @JsonProperty("context")
    private String content;
}
