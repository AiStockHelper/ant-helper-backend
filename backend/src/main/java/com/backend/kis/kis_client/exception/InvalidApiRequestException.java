package com.backend.kis.kis_client.exception;

import lombok.Getter;

@Getter
public class InvalidApiRequestException extends KisClientException {

    private String message;
    private int statusCode;

    public InvalidApiRequestException(String message){
        super(message);

        this.message = message;
    }
    public InvalidApiRequestException(String message, Exception e){
        super(message, e);

        this.message = message;
    }

    public InvalidApiRequestException(String message, int statusCode){
        super(message + "[status code : " + statusCode + "]");

        this.message = message;
        this.statusCode = statusCode;
    }

}
