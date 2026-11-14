package com.pathwayai.backend.common.discovery;

import com.pathwayai.backend.common.exception.ResourceNotFoundException;

/**
 * The program exists but is not shown because it is not verified, was discontinued by its official source, or no
 * working extractor has read its official source recently. It is the same 404 and the same error body as an unknown
 * program; only the message is specific, so a client can say something useful instead of "not found".
 */
public class ProgramWithheldException extends ResourceNotFoundException {

    public ProgramWithheldException(String identifier, int maxSourceAgeDays) {
        super("Program withheld: " + identifier + " is not shown right now because it is not verified, its official source states"
                + " it is discontinued, no extractor of its official source ran successfully in the last " + maxSourceAgeDays
                + " days, or a source that maintains its facts is failing. Its data is kept"
                + " and is shown again once an extractor run succeeds.");
    }
}
