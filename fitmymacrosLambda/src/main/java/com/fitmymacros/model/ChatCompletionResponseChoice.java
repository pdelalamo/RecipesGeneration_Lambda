package com.fitmymacros.model;

public class ChatCompletionResponseChoice {
    ChatCompletionResponseChoiceMessage message;
    Integer index;
    String finishReason;

    public ChatCompletionResponseChoiceMessage getMessage() {
        return message;
    }

    public void setMessage(ChatCompletionResponseChoiceMessage message) {
        this.message = message;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

}