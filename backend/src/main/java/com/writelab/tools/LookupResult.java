package com.writelab.tools;

import com.writelab.dictionary.DictionaryEntry;

public record LookupResult(ToolInvocationStatus status, DictionaryEntry entry) {

    public boolean found() {
        return status == ToolInvocationStatus.SUCCESS;
    }
}
