package com.model;

import java.util.List;

public class ChatCompletionResponse {
    String id;
    String object;
    Long createdOn;
    String model;
    List<ChatCompletionResponseChoice> choices;
    ChatCompletionResponseUsage usage;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public Long getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(Long createdOn) {
        this.createdOn = createdOn;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatCompletionResponseChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<ChatCompletionResponseChoice> choices) {
        this.choices = choices;
    }

    public ChatCompletionResponseUsage getUsage() {
        return usage;
    }

    public void setUsage(ChatCompletionResponseUsage usage) {
        this.usage = usage;
    }

}
