package com.pathwayai.backend.common.discovery;

import com.pathwayai.backend.common.exception.ResourceNotFoundException;

/**
 * The program exists but is not shown because it is not verified or none of its official sources was read
 * successfully recently. It is the same 404 and the same error body as an unknown program; only the message is
 * specific, so a client can say something useful instead of "not found".
 */
public class ProgramWithheldException extends ResourceNotFoundException {

    public ProgramWithheldException(String identifier, int maxSourceAgeDays) {
        super("Program withheld: " + identifier + " is not shown right now because it is not verified, none of its"
                + " official sources was read successfully in the last " + maxSourceAgeDays + " days, or a source that maintains"
                + " its facts is failing. Its data is kept"
                + " and is shown again once a source check succeeds.");
    }
}
