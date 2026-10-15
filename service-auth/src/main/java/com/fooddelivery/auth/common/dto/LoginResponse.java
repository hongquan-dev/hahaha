package com.fooddelivery.auth.common.dto;

public class LoginResponse {
    private String accessToken;
    private String tokenType = "Bearer";
    private String message;

    public LoginResponse(String accessToken, String message) {
        this.accessToken = accessToken;
        this.message = message;
    }

    // Getters and Setters
    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}